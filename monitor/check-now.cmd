@echo off
rem Runs the YouTube Music monitor on this PC and shows the results. No alerts are sent.
cd /d "%~dp0.."
call gradlew.bat -p monitor run -q --console=plain
echo.
pause
