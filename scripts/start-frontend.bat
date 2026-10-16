@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local.ps1" -Service frontend %*
exit /b %ERRORLEVEL%
