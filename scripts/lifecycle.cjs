const fs = require('node:fs/promises');
const path = require('node:path');

function validRequest(request, identity, now = Date.now()) {
  return request && request.instanceId === identity.instanceId &&
    request.processId === identity.processId && request.startedAt === identity.startedAt &&
    typeof request.requestedAt === 'string' &&
    Number.isFinite(Date.parse(request.requestedAt)) &&
    Date.parse(request.requestedAt) >= Date.parse(identity.startedAt) &&
    Math.abs(now - Date.parse(request.requestedAt)) <= 600000;
}

async function writeJson(file, value) {
  const temporary = file + '.tmp';
  await fs.writeFile(temporary, JSON.stringify(value, null, 2), { encoding: 'utf8', flag: 'w' });
  await fs.rename(temporary, file);
}

function startLifecycle({ close, instanceId = process.env.LOCAL_AI_INSTANCE_ID,
    controlDirectory = process.env.LOCAL_AI_CONTROL_DIR, processId = process.pid,
    pollMs = 250, timeoutMs = 180000 }) {
  if (!/^[0-9a-f-]{36}$/i.test(instanceId || '') || !controlDirectory ||
      path.basename(controlDirectory) !== instanceId) {
    throw new Error('A launcher instance and private control directory are required.');
  }
  let identity;
  let stopping = false;
  let reading = false;
  let timer;
  const poll = async () => {
    if (reading || stopping) return;
    reading = true;
    try {
      if (!identity) {
        const candidate = JSON.parse((await fs.readFile(path.join(controlDirectory, 'identity.json'), 'utf8')).replace(/^\uFEFF/, ''));
        if (candidate.instanceId !== instanceId || candidate.processId !== processId ||
            !Number.isFinite(Date.parse(candidate.startedAt)) ||
            Math.abs(Date.now() - Date.parse(candidate.startedAt)) > 60000) {
          throw new Error('Launcher identity does not match this process.');
        }
        identity = Object.freeze(candidate);
        await writeJson(path.join(controlDirectory, 'ready.json'), { ...identity, status: 'ready' });
      }
      const requestPath = path.join(controlDirectory, 'stop.request.json');
      const request = JSON.parse((await fs.readFile(requestPath, 'utf8')).replace(/^\uFEFF/, ''));
      if (!validRequest(request, identity)) {
        await writeJson(path.join(controlDirectory, 'stop.rejected.json'), { status: 'rejected', reason: 'Instance, process identity or request time mismatch' });
        return;
      }
      stopping = true;
      clearInterval(timer);
      await writeJson(path.join(controlDirectory, 'stop.accepted.json'), { ...identity, status: 'draining', requestedAt: request.requestedAt });
      let deadline;
      try {
        await Promise.race([
          close(),
          new Promise((_, reject) => { deadline = setTimeout(() => reject(new Error('Graceful shutdown exceeded ' + timeoutMs + ' ms; no force-stop attempted')), timeoutMs); })
        ]);
        await writeJson(path.join(controlDirectory, 'stop.completed.json'), { ...identity, status: 'stopped', completedAt: new Date().toISOString() });
      } catch (error) {
        await writeJson(path.join(controlDirectory, 'stop.failed.json'), { ...identity, status: 'failed', error: error.message });
        // Keep the process available for diagnosis. Never force-exit a service.
        timer = setInterval(() => {}, 1000);
      } finally { clearTimeout(deadline); }
    } catch (error) {
      if (error.code !== 'ENOENT') console.error('[lifecycle]', error.message);
    } finally { reading = false; }
  };
  timer = setInterval(poll, pollMs);
  poll();
  return () => clearInterval(timer);
}

module.exports = { validRequest, writeJson, startLifecycle };
