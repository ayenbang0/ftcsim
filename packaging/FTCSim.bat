@echo off
rem Fallback launcher: same program as FTCSim.exe, started through the bundled Java runtime.
rem Use it when Windows (Smart App Control / Application Control) blocks the unsigned FTCSim.exe.
cd /d "%~dp0"
start "" "%~dp0runtime\bin\javaw.exe" -Xmx1g "-Dftcsim.home=%~dp0." -cp "%~dp0app\*" ftcsim.desktop.DesktopMain %*
