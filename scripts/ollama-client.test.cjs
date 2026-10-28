const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { resolveOllamaOrigin, createOllamaClient } = require('./ollama-client.cjs');

test('default and configured Ollama origins remain loopback and match the listener', () => {
  assert.equal(resolveOllamaOrigin({}), 'http://127.0.0.1:11434');
  assert.equal(resolveOllamaOrigin({ LOCAL_AI_OLLAMA_URL: 'http://127.0.0.1:21434', OLLAMA_HOST: '127.0.0.1:21434' }), 'http://127.0.0.1:21434');
  assert.throws(() => resolveOllamaOrigin({ LOCAL_AI_OLLAMA_URL: 'http://127.0.0.1:21434', OLLAMA_HOST: '127.0.0.1:11434' }), /do not match/);
  for (const url of ['http://0.0.0.0:21434', 'http://example.com:21434', 'https://127.0.0.1:21434',
    'http://u:p@127.0.0.1:21434', 'http://127.0.0.1:21434/path', 'http://127.0.0.1:21434?x=1',
    'http://127.0.0.1:21434#x', 'http://127.0.0.1:1023', 'http://127.0.0.1:65536',
    'http://127.0.0.1:3000', 'http://127.0.0.1:5000', 'http://127.0.0.1:5002']) {
    assert.throws(() => resolveOllamaOrigin({ LOCAL_AI_OLLAMA_URL: url }), /loopback port/);
  }
});

test('model unload uses the configured HTTP listener and preserves error handling', async () => {
  const received = [];
  const server = http.createServer(async (request, response) => {
    let body = '';
    for await (const chunk of request) body += chunk;
    received.push({ path: request.url, method: request.method, body });
    response.setHeader('Content-Type', 'application/json');
    if (body.includes('failure')) { response.statusCode = 503; response.end('{}'); }
    else response.end(JSON.stringify(request.url === '/api/ps' ? { models: [] } : { done: true }));
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  try {
    const host = `127.0.0.1:${server.address().port}`;
    const api = createOllamaClient({ LOCAL_AI_OLLAMA_URL: `http://${host}`, OLLAMA_HOST: host });
    assert.deepEqual(await api('/api/ps'), { models: [] });
    assert.deepEqual(await api('/api/generate', { method: 'POST', body: JSON.stringify({ model: 'fixture', keep_alive: 0, stream: false }) }), { done: true });
    await assert.rejects(api('/api/generate', { method: 'POST', body: 'failure' }), /HTTP 503/);
    await assert.rejects(api('/api/pull'), /Unsupported/);
    assert.equal(received.length, 3);
    assert.equal(received[1].method, 'POST');
    assert.equal(JSON.parse(received[1].body).keep_alive, 0);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
});
