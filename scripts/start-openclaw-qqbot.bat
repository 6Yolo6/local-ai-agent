@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-openclaw-qqbot.ps1" %*
exit /b %ERRORLEVEL%
