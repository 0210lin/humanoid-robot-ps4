@echo off
rem Build with the C# compiler built into Windows (nothing to install)
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
"%CSC%" /nologo /target:winexe /out:..\ServoID_Tool.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Net.Http.dll ServoIDTool.cs
if errorlevel 1 (echo BUILD FAILED) else (echo OK: ..\ServoID_Tool.exe)
