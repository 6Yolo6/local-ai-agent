param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) { throw 'Ollama executable missing.' }
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class HiddenConsoleProcess {
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
    public struct STARTUPINFO {
        public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
        public int dwX; public int dwY; public int dwXSize; public int dwYSize;
        public int dwXCountChars; public int dwYCountChars; public int dwFillAttribute;
        public int dwFlags; public short wShowWindow; public short cbReserved2;
        public IntPtr lpReserved2; public IntPtr hStdInput; public IntPtr hStdOutput; public IntPtr hStdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION {
        public IntPtr hProcess; public IntPtr hThread; public uint dwProcessId; public uint dwThreadId;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern bool CreateProcess(string application, StringBuilder commandLine,
        IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint creationFlags,
        IntPtr environment, string directory, ref STARTUPINFO startupInfo, out PROCESS_INFORMATION processInformation);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);
}
'@
$startup = New-Object HiddenConsoleProcess+STARTUPINFO
$startup.cb = [Runtime.InteropServices.Marshal]::SizeOf($startup)
$startup.dwFlags = 1
$startup.wShowWindow = 0
$information = New-Object HiddenConsoleProcess+PROCESS_INFORMATION
$commandLine = New-Object Text.StringBuilder ('"' + $Executable + '" serve')
# CREATE_NEW_CONSOLE is explicit. Node's detached flag uses DETACHED_PROCESS on
# Windows, which would create a process that cannot receive console Ctrl+C.
if (-not [HiddenConsoleProcess]::CreateProcess($Executable, $commandLine, [IntPtr]::Zero,
    [IntPtr]::Zero, $false, 0x10, [IntPtr]::Zero, (Split-Path $Executable),
    [ref]$startup, [ref]$information)) {
    throw "Cannot create hidden Ollama console (Win32 $([Runtime.InteropServices.Marshal]::GetLastWin32Error()))."
}
try {
    $process = Get-Process -Id $information.dwProcessId -ErrorAction Stop
    @{ processId=$process.Id; startedAt=$process.StartTime.ToUniversalTime().ToString('o'); executable=$process.Path } | ConvertTo-Json -Compress
}
finally {
    [HiddenConsoleProcess]::CloseHandle($information.hThread) | Out-Null
    [HiddenConsoleProcess]::CloseHandle($information.hProcess) | Out-Null
}
