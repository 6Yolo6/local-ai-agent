function Get-LocalOllamaPort($Config) {
    if (-not $Config.PSObject.Properties['ollamaPort']) { return 11434 }
    $port = $Config.ollamaPort
    if (($port -isnot [int] -and $port -isnot [long]) -or
        $port -lt 1024 -or $port -gt 65535 -or $port -in @(3000, 5000, 5002)) {
        throw 'ollamaPort must be an integer from 1024 through 65535, distinct from the application ports.'
    }
    return [int]$port
}
