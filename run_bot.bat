@echo off
cd /d "%~dp0"
set LOG_DIR=%~dp0logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

:loop
echo [%date% %time%] Starting AutoTrading bot...
call gradlew.bat run >> "%LOG_DIR%\bot_%date:~0,4%%date:~5,2%%date:~8,2%.log" 2>&1
echo [%date% %time%] Bot stopped. Restarting in 10 seconds...
timeout /t 10 /nobreak
goto loop