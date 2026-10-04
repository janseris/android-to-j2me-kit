@echo off
rem Over-the-air install server for the Nokia 9300 (see ota_server.js).
rem Needs Node.js (the same one the build uses). Stop it with Ctrl+C.
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Node.js not found. Install it from https://nodejs.org and run this again.
  pause
  exit /b 1
)
node ota_server.js 8000
pause
