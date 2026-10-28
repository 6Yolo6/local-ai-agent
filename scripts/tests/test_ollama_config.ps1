$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../ollama-config.ps1')
$count = 0
function Expect-Port($Config, [int]$Expected) {
    if ((Get-LocalOllamaPort $Config) -ne $Expected) { throw 'Unexpected Ollama port.' }
    $script:count++
}
function Reject-Port($Port) {
    $failed = $false
    try { $null = Get-LocalOllamaPort ([pscustomobject]@{ollamaPort=$Port}) } catch { $failed = $true }
    if (-not $failed) { throw 'Invalid Ollama port accepted.' }
    $script:count++
}
Expect-Port ([pscustomobject]@{}) 11434
Expect-Port ([pscustomobject]@{ollamaPort=21434}) 21434
Expect-Port ([pscustomobject]@{ollamaPort=[long]65535}) 65535
foreach ($bad in @($null,$true,'21434',21434.5,0,1023,65536,3000,5000,5002)) { Reject-Port $bad }
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('ollama-config-' + [guid]::NewGuid().ToString('N'))
$fixtureScripts = Join-Path $fixtureRoot 'scripts'
$fixtureLocal = Join-Path $fixtureRoot '.local'
[IO.Directory]::CreateDirectory($fixtureScripts) | Out-Null
[IO.Directory]::CreateDirectory($fixtureLocal) | Out-Null
try {
    foreach ($name in @('local-common.ps1','ollama-config.ps1','gying-research-config.ps1')) {
        Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) $name) -Destination (Join-Path $fixtureScripts $name)
    }
    $fixture = @{javaHome=$fixtureRoot;mavenHome=$fixtureRoot;nodeExe=(Join-Path $fixtureRoot 'node.exe');ollamaExe=(Join-Path $fixtureRoot 'ollama.exe');modelsDir=$fixtureRoot;cacheDir=$fixtureRoot;model='fixture';ollamaPort=21434}
    [IO.File]::WriteAllText((Join-Path $fixtureLocal 'environment.json'),($fixture | ConvertTo-Json))
    $probe = @('param([string]$Common)', '. $Common', '@{host=$env:OLLAMA_HOST;url=$env:LOCAL_AI_OLLAMA_URL;port=(Get-ServicePort "ollama");backendPort=(Get-ServicePort "backend")} | ConvertTo-Json -Compress') -join [Environment]::NewLine
    $probePath = Join-Path $fixtureRoot 'probe.ps1'
    [IO.File]::WriteAllText($probePath,$probe)
    $result = & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $probePath -Common (Join-Path $fixtureScripts 'local-common.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Local configuration integration failed.' }
    $settings = $result | ConvertFrom-Json
    if ($settings.host -ne '127.0.0.1:21434' -or $settings.url -ne 'http://127.0.0.1:21434' -or $settings.port -ne 21434 -or $settings.backendPort -ne 5000) {
        throw 'Listener, HTTP client and lifecycle ports are not consistent.'
    }
    $count++
} finally {
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    if ((Split-Path $resolved -Parent) -ne [IO.Path]::GetTempPath().TrimEnd('\') -or (Split-Path $resolved -Leaf) -notlike 'ollama-config-*') { throw 'Unsafe fixture cleanup path.' }
    [IO.Directory]::Delete($resolved,$true)
}
"$count Ollama configuration cases passed."
