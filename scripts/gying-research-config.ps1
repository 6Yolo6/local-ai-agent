function Set-GyingResearchEnvironment($Config) {
    $enabled = $Config.PSObject.Properties['gyingResearchEnabled']
    if ($enabled) {
        if ($enabled.Value -isnot [bool]) { throw 'gyingResearchEnabled must be a JSON boolean.' }
        $env:LOCAL_AI_GYING_RESEARCH_ENABLED = $enabled.Value.ToString().ToLowerInvariant()
    }
    $model = $Config.PSObject.Properties['gyingResearchModel']
    if ($model) {
        if ($model.Value -isnot [string] -or $model.Value.Length -gt 160 -or $model.Value -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.:/-]*$') {
            throw 'Invalid local GYING research model name.'
        }
        $env:LOCAL_AI_GYING_RESEARCH_MODEL = $model.Value
    }
    $index = $Config.PSObject.Properties['gyingSemanticIndex']
    if ($index) {
        if ($index.Value -isnot [string] -or $index.Value.Length -gt 1000 -or $index.Value -match '[\x00-\x1f]') {
            throw 'Invalid local semantic index path.'
        }
        if ($index.Value -and ($index.Value -notmatch '^[a-zA-Z]:[\\/]' -or $index.Value.Substring(2).Contains(':'))) {
            throw 'Semantic index must be an absolute local Windows file path.'
        }
        $env:LOCAL_AI_GYING_SEMANTIC_INDEX = $index.Value
    }
}
