param([ValidateSet('all', 'ollama', 'backend', 'frontend', 'router')][string]$Service = 'all')
. "$PSScriptRoot/local-common.ps1"
$names = if ($Service -eq 'all') { @('frontend', 'router', 'backend', 'ollama') } else { @($Service) }
foreach ($name in $names) {
    $record = Get-ServiceRecord $name
    if (-not $record) { Write-Host "$name has no matching managed process."; continue }
    Stop-Process -Id $record.processId -ErrorAction Stop
    Remove-Item -LiteralPath (Join-Path $LocalDir "$name.pid.json")
    Write-Host "$name stopped."
}
