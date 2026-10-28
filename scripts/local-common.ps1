$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$LocalDir = Join-Path $ProjectRoot '.local'
$ConfigPath = Join-Path $LocalDir 'environment.json'
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    throw 'Run scripts/setup-local.ps1 first to detect this machine and save .local/environment.json.'
}
$LocalConfig = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'gying-research-config.ps1')
Set-GyingResearchEnvironment $LocalConfig
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
# Optional private file enables only the outbound GYING catalog worker.
$bridgeTokenPath = Join-Path $LocalDir 'gying-bridge-token.txt'
if (Test-Path -LiteralPath $bridgeTokenPath) {
    $env:LOCAL_AI_GYING_BRIDGE_TOKEN_FILE = $bridgeTokenPath
}
# Persistence is explicitly enabled with a separate private key file prepared by the operator.
$bridgeStateKey = Join-Path $LocalDir 'gying-state-key.bin'
if (Test-Path -LiteralPath $bridgeStateKey) {
    $env:LOCAL_AI_GYING_STATE_FILE = Join-Path $LocalDir 'ai-state/bridge.bin'
    $env:LOCAL_AI_GYING_STATE_KEY_FILE = $bridgeStateKey
}
$env:LOCAL_AI_OLLAMA_EXECUTABLE = $OllamaExe
. (Join-Path $PSScriptRoot 'ollama-config.ps1')
$OllamaPort = Get-LocalOllamaPort $LocalConfig
$OllamaUrl = "http://127.0.0.1:$OllamaPort"
$env:OLLAMA_HOST = "127.0.0.1:$OllamaPort"
$env:LOCAL_AI_OLLAMA_URL = $OllamaUrl
$env:OLLAMA_MODELS = $LocalConfig.modelsDir
$env:OLLAMA_CONTEXT_LENGTH = '4096'
$env:OLLAMA_NUM_PARALLEL = '1'
$env:OLLAMA_MAX_LOADED_MODELS = '1'
$env:OLLAMA_KEEP_ALIVE = '5m'
$env:npm_config_cache = Join-Path $LocalConfig.cacheDir 'npm'
$MavenRepository = Join-Path $LocalConfig.cacheDir 'maven'
function Initialize-LocalDirectories {
    foreach ($directory in @($LocalDir, (Join-Path $ProjectRoot 'logs'), $env:OLLAMA_MODELS, $env:npm_config_cache, $MavenRepository)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
}
function Test-LocalHttp([string]$Url) {
    try { $null = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3; return $true }
    catch { return $false }
}
function Assert-FreePort([int]$Port) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) { throw "Port $Port is already in use by PID $($listener[0].OwningProcess). No process was stopped." }
}
function Read-LocalJson([string]$Path) {
    $parameters = @{ InputObject = [IO.File]::ReadAllText($Path) }
    if ((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')) { $parameters['DateKind'] = 'String' }
    return ConvertFrom-Json @parameters
}
function Get-ServiceRecord([string]$Name) {
    $recordPath = Join-Path $LocalDir "$Name.pid.json"
    if (-not (Test-Path -LiteralPath $recordPath)) { return $null }
    $record = Read-LocalJson $recordPath
    $process = Get-Process -Id $record.processId -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    # The process can exit between Get-Process and its property reads.
    # Missing identity proof still requires the caller's receipt/port checks.
    try {
        $startedAt = $process.StartTime
        $executable = $process.Path
    } catch { return $null }
    if ($startedAt -and $executable -and
        $startedAt.ToUniversalTime().Ticks -eq ([datetime]$record.startedAt).ToUniversalTime().Ticks -and
        $executable -eq $record.executable) { return $record }
    return $null
}
function New-LocalControlDirectory([string]$Path) {
    if (Test-Path -LiteralPath $Path) { throw 'A new lifecycle directory is required.' }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $security = New-Object Security.AccessControl.DirectorySecurity
    $security.SetAccessRuleProtection($true, $false)
    $security.SetOwner($identity)
    foreach ($sid in @($identity.Value, 'S-1-5-18', 'S-1-5-32-544')) {
        $principal = New-Object Security.Principal.SecurityIdentifier($sid)
        $rule = New-Object Security.AccessControl.FileSystemAccessRule($principal, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $security.AddAccessRule($rule)
    }
    $directory = New-Object IO.DirectoryInfo($Path)
    if ($PSVersionTable.PSEdition -eq 'Core') {
        [IO.FileSystemAclExtensions]::Create($directory, $security)
    } else {
        $directory.Create($security)
    }
}
function Start-LocalProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$Directory) {
    if (Get-ServiceRecord $Name) { Write-Host "$Name already running."; return }
    if (-not (Test-Path -LiteralPath $Executable)) { throw "Executable missing: $Executable" }
    $instanceId = [guid]::NewGuid().ToString()
    $controlDirectory = Join-Path (Join-Path $LocalDir 'lifecycle') $instanceId
    New-LocalControlDirectory $controlDirectory
    $stdout = Join-Path $ProjectRoot "logs/$Name.out.log"
    $stderr = Join-Path $ProjectRoot "logs/$Name.err.log"
    $previousInstance = $env:LOCAL_AI_INSTANCE_ID
    $previousControl = $env:LOCAL_AI_CONTROL_DIR
    try {
        $env:LOCAL_AI_INSTANCE_ID = $instanceId
        $env:LOCAL_AI_CONTROL_DIR = $controlDirectory
        $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $Directory -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
    }
    finally {
        $env:LOCAL_AI_INSTANCE_ID = $previousInstance
        $env:LOCAL_AI_CONTROL_DIR = $previousControl
    }
    Start-Sleep -Milliseconds 300
    $process.Refresh()
    if ($process.HasExited) { throw "$Name exited immediately. See $stderr" }
    $record = @{
        processId = $process.Id; startedAt = $process.StartTime.ToUniversalTime().ToString('o');
        executable = $process.Path; instanceId = $instanceId; controlDirectory = $controlDirectory
    }
    Write-LocalJson (Join-Path $controlDirectory 'identity.json') $record
    Write-LocalJson (Join-Path $LocalDir "$Name.pid.json") $record
    Write-Host "$Name started (PID $($process.Id), instance $instanceId)."
}

function Wait-LocalHttp([string]$Name, [string]$Url, [int]$Seconds = 60) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-LocalHttp $Url) { return }
        Start-Sleep -Seconds 1
    }
    throw "$Name did not become ready: $Url. See logs/$Name.err.log and logs/$Name.out.log."
}

function Get-ServicePort([string]$Name) {
    switch ($Name) { 'ollama' { $OllamaPort }; 'backend' { 5000 }; 'router' { 5002 }; 'frontend' { 3000 }; default { throw "Unknown service: $Name" } }
}
function Write-LocalJson([string]$Path, $Value) {
    $temporary = "$Path.tmp"
    [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}
function Assert-LifecycleReady([string]$Name, $Record, [switch]$ForReboot) {
    if (-not $Record.instanceId -or -not $Record.controlDirectory) {
        throw "$Name was started by a legacy launcher and has no graceful lifecycle handshake. Restart it through the managed launcher before scheduling a reboot."
    }
    $expectedDirectory = Join-Path (Join-Path $LocalDir 'lifecycle') $Record.instanceId
    if ([IO.Path]::GetFullPath($Record.controlDirectory) -ne [IO.Path]::GetFullPath($expectedDirectory)) {
        throw "$Name lifecycle directory is outside its expected instance."
    }
    $readyPath = Join-Path $expectedDirectory 'ready.json'
    if (-not (Test-Path -LiteralPath $readyPath)) { throw "$Name lifecycle is not ready." }
    $ready = Read-LocalJson $readyPath
    if ($ready.instanceId -ne $Record.instanceId -or $ready.processId -ne $Record.processId -or $ready.startedAt -cne $Record.startedAt -or $ready.status -ne 'ready') {
        throw "$Name lifecycle identity does not match its managed process."
    }
    $expectedPid = $Record.processId
    if ($Name -eq 'ollama') {
        $child = Read-LocalJson (Join-Path $expectedDirectory 'child.json')
        $childProcess = Get-Process -Id $child.processId -ErrorAction SilentlyContinue
        if (-not $childProcess -or $childProcess.StartTime.ToUniversalTime().Ticks -ne ([datetime]$child.startedAt).ToUniversalTime().Ticks -or $childProcess.Path -ne $child.executable) {
            throw 'Ollama child identity no longer matches its launcher.'
        }
        $expectedPid = $child.processId
        $previousErrorPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $null = & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'console-interrupt.ps1') -TargetProcessId $child.processId -ExpectedStartedAt $child.startedAt -ExpectedExecutable $child.executable -CheckOnly 2>&1
            $consoleReady = $LASTEXITCODE -eq 0
        }
        finally { $ErrorActionPreference = $previousErrorPreference }
        if (-not $consoleReady) {
            if (-not $ForReboot) { throw 'This managed Ollama instance has no isolated console. Normal stop is refused. For a planned Windows reboot, use -ForReboot to unload it and defer final exit to Windows.' }
            $Record | Add-Member -NotePropertyName deferredToWindowsShutdown -NotePropertyValue $true -Force
            Write-Host 'Ollama exception: no isolated console; after applications drain, unload models and defer this managed process to normal Windows shutdown.'
        }
    }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort (Get-ServicePort $Name) -ErrorAction SilentlyContinue)
    if (-not $listeners -or @($listeners | Where-Object OwningProcess -ne $expectedPid).Count -gt 0) {
        throw "$Name listening port is missing or belongs to a different process."
    }
}
function Wait-LifecycleReady([string]$Name, [int]$Seconds = 30) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $record = Get-ServiceRecord $Name
        if ($record -and (Test-Path -LiteralPath (Join-Path $record.controlDirectory 'ready.json'))) {
            Assert-LifecycleReady $Name $record
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw "$Name did not publish its lifecycle handshake."
}
