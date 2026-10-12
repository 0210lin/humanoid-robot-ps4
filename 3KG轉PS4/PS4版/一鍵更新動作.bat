@echo off
chcp 65001 >nul
setlocal

rem ============================================================
rem  一鍵更新動作:把 MotorAdj 輸出的 motor.h 複製到 Micro_Robot,
rem  編譯並燒錄到 Arduino Micro。
rem
rem  使用前:1. 在 MotorAdj 輸出 motor.h(存在 Tools_MotorAdj 資料夾)
rem         2. 關閉 MotorAdj 的連線(序列埠只能一個程式用)
rem         3. 雙擊這個檔案
rem ============================================================

set "ROOT=%~dp0"
set "SRC=%ROOT%Tools_MotorAdj\motor.h"
set "DST=%ROOT%Micro_Robot\motor.h"
set "CLI=%ROOT%Tools_Arduino\arduino-cli.exe"
set "SKETCH=%ROOT%Micro_Robot"
set "FQBN=arduino:avr:micro"
rem 序列埠:留空 = 自動偵測 Arduino Micro;要固定的話寫成 set "PORT=COM5"
set "PORT="

if not exist "%SRC%" (
  echo [錯誤] 找不到 %SRC%
  echo        請先在 MotorAdj 輸出 motor.h,或修改這個檔案開頭的 SRC 路徑。
  pause
  exit /b 1
)

echo [1/4] 備份舊的 motor.h
if not exist "%ROOT%backup" mkdir "%ROOT%backup"
for /f %%i in ('powershell -nologo -command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%i"
copy /y "%DST%" "%ROOT%backup\motor.h.bak_%TS%" >nul

echo [2/4] 複製新的 motor.h
copy /y "%SRC%" "%DST%" >nul
if errorlevel 1 (
  echo [錯誤] 複製失敗
  pause
  exit /b 1
)

echo [3/4] 編譯
"%CLI%" compile -b %FQBN% "%SKETCH%"
if errorlevel 1 (
  echo.
  echo [錯誤] 編譯失敗,沒有燒錄。舊的 motor.h 已備份在 backup 資料夾。
  pause
  exit /b 1
)

if defined NOUPLOAD (
  echo [略過燒錄] NOUPLOAD 已設定
  exit /b 0
)

echo [4/4] 燒錄
if not defined PORT (
  "%CLI%" board list > "%TEMP%\_ports.txt" 2>nul
  for /f "tokens=1" %%p in ('findstr /i /c:"micro" /c:"leonardo" "%TEMP%\_ports.txt"') do set "PORT=%%p"
)
if not defined PORT (
  echo [錯誤] 找不到 Arduino Micro 的序列埠。請確認 USB 已接上,
  echo        且 MotorAdj 已中斷連線,或在這個檔案裡手動設定 PORT。
  pause
  exit /b 1
)
echo 使用序列埠 %PORT%
"%CLI%" upload -p %PORT% -b %FQBN% "%SKETCH%"
if errorlevel 1 (
  echo.
  echo [錯誤] 燒錄失敗。請確認 MotorAdj 已中斷連線(序列埠只能一個程式用)。
  pause
  exit /b 1
)

echo.
echo 完成!動作已更新到機器人。
pause
