# WMI creates a startup host outside the caller's nested Jobs; secrets remain in private files.
if (-not ('LocalAiIndependentProcess' -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class LocalAiIndependentProcess {
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool QueryInformationJobObject(IntPtr job, int kind, IntPtr data, uint size, out uint returned);
    public static bool InJob() {
        bool result;
        if (!IsProcessInJob(GetCurrentProcess(), IntPtr.Zero, out result))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        return result;
    }
    public static uint JobFlags() {
        if (!InJob()) return 0;
        uint size=IntPtr.Size==8 ? 144u : 112u;
        IntPtr data=Marshal.AllocHGlobal((int)size);
        try {
            uint returned;
            if (!QueryInformationJobObject(IntPtr.Zero,9,data,size,out returned))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            return (uint)Marshal.ReadInt32(data,16);
        } finally { Marshal.FreeHGlobal(data); }
    }
}
"@
}

function Invoke-IndependentLocalStartup {
    param(
        [Parameter(Mandatory=$true)]
        [ValidateSet('ollama','backend','router','frontend')][string[]]$Services,
        [switch]$Build,
        [switch]$PullModel,
        [ValidateRange(10,1800)][int]$WaitSeconds = 300
    )
    if (-not $Services -or @($Services | Select-Object -Unique).Count -ne $Services.Count) {
        throw 'Startup requires a nonempty, unique service list.'
    }
    $startScript = Join-Path $PSScriptRoot 'start-local.ps1'
    if (-not (Test-Path -LiteralPath $startScript -PathType Leaf)) { throw 'Managed startup script is missing.' }
    $requestDirectory = Join-Path (Join-Path $ProjectRoot '.local/startup') ([guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $requestDirectory -Force | Out-Null
    $resultPath = Join-Path $requestDirectory 'result.json'
    $outputPath = Join-Path $requestDirectory 'output.log'
    $environmentPath = Join-Path $requestDirectory 'environment.json'
    # Forward only reviewed startup settings. Never serialize inline keys/tokens/cookies/passwords.
    $environmentNames = @(
        'PATH','JAVA_HOME','npm_config_cache','OLLAMA_HOST','OLLAMA_MODELS','OLLAMA_CONTEXT_LENGTH',
        'OLLAMA_NUM_PARALLEL','OLLAMA_MAX_LOADED_MODELS','OLLAMA_KEEP_ALIVE',
        'LOCAL_AI_WORKSPACE','LOCAL_AI_MAVEN_EXECUTABLE','LOCAL_AI_NODE_EXECUTABLE','LOCAL_AI_NPM_EXECUTABLE',
        'LOCAL_AI_MODEL','LOCAL_AI_OLLAMA_EXECUTABLE','LOCAL_AI_OLLAMA_URL',
        'LOCAL_AI_GYING_RESEARCH_ENABLED','LOCAL_AI_GYING_RESEARCH_MODEL','LOCAL_AI_GYING_SEMANTIC_INDEX',
        'LOCAL_AI_EMBEDDING_MODEL','LOCAL_AI_GYING_BRIDGE_BASE_URL','LOCAL_AI_GYING_BRIDGE_TOKEN_FILE',
        'LOCAL_AI_GYING_STATE_FILE','LOCAL_AI_GYING_STATE_KEY_FILE'
    )
    $environment = [ordered]@{}
    foreach ($name in $environmentNames) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ($null -ne $value) { $environment[$name] = $value }
    }
    if ($environment.Contains('LOCAL_AI_GYING_BRIDGE_BASE_URL')) {
        $origin = [uri]$environment['LOCAL_AI_GYING_BRIDGE_BASE_URL']
        if ($origin.UserInfo) { throw 'A bridge URL with embedded credentials cannot be forwarded.' }
    }
    [IO.File]::WriteAllText($environmentPath,($environment|ConvertTo-Json -Depth 3),[Text.UTF8Encoding]::new($false))
    function Quote-StartupLiteral([string]$Value) { "'" + $Value.Replace("'", "''") + "'" }
    $serviceArguments = ($Services | ForEach-Object { Quote-StartupLiteral $_ }) -join ','
    $buildArgument = if ($Build) { '$true' } else { '$false' }
    $pullArgument = if ($PullModel) { '$true' } else { '$false' }
    $lines = @(
        '$ErrorActionPreference = ''Stop''',
        ('$resultPath = ' + (Quote-StartupLiteral $resultPath)),
        ('$outputPath = ' + (Quote-StartupLiteral $outputPath)),
        ('$environmentPath = ' + (Quote-StartupLiteral $environmentPath)),
        'try {',
        ('  Set-Location -LiteralPath ' + (Quote-StartupLiteral $ProjectRoot)),
        '  $startupEnvironment = Get-Content -Raw -LiteralPath $environmentPath | ConvertFrom-Json',
        '  foreach ($entry in $startupEnvironment.PSObject.Properties) { [Environment]::SetEnvironmentVariable($entry.Name,[string]$entry.Value,''Process'') }',
        '  Remove-Item -LiteralPath $environmentPath',
        ('  & ' + (Quote-StartupLiteral $startScript) + ' -Services @(' + $serviceArguments + ') -Build:' + $buildArgument + ' -PullModel:' + $pullArgument + ' -ServiceHost *> $outputPath'),
        '  $report = [ordered]@{status=''ready'';hostPid=$PID;finishedAt=[DateTimeOffset]::Now.ToString(''o'');hostInJob=[LocalAiIndependentProcess]::InJob()}',
        '} catch {',
        '  $report = [ordered]@{status=''failed'';hostPid=$PID;finishedAt=[DateTimeOffset]::Now.ToString(''o'');errorType=$_.Exception.GetType().Name}',
        '}',
        '$temporary = $resultPath + ''.tmp''',
        '[IO.File]::WriteAllText($temporary,($report|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))',
        '[IO.File]::Move($temporary,$resultPath)',
        'if ($report.status -ne ''ready'') { exit 1 }'
    )
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes(($lines -join [Environment]::NewLine)))
    $executable = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $commandLine = '"' + $executable + '" -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ' + $encoded
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ShowWindow=[uint16]0}
    $created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine=$commandLine; CurrentDirectory=$ProjectRoot; ProcessStartupInformation=$startup
    }
    if ($created.ReturnValue -ne 0 -or -not $created.ProcessId) {
        Remove-Item -LiteralPath $environmentPath -ErrorAction SilentlyContinue
        throw "Windows service-host creation failed (code $($created.ReturnValue)); no service was started."
    }
    $hostPid = $created.ProcessId
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $resultPath) {
            $report = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
            if ($report.hostPid -ne $hostPid -or $report.status -ne 'ready' -or $report.hostInJob -ne $false) {
                throw "Independent startup failed; inspect $outputPath. No service was force-stopped."
            }
            Write-Host "Independent service startup confirmed (host PID $hostPid)."
            return [pscustomobject]@{status='ready';hostPid=$hostPid;requestDirectory=$requestDirectory;services=$Services;hostInJob=$false}
        }
        if (-not (Get-Process -Id $hostPid -ErrorAction SilentlyContinue)) {
            throw "Startup host exited without a result; inspect $outputPath. No service was force-stopped."
        }
        Start-Sleep -Milliseconds 250
    }
    throw "Startup confirmation timed out; inspect $outputPath. The independent process was retained."
}
