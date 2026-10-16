param(
    [string]$OpenClawRoot = $env:OPENCLAW_ROOT,
    [string]$OpenClawHome = $env:USERPROFILE,
    [int]$GatewayPort = 18789,
    [string]$LlamaModelsUrl = "http://127.0.0.1:8080/v1/models"
)

$ErrorActionPreference = "Continue"

function Mask-Secret {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    if ($Value.Length -le 12) { return "***" }
    return "$($Value.Substring(0, 8))***REDACTED***$($Value.Substring($Value.Length - 4))"
}

Write-Host "== llama.cpp =="
try {
    $models = Invoke-RestMethod -Uri $LlamaModelsUrl -Method Get -TimeoutSec 5
    $ids = @()
    if ($models.data) { $ids += $models.data | ForEach-Object { $_.id } }
    if ($models.models) { $ids += $models.models | ForEach-Object { $_.model } }
    $ids = $ids | Where-Object { $_ } | Select-Object -Unique
    Write-Host "OK: $LlamaModelsUrl"
    Write-Host "Models: $($ids -join ', ')"
} catch {
    Write-Host "FAIL: $($_.Exception.Message)"
}

Write-Host ""
Write-Host "== OpenClaw gateway =="
$listeners = Get-NetTCPConnection -LocalPort $GatewayPort -ErrorAction SilentlyContinue |
    Where-Object { $_.State -eq "Listen" }
if ($listeners) {
    $listeners | Select-Object LocalAddress, LocalPort, State, OwningProcess | Format-Table -AutoSize
} else {
    Write-Host "FAIL: no listener on port $GatewayPort"
}

Write-Host ""
Write-Host "== OpenClaw config =="
$configPath = Join-Path $OpenClawHome ".openclaw\openclaw.json"
if (Test-Path -LiteralPath $configPath) {
    try {
        $cfg = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
        Write-Host "Config: $configPath"
        Write-Host "Gateway mode: $($cfg.gateway.mode)"
        Write-Host "Primary model: $($cfg.agents.defaults.model.primary)"
        Write-Host "QQ enabled: $($cfg.channels.qqbot.enabled)"
        Write-Host "QQ appId: $($cfg.channels.qqbot.appId)"
        Write-Host "QQ clientSecret: $(Mask-Secret $cfg.channels.qqbot.clientSecret)"
        Write-Host "Plugins: $((@($cfg.plugins.entries.PSObject.Properties.Name)) -join ', ')"
    } catch {
        Write-Host "FAIL: config parse error: $($_.Exception.Message)"
    }
} else {
    Write-Host "FAIL: config not found: $configPath"
}

Write-Host ""
Write-Host "== QQBot plugin =="
$pluginPkg = Join-Path $OpenClawHome ".openclaw\extensions\openclaw-qqbot\package.json"
if (Test-Path -LiteralPath $pluginPkg) {
    try {
        $pkg = Get-Content -LiteralPath $pluginPkg -Raw | ConvertFrom-Json
        Write-Host "Plugin: $($pkg.name)@$($pkg.version)"
        if ($pkg.version -ne "1.6.5") {
            Write-Host "WARN: OpenClaw 2026.2.23-cn has been verified with qqbot plugin 1.6.5."
        }
    } catch {
        Write-Host "FAIL: plugin package parse error: $($_.Exception.Message)"
    }
} else {
    Write-Host "FAIL: plugin not found: $pluginPkg"
}

Write-Host ""
Write-Host "== Recent gateway log =="
$logDir = Join-Path $OpenClawRoot ".tmp\openclaw"
$latestLog = Get-ChildItem -Path $logDir -Filter "*.log" -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($latestLog) {
    Write-Host "Log: $($latestLog.FullName)"
    $allHits = Select-String -Path $latestLog.FullName -Pattern "Gateway ready|invalid appid or secret|Connection failed|listening on|agent model|READY|Access token obtained|appId=" -ErrorAction SilentlyContinue
    $lastStartup = $allHits | Where-Object { $_.Line -match "listening on" } | Select-Object -Last 1
    if ($lastStartup) {
        $hits = $allHits | Where-Object { $_.LineNumber -ge $lastStartup.LineNumber } | Select-Object -Last 30
    } else {
        $hits = $allHits | Select-Object -Last 30
    }
    if ($hits) {
        $hits | ForEach-Object {
            $message = $_.Line
            try {
                $entry = $_.Line | ConvertFrom-Json
                if ($entry.'1') {
                    $message = $entry.'1'
                } elseif ($entry.'0') {
                    $message = $entry.'0'
                }
                if ($message -isnot [string]) {
                    $message = $message | ConvertTo-Json -Compress -Depth 5
                }
            } catch {
                $message = $_.Line
            }
            Write-Host "$($_.LineNumber): $message"
        }
    } else {
        Get-Content -LiteralPath $latestLog.FullName -Tail 40
    }
} else {
    Write-Host "No gateway log found in $logDir"
}
