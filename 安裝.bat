@echo off
chcp 65001 >nul
setlocal

rem ============================================================
rem  安裝:檢查並自動安裝 MotorAdjPlus 需要的東西
rem    1. Java(用 winget 安裝)
rem    2. arduino-cli(從 downloads.arduino.cc 下載,放在 Tools_Arduino)
rem    3. Arduino AVR 板子支援(arduino-cli core install arduino:avr)
rem  已經有的會自動略過。需要網路。
rem  測試模式:先 set DRYRUN=1 再執行,只檢查、不下載。
rem ============================================================

set "ROOT=%~dp0"
set "CLI=%ROOT%Tools_Arduino\arduino-cli.exe"

echo.
echo ===== [1/3] 檢查 Java =====
java -version >nul 2>&1
if not errorlevel 1 goto have_java
echo 找不到 Java。
if defined DRYRUN goto dry_java
where winget >nul 2>&1
if errorlevel 1 goto no_winget
echo 用 winget 安裝 Java(Temurin 21)...
winget install --id EclipseAdoptium.Temurin.21.JRE -e --accept-package-agreements --accept-source-agreements
if errorlevel 1 goto java_fail
echo Java 安裝完成。若稍後 MotorAdj 打不開,請重新開機後再試。
goto step2
:dry_java
echo [DRYRUN] 這裡會用 winget 安裝 Java
goto step2
:no_winget
echo 這台電腦沒有 winget。請自行安裝 Java,下載網址:https://adoptium.net
pause
exit /b 1
:java_fail
echo Java 安裝失敗。請自行安裝 Java,下載網址:https://adoptium.net
pause
exit /b 1
:have_java
echo Java 已安裝。

:step2
echo.
echo ===== [2/3] 檢查 arduino-cli =====
if exist "%CLI%" goto have_cli
echo 找不到 %CLI%
if defined DRYRUN goto dry_cli
echo 下載 arduino-cli(約 25 MB)...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $z=Join-Path $env:TEMP 'arduino-cli.zip'; Invoke-WebRequest 'https://downloads.arduino.cc/arduino-cli/arduino-cli_latest_Windows_64bit.zip' -OutFile $z; New-Item -ItemType Directory -Force '%ROOT%Tools_Arduino' | Out-Null; Expand-Archive $z -DestinationPath '%ROOT%Tools_Arduino' -Force; Remove-Item $z"
if errorlevel 1 goto cli_fail
if not exist "%CLI%" goto cli_fail
echo arduino-cli 安裝完成。
goto step3
:dry_cli
echo [DRYRUN] 這裡會下載 arduino-cli 到 Tools_Arduino
goto step3
:cli_fail
echo arduino-cli 下載失敗。請檢查網路,或自行下載後放到 Tools_Arduino 資料夾。
pause
exit /b 1
:have_cli
echo arduino-cli 已存在。

:step3
echo.
echo ===== [3/3] 檢查 Arduino AVR 板子支援 =====
if not exist "%CLI%" goto dry_core
"%CLI%" core list > "%TEMP%\_cores.txt" 2>nul
findstr /i /c:"arduino:avr" "%TEMP%\_cores.txt" >nul
if not errorlevel 1 goto have_core
echo 尚未安裝 arduino:avr。
if defined DRYRUN goto dry_core
echo 安裝 arduino:avr(需要下載,請稍候)...
"%CLI%" core update-index
"%CLI%" core install arduino:avr
if errorlevel 1 goto core_fail
echo arduino:avr 安裝完成。
goto done
:dry_core
echo [DRYRUN] 這裡會安裝 arduino:avr
goto done
:core_fail
echo arduino:avr 安裝失敗。請檢查網路後再執行一次。
pause
exit /b 1
:have_core
echo arduino:avr 已安裝。

:done
echo.
echo ===== 完成 =====
echo 現在可以雙擊 Tools_MotorAdj\MotorAdjPlus.jar 開始使用。
echo 如果資料夾位置和預設不同,請在 MotorAdj 的「路徑設定…」按鈕指定。
if not defined DRYRUN pause
exit /b 0
