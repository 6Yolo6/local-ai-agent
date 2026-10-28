. "$PSScriptRoot/local-common.ps1"
$failed = $false
foreach ($check in @(
    @{ name='ollama'; url="$OllamaUrl/api/version" },
    @{ name='backend'; url='http://127.0.0.1:5000/api/health' },
    @{ name='router'; url='http://127.0.0.1:5002/health' },
    @{ name='frontend'; url='http://127.0.0.1:3000' }
)) {
    $ok = Test-LocalHttp $check.url
    Write-Host "$($check.name): $ok - $($check.url)"
    if (-not $ok) { $failed = $true }
}
if (Test-LocalHttp "$OllamaUrl/api/tags") {
    $tags = Invoke-RestMethod -Uri "$OllamaUrl/api/tags"
    $hasModel = $LocalConfig.model -in @($tags.models.name)
    Write-Host "Model $($LocalConfig.model) installed: $hasModel"
    if (-not $hasModel) { $failed = $true }
}
if ($failed) { exit 1 }
