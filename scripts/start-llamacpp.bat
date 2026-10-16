@echo off
setlocal
if not defined LOCAL_AI_LLAMACPP_EXE (
    echo Optional engine. Set LOCAL_AI_LLAMACPP_EXE and LOCAL_AI_GGUF_MODEL first.
    echo The default assistant uses Ollama: scripts\start-all.bat
    exit /b 1
)
if not defined LOCAL_AI_GGUF_MODEL exit /b 1
"%LOCAL_AI_LLAMACPP_EXE%" -m "%LOCAL_AI_GGUF_MODEL%" --host 127.0.0.1 --port 8080 -c 4096 -ngl 99
