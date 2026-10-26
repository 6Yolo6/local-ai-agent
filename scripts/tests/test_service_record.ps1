$ErrorActionPreference='Stop'
$source=Join-Path (Split-Path -Parent $PSScriptRoot) 'local-common.ps1'
$tokens=$null;$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Source syntax error.' }
$definition=$ast.FindAll({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-ServiceRecord'},$false)
Invoke-Expression $definition.Extent.Text
$LocalDir=Join-Path ([IO.Path]::GetTempPath()) ('service-record-'+[guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($LocalDir) | Out-Null
[IO.File]::WriteAllText((Join-Path $LocalDir 'backend.pid.json'),'{}')
function Read-LocalJson([string]$Path) { return $script:record }
function Get-Process { param($Id,$ErrorAction) return $script:observed }
$started=[datetime]'2026-10-26T05:13:20.5204088Z'
$script:record=[pscustomobject]@{processId=42;startedAt=$started.ToUniversalTime().ToString('o');executable='C:/runtime/java.exe';instanceId='expected-instance'}
$throwing=[pscustomobject]@{Path='C:/runtime/java.exe'}
$throwing | Add-Member ScriptProperty StartTime { throw [InvalidOperationException]::new('Process has exited') }
$cases=@(
    @{name='missing process';process=$null;matches=$false},
    @{name='exit race null start time';process=[pscustomobject]@{StartTime=$null;Path='C:/runtime/java.exe'};matches=$false},
    @{name='unreadable path';process=[pscustomobject]@{StartTime=$started;Path=$null};matches=$false},
    @{name='exit race throwing property';process=$throwing;matches=$false},
    @{name='same PID different start';process=[pscustomobject]@{StartTime=$started.AddSeconds(1);Path='C:/runtime/java.exe'};matches=$false},
    @{name='different executable';process=[pscustomobject]@{StartTime=$started;Path='C:/other/java.exe'};matches=$false},
    @{name='exact live identity';process=[pscustomobject]@{StartTime=$started;Path='C:/runtime/java.exe'};matches=$true}
)
try {
    foreach ($case in $cases) {
        $script:observed=$case.process
        $actual=Get-ServiceRecord backend
        if ([bool]$actual -ne $case.matches) { throw ('Identity gate mismatch: '+$case.name) }
        if ($actual -and $actual.instanceId -ne 'expected-instance') { throw 'Returned wrong instance.' }
    }
    '7 process identity and exit-race cases passed.'
} finally {
    [IO.File]::Delete((Join-Path $LocalDir 'backend.pid.json'))
    [IO.Directory]::Delete($LocalDir)
}
