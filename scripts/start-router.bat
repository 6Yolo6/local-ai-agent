@echo off
chcp 65001 > nul
setlocal

echo ============================
echo Router Layer Starting...
echo ============================

set "NODE_HOME=D:\Dev\NodeJs-24"
set "ROUTER_DIR=D:\Dev\project\local-ai-agent\router-layer"
set "PATH=%NODE_HOME%;%PATH%"

if not exist "%NODE_HOME%\node.exe" (
    echo node.exe not found: %NODE_HOME%\node.exe
    pause
    exit /b 1
)

if not exist "%ROUTER_DIR%\package.json" (
    echo package.json not found: %ROUTER_DIR%\package.json
    pause
    exit /b 1
)

cd /d "%ROUTER_DIR%"

if not exist "node_modules" (
    echo Installing router dependencies...
    call npm install
    if errorlevel 1 (
        echo npm install failed.
        pause
        exit /b 1
    )
)

call npm start

pause
