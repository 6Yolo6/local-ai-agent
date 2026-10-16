@echo off
chcp 65001 > nul
echo.
echo  ╔══════════════════════════════════════════════════════╗
echo  ║   停止所有 AI Agent 服务                              ║
echo  ╚══════════════════════════════════════════════════════╝
echo.

echo  正在停止服务...
echo.

rem ── 停止 llama-server（llama.cpp）────────────────────────────
tasklist /fi "imagename eq llama-server.exe" /fo csv /nh 2>nul | findstr /i "llama-server" >nul
if not errorlevel 1 (
    taskkill /f /im llama-server.exe >nul 2>&1
    echo  ✓ llama.cpp 已停止
) else (
    echo  - llama.cpp 未运行
)

rem ── 停止 java.exe（Spring Boot MCP Server）────────────────────
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":5000 " ^| findstr "LISTENING"') do (
    tasklist /fi "pid eq %%p" /fo csv /nh 2>nul | findstr /i "java" >nul
    if not errorlevel 1 (
        taskkill /f /pid %%p >nul 2>&1
        echo  ✓ Spring Boot MCP Server (PID=%%p) 已停止
    )
)

rem ── 停止 node.exe（Vue3 Vite Dev Server，端口 3000）───────────
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":3000 " ^| findstr "LISTENING"') do (
    tasklist /fi "pid eq %%p" /fo csv /nh 2>nul | findstr /i "node" >nul
    if not errorlevel 1 (
        taskkill /f /pid %%p >nul 2>&1
        echo  ✓ Vue3 Frontend (PID=%%p) 已停止
    )
)

rem ── 停止 node.exe（Router Layer，端口 5002）───────────────────
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":5002 " ^| findstr "LISTENING"') do (
    tasklist /fi "pid eq %%p" /fo csv /nh 2>nul | findstr /i "node" >nul
    if not errorlevel 1 (
        taskkill /f /pid %%p >nul 2>&1
        echo  ✓ Router Layer (PID=%%p) 已停止
    )
)

rem ── 停止 Ollama 进程────────────────────────────────────────────
tasklist /fi "imagename eq ollama.exe" /fo csv /nh 2>nul | findstr /i "ollama" >nul
if not errorlevel 1 (
    taskkill /f /im ollama.exe >nul 2>&1
    echo  ✓ Ollama 已停止
) else (
    echo  - Ollama 未运行
)

echo.
echo  所有服务已停止。
echo.
pause
