const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { validRequest, startLifecycle, writeJson } = require('./lifecycle.cjs');

test('only a recent request bound to the exact instance, PID and start time is accepted', () => {
  const now = Date.now();
  const identity = { instanceId: randomUUID(), processId: 42, startedAt: new Date(now - 1000).toISOString() };
  const request = { ...identity, requestedAt: new Date(now).toISOString() };
  assert.equal(validRequest(request, identity, now), true);
  for (const altered of [{ instanceId: randomUUID() }, { processId: 43 },
    { startedAt: new Date(now - 500).toISOString() }, { requestedAt: new Date(now - 700000).toISOString() },
    { requestedAt: 'invalid' }, { requestedAt: new Date(now + 700000).toISOString() }]) {
    assert.equal(validRequest({ ...request, ...altered }, identity, now), false);
  }
});

async function exists(file) { try { await fs.access(file); return true; } catch { return false; } }
async function waitFor(check) {
  const deadline = Date.now() + 3000;
  while (Date.now() < deadline) { if (await check()) return; await new Promise(resolve => setTimeout(resolve, 10)); }
  throw new Error('Timed out waiting for lifecycle evidence');
}
test('graceful stop rejects a stale instance and acknowledges only after the active work drains', async () => {
  const parent = await fs.mkdtemp(path.join(os.tmpdir(), 'local-ai-lifecycle-'));
  const instanceId = randomUUID();
  const directory = path.join(parent, instanceId);
  await fs.mkdir(directory);
  const identity = { instanceId, processId: process.pid, startedAt: new Date().toISOString() };
  await writeJson(path.join(directory, 'identity.json'), identity);
  let drain;
  let closeCalls = 0;
  const dispose = startLifecycle({ instanceId, controlDirectory: directory, pollMs: 10, close: () => {
    closeCalls++; return new Promise(resolve => { drain = resolve; });
  } });
  try {
    await waitFor(() => exists(path.join(directory, 'ready.json')));
    await writeJson(path.join(directory, 'stop.request.json'), { ...identity, instanceId: randomUUID(), requestedAt: new Date().toISOString() });
    await waitFor(() => exists(path.join(directory, 'stop.rejected.json')));
    assert.equal(closeCalls, 0);
    await writeJson(path.join(directory, 'stop.request.json'), { ...identity, requestedAt: new Date().toISOString() });
    await waitFor(() => exists(path.join(directory, 'stop.accepted.json')));
    await waitFor(() => Boolean(drain));
    assert.equal(closeCalls, 1);
    assert.equal(await exists(path.join(directory, 'stop.completed.json')), false);
    drain();
    await waitFor(() => exists(path.join(directory, 'stop.completed.json')));
    const complete = JSON.parse(await fs.readFile(path.join(directory, 'stop.completed.json'), 'utf8'));
    assert.equal(complete.status, 'stopped');
    assert.equal(complete.instanceId, instanceId);
  } finally {
    dispose();
    if (drain) drain();
    await fs.rm(parent, { recursive: true, force: true });
  }
});
