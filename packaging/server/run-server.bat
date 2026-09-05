@echo off
setlocal
cd /d "%~dp0"
java -jar cs2d-server.jar
if errorlevel 1 pause

