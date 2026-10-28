$ErrorActionPreference = 'Stop'
$source = Join-Path (Split-Path -Parent $PSScriptRoot) 'local-common.ps1'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Source syntax error.' }
$definition=$ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'New-LocalControlDirectory'},$false)
Invoke-Expression $definition.Extent.Text
$fixture=Join-Path ([IO.Path]::GetTempPath()) ('control-acl-'+[guid]::NewGuid().ToString('N'))
try {
    New-LocalControlDirectory $fixture
    $allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
    $acl=Get-Acl -LiteralPath $fixture
    if (-not $acl.AreAccessRulesProtected) { throw 'Directory inheritance was not disabled.' }
    foreach ($entry in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
        if ($entry.IdentityReference.Value -notin $allowed -or $entry.AccessControlType -ne 'Allow') { throw 'Unexpected directory principal.' }
    }
    $child=Join-Path $fixture 'child.stderr.log'
    [IO.File]::WriteAllText($child,'fixture')
    $rules=@((Get-Acl -LiteralPath $child).GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 3) { throw 'Child did not inherit all three allowed identities.' }
    foreach ($entry in $rules) {
        if ($entry.IdentityReference.Value -notin $allowed -or -not $entry.IsInherited) { throw 'Child log permissions are not private.' }
    }
    $rejected=$false
    try { New-LocalControlDirectory $fixture } catch { $rejected=$true }
    if (-not $rejected -or [IO.File]::ReadAllText($child) -ne 'fixture') { throw 'Existing evidence was overwritten.' }
    '3 private lifecycle directory and log inheritance cases passed.'
} finally {
    $resolved=[IO.Path]::GetFullPath($fixture)
    if ((Split-Path $resolved -Parent) -ne [IO.Path]::GetTempPath().TrimEnd('\') -or (Split-Path $resolved -Leaf) -notlike 'control-acl-*') { throw 'Unsafe fixture path.' }
    if (Test-Path -LiteralPath $resolved) { [IO.Directory]::Delete($resolved,$true) }
}
