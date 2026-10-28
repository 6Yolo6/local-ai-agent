function resolveOllamaOrigin(environment = process.env) {
  const origin = environment.LOCAL_AI_OLLAMA_URL ?? 'http://127.0.0.1:11434';
  const match = /^http:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})$/.exec(origin);
  const port = match ? Number(match[1]) : 0;
  if (port < 1024 || port > 65535 || [3000, 5000, 5002].includes(port)) {
    throw new Error('Ollama URL must use the configured loopback port');
  }
  if (environment.OLLAMA_HOST !== undefined && environment.OLLAMA_HOST !== `127.0.0.1:${port}`) {
    throw new Error('Ollama listener and client ports do not match');
  }
  return origin;
}

function createOllamaClient(environment = process.env) {
  const origin = resolveOllamaOrigin(environment);
  return async (endpoint, options = {}) => {
    if (!['/api/ps', '/api/generate'].includes(endpoint)) throw new Error('Unsupported Ollama lifecycle endpoint');
    const response = await fetch(origin + endpoint, { ...options, signal: AbortSignal.timeout(175000) });
    if (!response.ok) throw new Error('Ollama returned HTTP ' + response.status);
    return response.json();
  };
}

module.exports = { resolveOllamaOrigin, createOllamaClient };
