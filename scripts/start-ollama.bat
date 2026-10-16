@echo off
chcp 65001 > nul
setlocal

set "OLLAMA_HOME=D:\Dev\Ollama"
set "PATH=%OLLAMA_HOME%;%PATH%"

netstat -ano | findstr :11434 > nul
if not errorlevel 1 (
    echo Ollama already running on 11434
    pause
    exit /b 0
)

if not exist "%OLLAMA_HOME%\ollama.exe" (
    echo ollama.exe not found
    pause
    exit /b 1
)

cd /d "%OLLAMA_HOME%"
echo Starting Ollama...
echo http://127.0.0.1:11434

ollama serve

echo.
echo Ollama exited.
pause