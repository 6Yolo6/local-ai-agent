@echo off
chcp 65001 > nul
setlocal

REM Keep the window open for startup errors.
set "LLAMA_EXE=D:\llama_cpp\llama-server.exe"
set "MODEL_PATH=E:\AIModel\gguf\Qwen_Qwen3.6-35B-A3B-Q4_K_M.gguf"

if not exist "%LLAMA_EXE%" (
    echo llama-server.exe not found: %LLAMA_EXE%
    pause
    exit /b 1
)

if not exist "%MODEL_PATH%" (
    echo model file not found: %MODEL_PATH%
    pause
    exit /b 1
)

echo Starting llama.cpp...
echo %LLAMA_EXE%
echo Model: %MODEL_PATH%

"%LLAMA_EXE%" ^
-m "%MODEL_PATH%" ^
-ngl 35 ^
--main-gpu 0 ^
-c 16384 ^
--cache-type-k q4_0 ^
--cache-type-v q4_0 ^
--threads 16 ^
--port 8080 ^
--flash-attn auto

pause
