const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { startLifecycle, writeJson } = require('./lifecycle.cjs');
const { createOllamaClient } = require('./ollama-client.cjs');
const execFileAsync = promisify(execFile);
const projectRoot = path.resolve(__dirname, '..');
const powershell = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');

async function main() {
  const service = process.argv[2];
  let close;
  if (service === 'router') {
    const { createApp } = require(path.join(projectRoot, 'router-layer', 'server.js'));
    const server = createApp().listen(5002, '127.0.0.1');
    await new Promise((resolve, reject) => { server.once('listening', resolve); server.once('error', reject); });
    close = () => new Promise((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
      server.closeIdleConnections?.();
    });
  } else if (service === 'frontend') {
    const { createServer } = await import(pathToFileURL(path.join(projectRoot, 'frontend', 'node_modules', 'vite', 'dist', 'node', 'index.js')).href);
    const vite = await createServer({
      root: path.join(projectRoot, 'frontend'),
      configFile: path.join(projectRoot, 'frontend', 'vite.config.js'),
      server: { host: '127.0.0.1', port: 3000, strictPort: true }
    });
    await vite.listen();
    close = () => vite.close();
  } else if (service === 'ollama') {
    const ollamaApi = createOllamaClient();
    const executable = process.env.LOCAL_AI_OLLAMA_EXECUTABLE;
    if (!executable) throw new Error('LOCAL_AI_OLLAMA_EXECUTABLE is required');
    const launched = await execFileAsync(powershell, [
      '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-File', path.join(__dirname, 'start-ollama-console.ps1'), '-Executable', executable
    ], { windowsHide: true });
    const childIdentity = JSON.parse(launched.stdout.replace(/^\uFEFF/, ''));
    await writeJson(path.join(process.env.LOCAL_AI_CONTROL_DIR, 'child.json'), childIdentity);
    close = async () => {
      const loaded = await ollamaApi('/api/ps');
      for (const model of loaded.models || []) {
        await ollamaApi('/api/generate', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ model: model.name, keep_alive: 0, stream: false })
        });
      }
      const remaining = await ollamaApi('/api/ps');
      if ((remaining.models || []).length) throw new Error('Ollama models remain loaded; shutdown refused');
      await execFileAsync(powershell, [
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', path.join(__dirname, 'console-interrupt.ps1'),
        '-TargetProcessId', String(childIdentity.processId),
        '-ExpectedStartedAt', childIdentity.startedAt,
        '-ExpectedExecutable', childIdentity.executable, '-WaitForExitSeconds', '10'
      ], { windowsHide: true, timeout: 15000 });
    };
  } else throw new Error('Unsupported managed service: ' + service);
  startLifecycle({ close });
  console.log(service + ' ready with lifecycle instance ' + process.env.LOCAL_AI_INSTANCE_ID);
}

main().catch(error => { console.error(error); process.exitCode = 1; });
