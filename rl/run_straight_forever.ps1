# Runs the two trainings forever. Started by the Windows scheduled task "HumanoidRobot_StraightTraining" (at logon),
# or by hand:  powershell -ExecutionPolicy Bypass -File run_straight_forever.ps1
#  1) RL straight-walk policy  (train.py ... straighter)          -> low priority, logs runs_straighter.log / .err
#  2) keyframe skills search   (skills_forever.py, 6 skills)      -> normal priority, logs skills_forever.log / .err
# Each one is restarted 30 seconds after it exits (they resume from their own saved files).
# Stop: create STOP.txt (stops both) or STOP_SKILLS.txt (stops only the skills search) next to this script, then stop the process.
$rl = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $rl
$py = "C:\Users\HurtMaker\robot_rl\venv\Scripts\python.exe"
$env:PYTHONIOENCODING = "utf-8"

function Start-One($argList, $outLog, $errLog, $prio) {
    $p = Start-Process -FilePath $py -ArgumentList $argList -WorkingDirectory $rl `
        -RedirectStandardOutput (Join-Path $rl $outLog) -RedirectStandardError (Join-Path $rl $errLog) `
        -WindowStyle Hidden -PassThru
    try { $p.PriorityClass = $prio } catch {}
    return $p
}

$rlArgs = @("-u", "train.py", "0", "straighter", "--from", "straighter", "--forever", "--clip-minutes", "30")
$skArgs = @("-u", "skills_forever.py", "--workers", "10", "--chunk", "30", "--clip-minutes", "30")
$p1 = $null; $p2 = $null
$t1 = Get-Date; $t2 = Get-Date

while (-not (Test-Path (Join-Path $rl "STOP.txt"))) {
    $now = Get-Date
    if (($p1 -eq $null) -or $p1.HasExited) {
        if (($p1 -eq $null) -or (($now - $t1).TotalSeconds -ge 30)) { $p1 = Start-One $rlArgs "runs_straighter.log" "runs_straighter.err" "BelowNormal"; $t1 = $now }
    } else { $t1 = $now }
    if (-not (Test-Path (Join-Path $rl "STOP_SKILLS.txt"))) {
        if (($p2 -eq $null) -or $p2.HasExited) {
            if (($p2 -eq $null) -or (($now - $t2).TotalSeconds -ge 30)) { $p2 = Start-One $skArgs "skills_forever.log" "skills_forever.err" "Normal"; $t2 = $now }
        } else { $t2 = $now }
    }
    Start-Sleep -Seconds 10
}
