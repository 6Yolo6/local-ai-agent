import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
PS = Path(os.environ.get('SystemRoot', 'C:/Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe'


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


class BasicLimits(ctypes.Structure):
    _fields_ = [('processTime', ctypes.c_longlong), ('jobTime', ctypes.c_longlong),
                ('flags', wintypes.DWORD), ('minWs', ctypes.c_size_t), ('maxWs', ctypes.c_size_t),
                ('processLimit', wintypes.DWORD), ('affinity', ctypes.c_size_t),
                ('priority', wintypes.DWORD), ('scheduling', wintypes.DWORD)]


class ExtendedLimits(ctypes.Structure):
    _fields_ = [('basic', BasicLimits), ('io', ctypes.c_ulonglong * 6),
                ('processMemory', ctypes.c_size_t), ('jobMemory', ctypes.c_size_t),
                ('peakProcessMemory', ctypes.c_size_t), ('peakJobMemory', ctypes.c_size_t)]


@unittest.skipUnless(os.name == 'nt', 'Windows process Job integration')
class ServiceHostTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="local ai 中文 ' host-")
        self.root = Path(self.temp.name)
        self.scripts = self.root / 'scripts'
        self.scripts.mkdir()
        shutil.copyfile(SCRIPTS / 'service-host.ps1', self.scripts / 'service-host.ps1')
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.kernel.CreateJobObjectW.restype = wintypes.HANDLE
        self.kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        self.kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.job = None
        self.parent = None
        self.child = None
        self.write_mock()

    def write_mock(self):
        child = r'''$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSCommandPath
while(-not (Test-Path -LiteralPath (Join-Path $root 'stop-child'))) {
    [IO.File]::WriteAllText((Join-Path $root 'heartbeat'),[DateTimeOffset]::Now.ToString('o'))
    Start-Sleep -Milliseconds 100
}
'''
        (self.root / 'child.ps1').write_text(child, encoding='utf-8')
        mock = r'''param([string[]]$Services,[switch]$Build,[switch]$PullModel,[switch]$ServiceHost)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
. "$PSScriptRoot/service-host.ps1"
[IO.File]::WriteAllText((Join-Path $root 'job-probe.json'),(@{inJob=[LocalAiIndependentProcess]::InJob();flags=[LocalAiIndependentProcess]::JobFlags();pid=$PID}|ConvertTo-Json))
if([LocalAiIndependentProcess]::InJob()){throw 'Still inside a caller Job'}
if($env:LOCAL_AI_MODEL -eq 'fail-fixture'){throw 'Synthetic startup failure'}
$exe=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$child=Start-Process -FilePath $exe -ArgumentList @('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',('"'+(Join-Path $root 'child.ps1')+'"')) -WindowStyle Hidden -PassThru
$d=[ordered]@{services=$Services;build=[bool]$Build;pull=[bool]$PullModel;hosted=[bool]$ServiceHost;inJob=[LocalAiIndependentProcess]::InJob();environment=$env:LOCAL_AI_MODEL;childPid=$child.Id}
[IO.File]::WriteAllText((Join-Path $root 'fixture.json'),($d|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
'''
        (self.scripts / 'start-local.ps1').write_text(mock, encoding='utf-8')

    def tearDown(self):
        (self.root / 'stop-child').touch()
        (self.root / 'stop-parent').touch()
        if self.job:
            self.kernel.CloseHandle(self.job)
            self.job = None
        if self.parent:
            self.parent.wait(timeout=10)
        if self.child:
            try:
                handle = self.child
                self.kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
                self.assertEqual(self.kernel.WaitForSingleObject(handle, 10000), 0, 'Fixture must stop naturally')
            finally:
                self.kernel.CloseHandle(self.child)
        # The only temporary tree deleted here is the verified fixture owned by this test.
        self.assertTrue(self.root.resolve().name.startswith('local ai '))
        self.temp.cleanup()

    def wait_file(self, name, timeout=25):
        path = self.root / name
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if path.exists():
                return path
            time.sleep(.05)
        self.fail('Missing fixture output: ' + name)

    def caller(self, breakaway=True, services="@('backend','router')", fail=False, denied=False):
        script = '\n'.join([
            "$ErrorActionPreference='Stop'",
            '$ProjectRoot=' + literal(self.root),
            '. ' + literal(self.scripts / 'service-host.ps1'),
            ("function Invoke-CimMethod { [pscustomobject]@{ReturnValue=2;ProcessId=0} }" if denied else ''),
            "while(-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'release'))) {Start-Sleep -Milliseconds 50}",
            'try {',
            ' $r=Invoke-IndependentLocalStartup -Services ' + services + ' -Build -PullModel -WaitSeconds 15',
            " $o=@{status='ready';result=$r}",
            "} catch {$o=@{status='failed';errorType=$_.Exception.GetType().Name;message=$_.Exception.Message}}",
            "[IO.File]::WriteAllText((Join-Path $ProjectRoot 'caller.json'),($o|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))",
            "while(-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'stop-parent'))) {Start-Sleep -Milliseconds 50}"
        ])
        path = self.root / 'caller.ps1'
        path.write_text(script, encoding='utf-8-sig')
        env = dict(os.environ, LOCAL_AI_MODEL='inherited-fixture-value')
        if fail:
            env['LOCAL_AI_MODEL'] = 'fail-fixture'
        self.parent = subprocess.Popen([str(PS), '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(path)], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.job = self.kernel.CreateJobObjectW(None, None)
        self.assertTrue(self.job)
        limits = ExtendedLimits()
        limits.basic.flags = 0x2000 | (0x800 if breakaway else 0)
        self.assertTrue(self.kernel.SetInformationJobObject(self.job, 9, ctypes.byref(limits), ctypes.sizeof(limits)), ctypes.get_last_error())
        self.assertTrue(self.kernel.AssignProcessToJobObject(self.job, wintypes.HANDLE(int(self.parent._handle))), ctypes.get_last_error())
        (self.root / 'release').touch()
        return json.loads(self.wait_file('caller.json').read_text(encoding='utf-8-sig'))

    def test_child_survives_callers_job_close_and_preserves_options_environment(self):
        output = self.caller(breakaway=False)
        self.assertEqual(output['status'], 'ready', json.dumps({'caller':output,'diagnostics':[{str(p.relative_to(self.root)):p.read_text(encoding='utf-8-sig',errors='replace')} for pattern in ['.local/startup/*/output.log','.local/startup/*/result.json','job-probe.json'] for p in self.root.glob(pattern)]},ensure_ascii=False))
        fixture = json.loads(self.wait_file('fixture.json').read_text(encoding='utf-8-sig'))
        self.assertEqual(fixture['services'], ['backend', 'router'])
        self.assertTrue(fixture['build'] and fixture['pull'] and fixture['hosted'])
        self.assertFalse(fixture['inJob'])
        self.assertEqual(fixture['environment'], 'inherited-fixture-value')
        self.kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        self.kernel.OpenProcess.restype = wintypes.HANDLE
        self.child = self.kernel.OpenProcess(0x100000 | 0x1000, False, fixture['childPid'])
        self.assertTrue(self.child)
        beat = self.wait_file('heartbeat')
        first = beat.read_text()
        self.assertIsNone(self.parent.poll(), 'Fixture caller must still be alive before its Job closes')
        self.assertTrue(self.kernel.CloseHandle(self.job))
        self.job = None
        self.parent.wait(timeout=10)
        time.sleep(.3)
        self.assertNotEqual(first, beat.read_text(), 'Independent fixture must continue after the parent Job closes')

    def test_windows_creation_denial_fails_without_starting_services(self):
        output = self.caller(denied=True)
        self.assertEqual(output['status'], 'failed')
        self.assertFalse((self.root / 'fixture.json').exists())

    def test_startup_failure_is_not_reported_as_ready(self):
        output = self.caller(fail=True)
        self.assertEqual(output['status'], 'failed')
        self.assertFalse((self.root / 'fixture.json').exists())

    def test_duplicate_services_are_rejected_before_launch(self):
        output = self.caller(services="@('backend','backend')")
        self.assertEqual(output['status'], 'failed')
        self.assertFalse((self.root / 'fixture.json').exists())

    def test_invalid_service_is_rejected_before_launch(self):
        output = self.caller(services="@('invalid')")
        self.assertEqual(output['status'], 'failed')
        self.assertFalse((self.root / 'fixture.json').exists())

    def test_hosted_switch_cannot_start_inside_a_job(self):
        shutil.copyfile(SCRIPTS / 'start-local.ps1', self.scripts / 'start-local.ps1')
        (self.scripts / 'local-common.ps1').write_text('$ProjectRoot=Split-Path -Parent $PSScriptRoot\nfunction Initialize-LocalDirectories {}\n', encoding='utf-8')
        r = subprocess.run([str(PS), '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(self.scripts / 'start-local.ps1'), '-Service', 'backend', '-ServiceHost'], capture_output=True, timeout=15)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn(b'No service was started', r.stderr)
        self.assertFalse((self.root / 'fixture.json').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
