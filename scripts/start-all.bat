@echo off
chcp 65001 > nul
setlocal

set "SCRIPTS=D:\Dev\project\local-ai-agent\scripts"
set "LLAMA_BAT=%SCRIPTS%\start-llamacpp.bat"
set "OLLAMA_BAT=%SCRIPTS%\start-ollama.bat"
set "BACKEND_BAT=%SCRIPTS%\start-backend.bat"
set "FRONTEND_BAT=%SCRIPTS%\start-frontend.bat"
:: OpenClaw MCP
set OPENCLAW_DIR=C:\Users\20317.YOLO\.openclaw
set OPENCLAW_CONFIG=%OPENCLAW_DIR%\openclaw.json
set OPENCLAW_EXEC=D:\Dev\node_global  :: openclaw��������Ŀ¼
set ANTHROPIC_API_KEY=sk-local-model-123456789  :: �滻Ϊ���API��Կ

:: ClaudeCode MCP
set CLAUDE_PATH=D:\Dev\Nodejs\node_global
:: ����ģ��ר�û������������ģ���
set ANTHROPIC_API_KEY=sk-local-123456
set ANTHROPIC_BASE_URL=http://127.0.0.1:8080/v1
:: �����ļ�·��
set CLAUDE_DIR=C:\Users\20317.YOLO\.claude
set CLAUDE_CONFIG=%CLAUDE_DIR%\settings.json

echo ==================================
echo Starting all services
echo ==================================

if not exist "%LLAMA_BAT%" (
    echo llama bat not found: %LLAMA_BAT%
    pause
    exit /b 1
)

if not exist "%OLLAMA_BAT%" (
    echo ollama bat not found: %OLLAMA_BAT%
    pause
    exit /b 1
)

if not exist "%BACKEND_BAT%" (
    echo backend bat not found: %BACKEND_BAT%
    pause
    exit /b 1
)

set "ROUTER_BAT=%SCRIPTS%\start-router.bat"

if not exist "%FRONTEND_BAT%" (
    echo frontend bat not found: %FRONTEND_BAT%
    pause
    exit /b 1
)

if not exist "%ROUTER_BAT%" (
    echo router bat not found: %ROUTER_BAT%
    pause
    exit /b 1
)

start "" cmd /k call "%LLAMA_BAT%"
timeout /t 3 /nobreak > nul

start "" cmd /k call "%OLLAMA_BAT%"
timeout /t 3 /nobreak > nul

start "" cmd /k call "%ROUTER_BAT%"
timeout /t 3 /nobreak > nul

start "" cmd /k call "%BACKEND_BAT%"
timeout /t 3 /nobreak > nul

start "" cmd /k call "%FRONTEND_BAT%"

echo.
echo All services launched.
pause