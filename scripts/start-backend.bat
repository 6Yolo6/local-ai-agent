@echo off
chcp 65001 > nul
setlocal

set "JAVA_HOME=D:\Dev\JDK17"
set "MAVEN_HOME=D:\Dev\Maven\apache-maven-3.9.9"
set "PROJECT_DIR=D:\Dev\project\local-ai-agent\backend"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('LOCAL_AI_QQ_BOT_TOKEN','User')"`) do set "LOCAL_AI_QQ_BOT_TOKEN=%%V"
if not defined LOCAL_AI_QQ_BOT_TOKEN (
    for /f "usebackq delims=" %%V in (`powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('TENCENT_CHANNEL_BOT_TOKEN','User')"`) do set "LOCAL_AI_QQ_BOT_TOKEN=%%V"
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo java.exe not found: %JAVA_HOME%\bin\java.exe
    pause
    exit /b 1
)

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo mvn.cmd not found: %MAVEN_HOME%\bin\mvn.cmd
    pause
    exit /b 1
)

if not exist "%PROJECT_DIR%\pom.xml" (
    echo pom.xml not found: %PROJECT_DIR%\pom.xml
    pause
    exit /b 1
)

cd /d "%PROJECT_DIR%"

if not exist "target\*.jar" (
    echo Building backend...
    call "%MAVEN_HOME%\bin\mvn.cmd" clean package -DskipTests
    if errorlevel 1 (
        echo Maven build failed.
        pause
        exit /b 1
    )
)

set "JAR="
for %%J in (target\*.jar) do set "JAR=%%J"

if not defined JAR (
    echo No jar found in target folder.
    pause
    exit /b 1
)

echo Starting backend...
echo JAR: %JAR%
echo http://127.0.0.1:5000

"%JAVA_HOME%\bin\java.exe" -Xms256m -Xmx512m -Dfile.encoding=UTF-8 -jar "%JAR%"

echo.
echo Backend exited.
pause
