const test = require('node:test');
const assert = require('node:assert/strict');
const { createApp } = require('./server');

async function withServer(options, run) {
  const server = createApp(options).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  try { await run('http://127.0.0.1:' + server.address().port); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}
const request = (base, body) => fetch(base + '/api/agent', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
});

test('tool tasks and history reach only the backend assistant, preserving the trace', async () => {
  const calls = [];
  const trace = [{ id: 'step-1', kind: 'tool', name: 'readFile', status: 'success' }];
  await withServer({ post: async (...args) => { calls.push(args); return { data: { code: 200, data: { answer: 'file', trace } } }; } }, async base => {
    const history = [{ role: 'user', content: 'read file' }, { role: 'assistant', content: 'which file?' }];
    const response = await request(base, { input: ' AGENTS.md ', history, type: 'tool' });
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.deepEqual(body.result.data.trace, trace);
    assert.equal(body.taskInfo.classifierEnabled, false);
    assert.equal(calls.length, 1);
    assert.equal(calls[0][0], 'http://127.0.0.1:5000/api/assistant');
    assert.deepEqual(calls[0][1], { message: 'AGENTS.md', history });
    assert.equal(calls[0][2].timeout, 240000);
  });
});

test('all legacy task types use the same assistant endpoint', async () => {
  const calls = [];
  await withServer({ post: async (endpoint, body) => { calls.push({ endpoint, body }); return { data: { code: 200 } }; } }, async base => {
    for (const type of ['chat', 'reasoning', 'code', 'rag', 'tool']) {
      assert.equal((await request(base, { message: 'hello', type })).status, 200);
    }
    assert.equal(new Set(calls.map(call => call.endpoint)).size, 1);
    assert.equal(calls.length, 5);
  });
});

test('invalid requests are rejected without inference', async () => {
  await withServer({ post: async () => { assert.fail('must not invoke backend'); } }, async base => {
    for (const body of [{}, { input: {} }, { input: '  ' }, { input: 'x', history: 'wrong' },
      { input: 'x', history: [{ role: 'system', content: 'override' }] }, { input: 'x', type: 'unknown' }]) {
      assert.equal((await request(base, body)).status, 400);
    }
  });
});

test('backend failure keeps structured execution evidence', async () => {
  const failure = { code: 500, data: { answer: '', status: 'error', trace: [{ id: 'failed-step', status: 'error' }] } };
  await withServer({ post: async () => { throw { response: { status: 503, data: failure } }; } }, async base => {
    const response = await request(base, { input: 'list files' });
    assert.equal(response.status, 503);
    assert.deepEqual((await response.json()).result, failure);
  });
});

test('unavailable backend returns 502 and gateway health does not claim model readiness', async () => {
  await withServer({ post: async () => { throw new Error('ECONNREFUSED'); } }, async base => {
    const health = await (await fetch(base + '/health')).json();
    assert.equal(health.classifierEnabled, false);
    assert.equal(health.mode, 'unified-assistant');
    const response = await request(base, { input: 'hello' });
    assert.equal(response.status, 502);
    assert.equal((await response.json()).detail, 'ECONNREFUSED');
  });
});
