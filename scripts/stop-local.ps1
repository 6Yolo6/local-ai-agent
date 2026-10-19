param(
    [ValidateSet('all', 'ollama', 'backend', 'frontend', 'router')][string]$Service = 'all',
    [ValidateSet('ollama', 'backend', 'frontend', 'router')][string[]]$Services,
    [switch]$Graceful,
    [switch]$CheckOnly,
    [switch]$ForReboot,
    [ValidateRange(10, 600)][int]$TimeoutSeconds = 220
)
. "$PSScriptRoot/local-common.ps1"
$names = if ($Services) { @('frontend', 'router', 'backend', 'ollama') | Where-Object { $_ -in $Services } } elseif ($Service -eq 'all') { @('frontend', 'router', 'backend', 'ollama') } else { @($Service) }
$records = @{}
# Validate every requested service before stopping any of them.
foreach ($name in $names) {
    $record = Get-ServiceRecord $name
    if (-not $record) {
        $listeners = @(Get-NetTCPConnection -State Listen -LocalPort (Get-ServicePort $name) -ErrorAction SilentlyContinue)
        if ($listeners.Count -gt 0) { throw "$name port is held by an untracked process. Reboot must be cancelled." }
        Write-Host "$name is not running."
        continue
    }
    Assert-LifecycleReady $name $record -ForReboot:$ForReboot
    $records[$name] = $record
    Write-Host "$name lifecycle ready (instance $($record.instanceId))."
}
if ($CheckOnly) {
    Write-Host 'Graceful shutdown preflight passed; no stop requests were sent.'
    $instances = @($names | Where-Object { $records.ContainsKey($_) } | ForEach-Object {
        $record = $records[$_]
        @{ name=$_; instanceId=$record.instanceId; processId=$record.processId; startedAt=$record.startedAt;
           executable=$record.executable; controlDirectory=$record.controlDirectory }
    })
    @{ checkOnly=$true; runningServices=@($names | Where-Object { $records.ContainsKey($_) }); instances=$instances;
       deferredToWindowsShutdown=@($names | Where-Object { $records.ContainsKey($_) -and $records[$_].deferredToWindowsShutdown }) } | ConvertTo-Json -Compress -Depth 4
    return
}
foreach ($name in $names) {
    if (-not $records.ContainsKey($name)) { continue }
    $record = $records[$name]
    $current = Get-ServiceRecord $name
    if (-not $current -or $current.instanceId -ne $record.instanceId) { throw "$name process changed after preflight; reboot must be cancelled." }
    if ($name -eq 'ollama' -and $record.deferredToWindowsShutdown) {
        if (-not $ForReboot) { throw 'Deferring Ollama requires -ForReboot.' }
        foreach ($application in @('frontend', 'router', 'backend')) {
            if ((Get-ServiceRecord $application) -or @(Get-NetTCPConnection -State Listen -LocalPort (Get-ServicePort $application) -ErrorAction SilentlyContinue).Count -gt 0) {
                throw "Cannot defer Ollama while $application is running."
            }
        }
        $connections = @(Get-NetTCPConnection -LocalPort 11434 -State Established -ErrorAction SilentlyContinue)
        if ($connections.Count -gt 0) { throw 'Ollama still has connected clients; no idle-state proof is available. Reboot must be cancelled.' }
        $loaded = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/ps' -TimeoutSec 10 -DisableKeepAlive
        foreach ($model in $loaded.models) {
            $body = @{ model=$model.name; keep_alive=0; stream=$false } | ConvertTo-Json
            $unloaded = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/generate' -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 180 -DisableKeepAlive
            if (-not $unloaded.done) { throw 'Ollama did not acknowledge model unload. Reboot must be cancelled.' }
        }
        $remaining = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/ps' -TimeoutSec 10 -DisableKeepAlive
        if (@($remaining.models).Count -gt 0) { throw 'Ollama models remain loaded. Reboot must be cancelled.' }
        Start-Sleep -Milliseconds 300
        if (@(Get-NetTCPConnection -LocalPort 11434 -State Established -ErrorAction SilentlyContinue).Count -gt 0) { throw 'Ollama gained a connected client after unload. Reboot must be cancelled.' }
        $proof = @{
            instanceId=$record.instanceId; processId=$record.processId; startedAt=$record.startedAt;
            status='deferredToWindowsShutdown'; processStillRunning=$true; models=@(); checkedAt=[datetime]::UtcNow.ToString('o')
        }
        Write-LocalJson (Join-Path $record.controlDirectory 'os-shutdown-ready.json') $proof
        Write-Host 'Ollama models unloaded; this managed process is still running and is deferred to normal Windows shutdown.'
        continue
    }
    $request = @{
        instanceId = $record.instanceId; processId = $record.processId;
        startedAt = $record.startedAt; requestedAt = [datetime]::UtcNow.ToString('o')
    }
    Write-LocalJson (Join-Path $record.controlDirectory 'stop.request.json') $request
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $completedPath = Join-Path $record.controlDirectory 'stop.completed.json'
    while ((Get-Date) -lt $deadline) {
        $failedPath = Join-Path $record.controlDirectory 'stop.failed.json'
        if (Test-Path -LiteralPath $failedPath) {
            $failure = Read-LocalJson $failedPath
            throw "$name could not stop gracefully: $($failure.error). Reboot must be cancelled."
        }
        $running = Get-ServiceRecord $name
        if (-not $running) {
            if (-not (Test-Path -LiteralPath $completedPath)) { throw "$name exited without a completed lifecycle acknowledgement. Reboot must be cancelled." }
            $completed = Read-LocalJson $completedPath
            if ($completed.instanceId -ne $record.instanceId -or $completed.processId -ne $record.processId -or $completed.startedAt -cne $record.startedAt -or $completed.status -ne 'stopped') {
                throw "$name shutdown acknowledgement does not match this instance."
            }
            $remainingListeners = @(Get-NetTCPConnection -State Listen -LocalPort (Get-ServicePort $name) -ErrorAction SilentlyContinue)
            if ($remainingListeners.Count -gt 0) { throw "$name still has a listening process. Reboot must be cancelled." }
            Remove-Item -LiteralPath (Join-Path $LocalDir "$name.pid.json")
            Write-Host "$name stopped gracefully."
            break
        }
        Start-Sleep -Milliseconds 250
    }
    if (Get-ServiceRecord $name) { throw "$name graceful shutdown timed out; no process was force-stopped. Reboot must be cancelled." }
}
