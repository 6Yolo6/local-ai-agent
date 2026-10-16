param(
    [string]$RuntimeRoot = (Join-Path $env:LOCALAPPDATA 'local-ai-agent'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$NodeExe = '',
    [string]$MavenHome = '',
    [string]$OllamaExe = '',
    [string]$PnpmExe = '',
    [string]$Model = 'qwen3:1.7b',
    [switch]$InstallTools
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$localDir = Join-Path $ProjectRoot '.local'
New-Item -ItemType Directory -Force -Path $localDir | Out-Null
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome to an installed JDK 17+ (including javac).'
}
if (-not $NodeExe) { $NodeExe = (Get-Command node.exe -ErrorAction Stop).Source }
if (-not (Test-Path -LiteralPath $NodeExe)) { throw "Node executable missing: $NodeExe" }
$RuntimeRoot = [IO.Path]::GetFullPath($RuntimeRoot)
$runtime = Join-Path $RuntimeRoot 'runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
if ($InstallTools) {
    $pnpmRoot = Join-Path $runtime 'pnpm'
    $PnpmExe = Join-Path $pnpmRoot 'node_modules/.bin/pnpm.cmd'
    if (-not (Test-Path -LiteralPath $PnpmExe)) {
        $npm = Join-Path (Split-Path -Parent $NodeExe) 'npm.cmd'
        & $npm install --prefix $pnpmRoot pnpm@10.28.2 --cache (Join-Path $RuntimeRoot 'cache/npm') --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'pnpm installation failed.' }
    }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $MavenHome = Join-Path $runtime 'apache-maven-3.9.9'
    if (-not (Test-Path -LiteralPath (Join-Path $MavenHome 'bin/mvn.cmd'))) {
        $url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
        $archive = Join-Path $runtime 'apache-maven-3.9.9-bin.zip'
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archive
        $expected = ((Invoke-WebRequest -UseBasicParsing -Uri ($url + '.sha512')).Content.Trim() -split '\s+')[0]
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ine $expected) { throw 'Maven checksum mismatch.' }
        Expand-Archive -LiteralPath $archive -DestinationPath $runtime -Force
    }
    $ollamaDir = Join-Path $runtime 'ollama-0.11.11'
    $OllamaExe = Join-Path $ollamaDir 'ollama.exe'
    if (-not (Test-Path -LiteralPath $OllamaExe)) {
        # Pinned CUDA 12-capable build for the GTX 1650 Ti's installed driver.
        $url = 'https://github.com/ollama/ollama/releases/download/v0.11.11/ollama-windows-amd64.zip'
        $archive = Join-Path $runtime 'ollama-windows-amd64.zip'
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archive
        $expected = 'b309cc68d1263837f062195c97c7137f54de2816ae14a756b0c8ca1f7cacf907'
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $expected) { throw 'Ollama checksum mismatch.' }
        Expand-Archive -LiteralPath $archive -DestinationPath $ollamaDir -Force
    }
}
if (-not $MavenHome) {
    $maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($maven) { $MavenHome = Split-Path -Parent (Split-Path -Parent $maven.Source) }
}
if (-not $OllamaExe) {
    $ollama = Get-Command ollama.exe -ErrorAction SilentlyContinue
    if ($ollama) { $OllamaExe = $ollama.Source }
}
if (-not $MavenHome -or -not (Test-Path -LiteralPath (Join-Path $MavenHome 'bin/mvn.cmd'))) {
    throw 'Pass -MavenHome or use -InstallTools.'
}
if (-not $OllamaExe -or -not (Test-Path -LiteralPath $OllamaExe)) { throw 'Pass -OllamaExe or use -InstallTools.' }
if (-not $PnpmExe) { $PnpmExe = (Get-Command pnpm.cmd -ErrorAction Stop).Source }
$versionOutput = & $OllamaExe --version 2>&1 | Out-String
$match = [regex]::Match($versionOutput, '\d+\.\d+\.\d+')
if (-not $match.Success -or [version]$match.Value -lt [version]'0.9.0') { throw 'Ollama 0.9+ is required for Qwen3 think=false; use -InstallTools.' }
$configPath = Join-Path $localDir 'environment.json'
if (Test-Path -LiteralPath $configPath) {
    Copy-Item -LiteralPath $configPath -Destination (Join-Path $localDir ("environment.backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json'))
}
@{
    javaHome = $JavaHome; mavenHome = $MavenHome; nodeExe = $NodeExe; pnpmExe = $PnpmExe
    ollamaExe = $OllamaExe; modelsDir = (Join-Path $RuntimeRoot 'models')
    cacheDir = (Join-Path $RuntimeRoot 'cache'); model = $Model
} | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
Write-Host "Machine configuration saved to $configPath"
Write-Host 'Next: scripts/start-all.bat -Build -PullModel'
