@echo off
chcp 65001 > nul
setlocal

set "NODE_HOME=D:\Dev\NodeJs-24"
set "FRONTEND_DIR=D:\Dev\project\local-ai-agent\frontend"
set "NPM_CACHE=D:\Dev\Nodejs\node_cache"
set "PATH=%NODE_HOME%;%PATH%"

if not exist "%NODE_HOME%\node.exe" (
    echo node.exe not found: %NODE_HOME%\node.exe
    pause
    exit /b 1
)

if not exist "%FRONTEND_DIR%\package.json" (
    echo package.json not found: %FRONTEND_DIR%\package.json
    pause
    exit /b 1
)

cd /d "%FRONTEND_DIR%"

call npm config set cache "%NPM_CACHE%" > nul 2>&1

if not exist "node_modules" (
    echo Installing frontend dependencies...
    call npm install
    if errorlevel 1 (
        echo npm install failed.
        pause
        exit /b 1
    )
)

echo Starting frontend...
echo http://localhost:3000

call npm run dev -- --host 0.0.0.0 --port 3000

echo.
echo Frontend exited.
pause