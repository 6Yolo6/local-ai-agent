param(
    [Parameter(Mandatory=$true)][int]$TargetProcessId,
    [Parameter(Mandatory=$true)][string]$ExpectedStartedAt,
    [Parameter(Mandatory=$true)][string]$ExpectedExecutable,
    [switch]$CheckOnly,
    [ValidateRange(0, 180)][int]$WaitForExitSeconds = 0
)
$ErrorActionPreference = 'Stop'
$target = Get-Process -Id $TargetProcessId -ErrorAction Stop
if ($target.StartTime.ToUniversalTime().Ticks -ne ([datetime]$ExpectedStartedAt).ToUniversalTime().Ticks -or
    $target.Path -ne $ExpectedExecutable) { throw 'Console interrupt refused: process identity changed.' }
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class LocalConsoleControl {
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool FreeConsole();
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool AttachConsole(uint processId);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool GenerateConsoleCtrlEvent(uint ctrlEvent, uint processGroupId);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern bool SetConsoleCtrlHandler(IntPtr handler, bool add);
    [DllImport("kernel32.dll", SetLastError=true)] public static extern uint GetConsoleProcessList([Out] uint[] processList, uint count);
}
'@
[LocalConsoleControl]::FreeConsole() | Out-Null
if (-not [LocalConsoleControl]::AttachConsole([uint32]$TargetProcessId)) {
    throw "Cannot attach to the managed process console (Win32 $([Runtime.InteropServices.Marshal]::GetLastWin32Error())). No force-stop was attempted."
}
try {
    if (-not [LocalConsoleControl]::SetConsoleCtrlHandler([IntPtr]::Zero, $true)) { throw 'Cannot protect interrupt helper.' }
    $consoleIds = New-Object 'uint32[]' 64
    $count = [LocalConsoleControl]::GetConsoleProcessList($consoleIds, 64)
    if ($count -eq 0 -or $count -gt 64) { throw 'Cannot verify the managed process console.' }
    $allowed = @([int]$PID, $TargetProcessId)
    for ($i = 0; $i -lt $count; $i++) {
        if ([int]$consoleIds[$i] -notin $allowed) {
            $ancestorId = [int]$consoleIds[$i]
            $belongsToTarget = $false
            for ($depth = 0; $depth -lt 20 -and $ancestorId -gt 0; $depth++) {
                $ancestor = Get-CimInstance Win32_Process -Filter "ProcessId=$ancestorId" -ErrorAction Stop
                if (-not $ancestor) { break }
                $ancestorId = [int]$ancestor.ParentProcessId
                if ($ancestorId -eq $TargetProcessId) { $belongsToTarget = $true; break }
            }
            if (-not $belongsToTarget) { throw "Console contains unrelated PID $($consoleIds[$i]); refusing to signal it." }
        }
    }
    if ($CheckOnly) { Write-Host "Verified isolated console for PID $TargetProcessId"; return }
    $target.Refresh()
    if ($target.HasExited) { return }
    if (-not [LocalConsoleControl]::GenerateConsoleCtrlEvent(0, 0)) {
        throw "Console interrupt failed (Win32 $([Runtime.InteropServices.Marshal]::GetLastWin32Error()))."
    }
    Start-Sleep -Milliseconds 500
    if ($WaitForExitSeconds -gt 0 -and -not $target.WaitForExit($WaitForExitSeconds * 1000)) { throw 'Process did not exit after its normal console interrupt.' }
}
finally { [LocalConsoleControl]::FreeConsole() | Out-Null }
