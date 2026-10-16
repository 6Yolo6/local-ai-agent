<template>
  <div class="page-wrap">
    <div class="page-header">
      <h1 class="page-title">配置说明</h1>
    </div>

    <el-tabs v-model="activeTab" class="config-tabs">
      <el-tab-pane label="llama.cpp" name="llama">
        <ConfigBlock title="llama.cpp 启动命令" :code="llamaCmd" />
        <ConfigBlock title="application.yml 配置" :code="llamaYaml" />
      </el-tab-pane>
      <el-tab-pane label="ClaudeCode + OpenClaw" name="claude">
        <ConfigBlock title="ClaudeCode MCP 配置（~/.claude/claude_desktop_config.json）" :code="claudeConfig" />
        <ConfigBlock title="ANTHROPIC_BASE_URL 说明" :code="envNote" />
      </el-tab-pane>
      <el-tab-pane label="QQ Bot" name="qq">
        <ConfigBlock title="go-cqhttp config.yml" :code="cqConfig" />
      </el-tab-pane>
      <el-tab-pane label="飞书 Bot" name="feishu">
        <ConfigBlock title="飞书 Webhook 配置" :code="feishuConfig" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, h } from 'vue'
const activeTab = ref('llama')

// ConfigBlock 内联组件
const ConfigBlock = { props: ['title','code'], template: `
  <div class="config-block">
    <div class="config-title">{{ title }}</div>
    <pre class="config-code">{{ code }}</pre>
  </div>
`}

const llamaCmd = `# E:\\AIModel\\gguf 目录下运行（或双击 start-llamacpp.bat）
llama-server.exe ^
  -m "E:\\AIModel\\gguf\\Qwen_Qwen3.6-35B-A3B-Q4_K_M.gguf" ^
  -ngl 35 ^
  --main-gpu 0 ^
  --host 127.0.0.1 ^
  --port 8080 ^
  -c 16384 ^
  --cache-type-k q4_0 ^
  --cache-type-v q4_0 ^
  --threads 16 ^
  --flash-attn auto

# 验证：浏览器访问 http://127.0.0.1:8080/health`

const llamaYaml = `local-ai:
  llamacpp:
    base-url: http://127.0.0.1:8080
    model: local-model       # llama.cpp 忽略此字段
    temperature: 0.7
    max-tokens: 4096
    timeout-seconds: 180     # MoE 首 Token 较慢，建议 ≥120s`

const claudeConfig = `// ~\\AppData\\Roaming\\Claude\\claude_desktop_config.json
{
  "mcpServers": {
    "local-ai-agent": {
      "url": "http://127.0.0.1:5000/mcp"
    }
  }
}

// 或在项目根目录的 .claude/settings.json 中：
{
  "mcpServers": {
    "local-ai-agent": {
      "url": "http://127.0.0.1:5000/mcp",
      "description": "本地 AI Agent MCP 服务"
    }
  }
}`

const envNote = `# ClaudeCode 接 Ollama 方式（如需）
# 注意：Ollama 默认暴露 OpenAI 兼容 API（/v1），非 Anthropic API
# 若 ClaudeCode 要用 Ollama 作为后端推理，需要 LiteLLM 等中间层转换

# 设置环境变量（PowerShell）
$env:ANTHROPIC_BASE_URL = "http://localhost:11434/v1/anthropic"
# 本项目 MCP Server 本身用 llama.cpp，以上配置仅影响 ClaudeCode 内置推理`

const cqConfig = `# D:\\Dev\\go-cqhttp\\config.yml
account:
  uin: 你的QQ号
  password: ""
  protocol: 6   # iPad 协议

servers:
  - http:
      host: 127.0.0.1
      port: 5700
      post:
        - url: http://127.0.0.1:5000/bot/qq/webhook
          secret: ""`

const feishuConfig = `# 1. 飞书群 → 群设置 → 机器人 → 添加自定义机器人
# 2. 复制 Webhook 地址
# 3. 填入 application.yml：

local-ai:
  feishu:
    webhook-url: "https://open.feishu.cn/open-apis/bot/v2/hook/你的TOKEN"

# 测试推送（curl）：
# curl -X POST "Webhook地址" -H "Content-Type: application/json" ^
#   -d "{\"msg_type\":\"text\",\"content\":{\"text\":\"测试消息\"}}"`
</script>

<style scoped>
.page-wrap   { height: 100vh; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 20px; }
.page-title  { font-family: var(--font-mono); font-size: 20px; font-weight: 700; color: var(--text-1); }
.config-tabs { flex: 1; }
.config-block { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 16px; overflow: hidden; }
.config-title { padding: 10px 16px; font-size: 12px; font-weight: 600; color: var(--text-3); border-bottom: 1px solid var(--border); font-family: var(--font-mono); }
.config-code  { padding: 14px 16px; font-family: var(--font-mono); font-size: 12px; color: var(--text-1); white-space: pre-wrap; line-height: 1.7; margin: 0; }
</style>
