@echo off
rem Builds dist\FTCSim\FTCSim.exe (and dist\FTCSim-windows-x64.zip). Needs a full JDK 17+ (jpackage, jlink, jmods).
rem Set FTCSIM_JDK to the JDK folder if Gradle cannot find one, e.g. set FTCSIM_JDK=C:\Users\me\.jdks\jbr-21.0.11
cd /d "%~dp0"
call gradlew.bat :sim:packageExe --console=plain %*
if errorlevel 1 exit /b 1
echo.
echo Done: %~dp0dist\FTCSim\FTCSim.exe
