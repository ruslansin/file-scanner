@echo off
cd /d "%~dp0"
echo ==^> File Scanner - build ^& launch...
call mvn -q compile javafx:run
pause
