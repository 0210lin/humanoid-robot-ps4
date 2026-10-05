@echo off
chcp 65001 >nul
rem 重建機器人 3D 的結構(馬達 ↔ 伺服的對照改過之後執行)。舊的 rig.json 會先備份成 rig.json.bak
java -cp "%~dp0Tools_MotorAdj\MotorAdjPlus.jar" BuildRig "%~dp0robot_stl"
echo.
echo 完成。重開 MotorAdj 的「機器人 3D」分頁(或按「重新載入」)就會用新的結構。
pause