const express = require('express');
const axios = require('axios');
const routerConfig = require('./router.json');

const allowedTypes = new Set(['chat', 'reasoning', 'code', 'rag', 'tool', 'assistant']);

/**
 * Compatibility gateway: keep /api/agent and its taskInfo/result envelope while
 * one backend assistant owns model inference, tool execution and workspace rules.
 * No classifier model is loaded on the local GPU.
 */
function createApp({ post = axios.post, endpoint = process.env.ASSISTANT_ENDPOINT || routerConfig.assistant.endpoint } = {}) {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/health', (_req, res) => {
    res.json({ ok: true, service: 'router-layer', mode: 'unified-assistant', classifierEnabled: false });
  });

  app.post('/api/agent', async (req, res) => {
    const input = req.body?.input ?? req.body?.message;
    if (typeof input !== 'string' || !input.trim()) {
      return res.status(400).json({ error: 'input (or message) must be a non-empty string' });
    }
    const history = req.body?.history ?? [];
    if (!Array.isArray(history) || history.some(item => !item ||
        !['user', 'assistant'].includes(item.role) || typeof item.content !== 'string')) {
      return res.status(400).json({ error: 'history must contain user/assistant messages with string content' });
    }
    const scope = req.body?.scope ?? 'local';
    if (!['local', 'gying'].includes(scope)) return res.status(400).json({ error: 'unsupported query scope' });
    const type = req.body?.type ?? 'assistant';
    if (!allowedTypes.has(type)) return res.status(400).json({ error: 'unsupported task type' });

    const taskInfo = { type, complexity: 'low', mode: 'unified-assistant', classifierEnabled: false };
    try {
      const response = await post(endpoint, {
        message: input.trim(), scope,
        history: history.map(({ role, content }) => ({ role, content }))
      }, { timeout: routerConfig.assistant.timeoutMs });
      res.json({ taskInfo, result: response.data });
    } catch (error) {
      // A structured backend failure still contains useful execution evidence.
      if (error.response?.data?.data?.trace) {
        return res.status(error.response.status || 502).json({ taskInfo, result: error.response.data });
      }
      res.status(502).json({
        taskInfo, error: 'Assistant backend request failed',
        detail: error.response?.data?.message || error.message
      });
    }
  });

  app.use((error, _req, res, _next) => {
    res.status(error.status || 500).json({ error: error.status === 400 ? 'Invalid JSON request' : 'Request failed' });
  });
  return app;
}

if (require.main === module) {
  const port = Number(process.env.ROUTER_PORT || 5002);
  createApp().listen(port, '127.0.0.1', () => {
    console.log('Router gateway running on http://127.0.0.1:' + port + ' (unified assistant, no classifier)');
  });
}

module.exports = { createApp };
