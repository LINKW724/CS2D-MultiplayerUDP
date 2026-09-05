@echo off
setlocal
set "APP_DIR=%~dp0"
java -cp "%APP_DIR%lib\*" cs2d.ClientMain
if errorlevel 1 pause

