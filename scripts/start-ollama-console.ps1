param([Parameter(Mandatory=$true)][string]$Executable)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) { throw 'Ollama executable missing.' }
$controlDirectory = $env:LOCAL_AI_CONTROL_DIR
$instanceId = $env:LOCAL_AI_INSTANCE_ID
if (-not $controlDirectory -or $instanceId -notmatch '^[0-9a-f-]{36}$' -or
    (Split-Path $controlDirectory -Leaf) -ne $instanceId -or
    -not (Test-Path -LiteralPath $controlDirectory -PathType Container) -or
    ((Get-Item -LiteralPath $controlDirectory).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'A private launcher instance directory is required for Ollama child logs.'
}
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
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
    public struct STARTUPINFOEX { public STARTUPINFO startup; public IntPtr attributes; }
    [StructLayout(LayoutKind.Sequential)]
    public struct SECURITY_ATTRIBUTES { public int length; public IntPtr descriptor; public int inherit; }
    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION {
        public IntPtr hProcess; public IntPtr hThread; public uint dwProcessId; public uint dwThreadId;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr CreateFile(string name, uint access, uint share, ref SECURITY_ATTRIBUTES security,
        uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, int flags, ref IntPtr size);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, IntPtr attribute,
        IntPtr value, IntPtr size, IntPtr previous, IntPtr returnedSize);
    [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool CreateProcess(string application, StringBuilder commandLine,
        IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint creationFlags,
        IntPtr environment, string directory, ref STARTUPINFOEX startupInfo, out PROCESS_INFORMATION processInformation);
    [DllImport("kernel32.dll")] public static extern bool CloseHandle(IntPtr handle);

    static IntPtr Open(string name, uint access, uint creation) {
        var security = new SECURITY_ATTRIBUTES { length=Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES)), inherit=1 };
        IntPtr handle = CreateFile(name, access, 1, ref security, creation, 0x80, IntPtr.Zero);
        if (handle == new IntPtr(-1)) throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot open Ollama child stream.");
        return handle;
    }
    public static PROCESS_INFORMATION Start(string executable, string directory, string stdout, string stderr) {
        IntPtr input=IntPtr.Zero, output=IntPtr.Zero, error=IntPtr.Zero;
        IntPtr attributes=IntPtr.Zero, handles=IntPtr.Zero;
        bool initialized=false;
        try {
            // CREATE_NEW prevents overwriting prior instance evidence. NUL supplies valid stdin.
            input=Open("NUL", 0x80000000, 3);
            output=Open(stdout, 0x40000000, 1);
            error=Open(stderr, 0x40000000, 1);
            IntPtr size=IntPtr.Zero;
            InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref size);
            if (size == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            attributes=Marshal.AllocHGlobal(size);
            if (!InitializeProcThreadAttributeList(attributes, 1, 0, ref size)) throw new Win32Exception(Marshal.GetLastWin32Error());
            initialized=true;
            handles=Marshal.AllocHGlobal(IntPtr.Size * 3);
            Marshal.WriteIntPtr(handles, 0, input);
            Marshal.WriteIntPtr(handles, IntPtr.Size, output);
            Marshal.WriteIntPtr(handles, IntPtr.Size * 2, error);
            // Inherit only these three handles, never Node's execFile capture pipes.
            if (!UpdateProcThreadAttribute(attributes, 0, new IntPtr(0x20002), handles,
                new IntPtr(IntPtr.Size * 3), IntPtr.Zero, IntPtr.Zero)) throw new Win32Exception(Marshal.GetLastWin32Error());
            var startup = new STARTUPINFOEX();
            startup.startup.cb=Marshal.SizeOf(typeof(STARTUPINFOEX));
            startup.startup.dwFlags=0x101; // STARTF_USESHOWWINDOW | STARTF_USESTDHANDLES
            startup.startup.wShowWindow=0;
            startup.startup.hStdInput=input;
            startup.startup.hStdOutput=output;
            startup.startup.hStdError=error;
            startup.attributes=attributes;
            PROCESS_INFORMATION process;
            // Preserve CREATE_NEW_CONSOLE; extended startup info only restricts handle inheritance.
            if (!CreateProcess(executable, new StringBuilder("\"" + executable + "\" serve"),
                IntPtr.Zero, IntPtr.Zero, true, 0x80010, IntPtr.Zero, directory, ref startup, out process)) {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot create hidden Ollama console.");
            }
            return process;
        } finally {
            if (initialized) DeleteProcThreadAttributeList(attributes);
            if (attributes != IntPtr.Zero) Marshal.FreeHGlobal(attributes);
            if (handles != IntPtr.Zero) Marshal.FreeHGlobal(handles);
            if (input != IntPtr.Zero) CloseHandle(input);
            if (output != IntPtr.Zero) CloseHandle(output);
            if (error != IntPtr.Zero) CloseHandle(error);
        }
    }
}
'@
$information = [HiddenConsoleProcess]::Start($Executable, (Split-Path $Executable),
    (Join-Path $controlDirectory 'child.stdout.log'), (Join-Path $controlDirectory 'child.stderr.log'))
try {
    $process = Get-Process -Id $information.dwProcessId -ErrorAction Stop
    @{ processId=$process.Id; startedAt=$process.StartTime.ToUniversalTime().ToString('o'); executable=$process.Path } | ConvertTo-Json -Compress
}
finally {
    [HiddenConsoleProcess]::CloseHandle($information.hThread) | Out-Null
    [HiddenConsoleProcess]::CloseHandle($information.hProcess) | Out-Null
}
