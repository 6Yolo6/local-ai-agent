param(
    [string]$OpenClawRoot = $env:OPENCLAW_ROOT,
    [int]$GatewayPort = 18789
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($OpenClawRoot) -or -not (Test-Path -LiteralPath $OpenClawRoot)) {
    throw "Set OPENCLAW_ROOT or pass an existing -OpenClawRoot before stopping this optional integration."
}

$targets = @()

$listeners = Get-NetTCPConnection -LocalPort $GatewayPort -ErrorAction SilentlyContinue |
    Where-Object { $_.State -eq "Listen" }
foreach ($listener in $listeners) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" -ErrorAction SilentlyContinue
    if ($owner -and $owner.CommandLine -match "openclaw\.mjs\s+gateway" -and $owner.CommandLine -like "*$OpenClawRoot*") {
        $targets += [int]$listener.OwningProcess
    }
}

$gatewayProcesses = Get-CimInstance Win32_Process |
    Where-Object {
        $_.CommandLine -and
        $_.CommandLine -match "openclaw\.mjs\s+gateway" -and
        ($_.CommandLine -like "*$OpenClawRoot*")
    }
foreach ($process in $gatewayProcesses) {
    $targets += [int]$process.ProcessId
}

$targets = $targets | Sort-Object -Unique

if (-not $targets) {
    Write-Host "No OpenClaw gateway process found."
    exit 0
}

foreach ($pidToStop in $targets) {
    try {
        $proc = Get-Process -Id $pidToStop -ErrorAction Stop
        Write-Host "Stopping PID $pidToStop ($($proc.ProcessName))"
        Stop-Process -Id $pidToStop -Force
    } catch {
        Write-Host "Skip PID ${pidToStop}: $($_.Exception.Message)"
    }
}

Start-Sleep -Seconds 2
$remaining = Get-NetTCPConnection -LocalPort $GatewayPort -ErrorAction SilentlyContinue |
    Where-Object { $_.State -eq "Listen" }
if ($remaining) {
    Write-Warning "Port $GatewayPort is still listening."
    $remaining | Select-Object LocalAddress, LocalPort, State, OwningProcess | Format-Table -AutoSize
    exit 1
}

$tmpDir = Join-Path $OpenClawRoot ".tmp\openclaw"
if (Test-Path -LiteralPath $tmpDir) {
    Get-ChildItem -Path $tmpDir -Filter "gateway.*.lock" -File -ErrorAction SilentlyContinue | ForEach-Object {
        $removeLock = $false
        try {
            $lock = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
            if ($lock.pid -and -not (Get-Process -Id ([int]$lock.pid) -ErrorAction SilentlyContinue)) {
                $removeLock = $true
            }
        } catch {
            $removeLock = $true
        }
        if ($removeLock) {
            Write-Host "Removing stale gateway lock: $($_.FullName)"
            Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Host "OpenClaw gateway stopped."
