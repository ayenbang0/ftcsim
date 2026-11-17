@echo off
rem Console launcher (batch scoring runs, --self-test, --help); same as FTCSim-cli.exe.
rem   FTCSim-cli.bat --batch --autos C:\path\to\autos
cd /d "%~dp0"
"%~dp0runtime\bin\java.exe" -Xmx1g "-Dftcsim.home=%~dp0." -Dftcsim.console=true -cp "%~dp0app\*" ftcsim.desktop.DesktopMain %*
