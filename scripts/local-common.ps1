$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalDir = Join-Path $ProjectRoot '.local'
$ConfigPath = Join-Path $LocalDir 'environment.json'
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw 'Run scripts/setup-local.ps1 first to detect this machine and save .local/environment.json.'
}
$LocalConfig = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$JavaExe = Join-Path $LocalConfig.javaHome 'bin/java.exe'
$MavenExe = Join-Path $LocalConfig.mavenHome 'bin/mvn.cmd'
$NodeExe = $LocalConfig.nodeExe
$NpmExe = Join-Path (Split-Path -Parent $NodeExe) 'npm.cmd'
$OllamaExe = $LocalConfig.ollamaExe
$env:JAVA_HOME = $LocalConfig.javaHome
$env:PATH = "$(Split-Path $JavaExe);$(Split-Path $MavenExe);$(Split-Path $NodeExe);$env:PATH"
$env:LOCAL_AI_WORKSPACE = $ProjectRoot.Replace('\', '/')
$env:LOCAL_AI_MAVEN_EXECUTABLE = $MavenExe
$env:LOCAL_AI_NODE_EXECUTABLE = $NodeExe
$env:LOCAL_AI_NPM_EXECUTABLE = $NpmExe
$env:LOCAL_AI_MODEL = $LocalConfig.model
$env:OLLAMA_HOST = '127.0.0.1:11434'
$env:LOCAL_AI_OLLAMA_URL = 'http://127.0.0.1:11434'
$env:OLLAMA_MODELS = $LocalConfig.modelsDir
$env:OLLAMA_CONTEXT_LENGTH = '4096'
$env:OLLAMA_NUM_PARALLEL = '1'
$env:OLLAMA_MAX_LOADED_MODELS = '1'
$env:OLLAMA_KEEP_ALIVE = '5m'
$env:npm_config_cache = Join-Path $LocalConfig.cacheDir 'npm'
$MavenRepository = Join-Path $LocalConfig.cacheDir 'maven'
foreach ($directory in @($LocalDir, (Join-Path $ProjectRoot 'logs'), $env:OLLAMA_MODELS, $env:npm_config_cache, $MavenRepository)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
function Test-LocalHttp([string]$Url) {
    try { $null = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3; return $true }
    catch { return $false }
}
function Assert-FreePort([int]$Port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) { throw "Port $Port is already in use by PID $($listener[0].OwningProcess). No process was stopped." }
}
function Get-ServiceRecord([string]$Name) {
    $recordPath = Join-Path $LocalDir "$Name.pid.json"
    if (-not (Test-Path -LiteralPath $recordPath)) { return $null }
    $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    $process = Get-Process -Id $record.processId -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetime]$record.startedAt).ToUniversalTime().Ticks -and
        $process.Path -eq $record.executable) { return $record }
    return $null
}
function Start-LocalProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory) {
    if (Get-ServiceRecord $Name) { Write-Host "$Name already running."; return }
    if (-not (Test-Path -LiteralPath $Executable)) { throw "Executable missing: $Executable" }
    $stdout = Join-Path $ProjectRoot "logs/$Name.out.log"
    $stderr = Join-Path $ProjectRoot "logs/$Name.err.log"
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    Start-Sleep -Milliseconds 300
    $process.Refresh()
    if ($process.HasExited) { throw "$Name exited immediately. See $stderr" }
    @{ processId = $process.Id; startedAt = $process.StartTime.ToUniversalTime().ToString('o'); executable = $process.Path } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $LocalDir "$Name.pid.json") -Encoding UTF8
    Write-Host "$Name started (PID $($process.Id))."
}
function Wait-LocalHttp([string]$Name, [string]$Url, [int]$Seconds = 60) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-LocalHttp $Url) { return }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become ready: $Url. See logs/$Name.err.log and logs/$Name.out.log."
}
