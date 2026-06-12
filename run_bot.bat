@echo off
cd /d "%~dp0"
set LOG_DIR=%~dp0logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

:loop
echo [%date% %time%] Starting AutoTrading bot...
if exist "%~dp0build\package\AutoTrading\AutoTrading.exe" (
    "%~dp0build\package\AutoTrading\AutoTrading.exe" >> "%LOG_DIR%\bot_%date:~0,4%%date:~5,2%%date:~8,2%.log" 2>&1
) else (
    call gradlew.bat run >> "%LOG_DIR%\bot_%date:~0,4%%date:~5,2%%date:~8,2%.log" 2>&1
)
echo [%date% %time%] Bot stopped. Restarting in 10 seconds...
ping -n 11 127.0.0.1 > nul
goto loop