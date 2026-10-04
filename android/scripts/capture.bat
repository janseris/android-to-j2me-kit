@echo off
rem Capture only the app's backend and save it.
rem   capture.bat "api\.example\.com" myapp
rem Regex of hosts to intercept (others pass through untouched, so other apps keep working).
if "%~1"=="" (echo usage: capture.bat "host-regex" name & exit /b 1)
set NAME=%~2
if "%NAME%"=="" set NAME=capture
mitmweb --listen-port 8080 --allow-hosts "%~1" --set save_stream_file=%NAME%.flow --set hardump=%NAME%.har
