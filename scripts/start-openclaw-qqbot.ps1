param(
    [string]$OpenClawRoot = "D:\OpenClaw\openclaw-cn",
    [string]$OpenClawHome = "C:\Users\20317.YOLO",
    [string]$NodeExe = "D:\Dev\NodeJs-24\node.exe",
    [int]$GatewayPort = 18789
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $OpenClawRoot)) {
    throw "OpenClaw root not found: $OpenClawRoot"
}
if (-not (Test-Path -LiteralPath $NodeExe)) {
    throw "Node executable not found: $NodeExe"
}

$listener = Get-NetTCPConnection -LocalPort $GatewayPort -ErrorAction SilentlyContinue |
    Where-Object { $_.State -eq "Listen" } |
    Select-Object -First 1

if ($listener) {
    Write-Host "OpenClaw gateway is already listening on port $GatewayPort (PID $($listener.OwningProcess))."
    exit 0
}

$tmpDir = Join-Path $OpenClawRoot ".tmp"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

$openclawTmpDir = Join-Path $tmpDir "openclaw"
if (Test-Path -LiteralPath $openclawTmpDir) {
    Get-ChildItem -Path $openclawTmpDir -Filter "gateway.*.lock" -File -ErrorAction SilentlyContinue | ForEach-Object {
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

$stdoutLog = Join-Path $tmpDir "gateway-run.out.log"
$stderrLog = Join-Path $tmpDir "gateway-run.err.log"
$command = @"
`$env:OPENCLAW_HOME='$OpenClawHome'
`$env:TEMP='$tmpDir'
`$env:TMP='$tmpDir'
Set-Location '$OpenClawRoot'
& '$NodeExe' openclaw.mjs gateway run --compact
"@

$process = Start-Process -FilePath "powershell.exe" `
    -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $command `
    -WorkingDirectory $OpenClawRoot `
    -WindowStyle Hidden `
    -PassThru

Start-Sleep -Seconds 8

$listener = Get-NetTCPConnection -LocalPort $GatewayPort -ErrorAction SilentlyContinue |
    Where-Object { $_.State -eq "Listen" } |
    Select-Object -First 1

if ($listener) {
    Write-Host "OpenClaw gateway started. Launcher PID $($process.Id), listener PID $($listener.OwningProcess), port $GatewayPort."
    Write-Host "Logs:"
    Write-Host "  $stdoutLog"
    Write-Host "  $stderrLog"
    exit 0
}

Write-Warning "OpenClaw gateway did not open port $GatewayPort yet. Check logs:"
Write-Host "  $stdoutLog"
Write-Host "  $stderrLog"
exit 1
