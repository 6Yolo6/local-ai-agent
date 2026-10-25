. (Join-Path $PSScriptRoot '../gying-research-config.ps1')
$saved = @{}
$keys = @('LOCAL_AI_GYING_RESEARCH_ENABLED','LOCAL_AI_GYING_RESEARCH_MODEL','LOCAL_AI_GYING_SEMANTIC_INDEX')
foreach ($key in $keys) { $saved[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$count = 0
function Reject($Config) {
    $failed = $false
    try { Set-GyingResearchEnvironment $Config } catch { $failed = $true }
    if (-not $failed) { throw 'Invalid research configuration accepted.' }
    $script:count++
}
try {
    $env:LOCAL_AI_GYING_RESEARCH_ENABLED = 'inherited'
    Set-GyingResearchEnvironment ([pscustomobject]@{model='qwen3:1.7b'})
    if ($env:LOCAL_AI_GYING_RESEARCH_ENABLED -ne 'inherited') { throw 'Legacy configuration changed.' }; $count++
    Set-GyingResearchEnvironment ([pscustomobject]@{gyingResearchEnabled=$true;gyingResearchModel='qwen3:4b-instruct-2507-q4_K_M';gyingSemanticIndex='G:/fixture/catalog.bin'})
    if ($env:LOCAL_AI_GYING_RESEARCH_ENABLED -ne 'true' -or $env:LOCAL_AI_GYING_RESEARCH_MODEL -ne 'qwen3:4b-instruct-2507-q4_K_M' -or $env:LOCAL_AI_GYING_SEMANTIC_INDEX -ne 'G:/fixture/catalog.bin') { throw 'Persistent settings not restored.' }; $count++
    Set-GyingResearchEnvironment ([pscustomobject]@{gyingResearchEnabled=$false;gyingSemanticIndex=''})
    if ($env:LOCAL_AI_GYING_RESEARCH_ENABLED -ne 'false' -or $env:LOCAL_AI_GYING_SEMANTIC_INDEX) { throw 'Rollback settings not restored.' }; $count++
    Reject ([pscustomobject]@{gyingResearchEnabled='false'})
    Reject ([pscustomobject]@{gyingResearchModel="model`nother"})
    Reject ([pscustomobject]@{gyingSemanticIndex='https://example.com/catalog.bin'})
    Reject ([pscustomobject]@{gyingSemanticIndex='\\server\catalog.bin'})
    Reject ([pscustomobject]@{gyingSemanticIndex='catalog.bin'})
    Reject ([pscustomobject]@{gyingSemanticIndex='G:/catalog.bin:stream'})
    "$count research configuration cases passed."
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $saved[$key], 'Process') }
}
