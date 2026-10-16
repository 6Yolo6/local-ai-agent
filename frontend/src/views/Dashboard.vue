<template>
  <div class="page-wrap">
    <!-- 顶栏 -->
    <header class="page-header">
      <h1 class="page-title">仪表盘</h1>
      <el-button size="small" :loading="store.loading" @click="store.refreshStatus">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </header>

    <!-- 服务状态卡片 -->
    <section class="card-grid">
      <!-- llama.cpp -->
      <div class="stat-card" :class="{'stat-card--on': store.isLlamaOnline}">
        <div class="stat-header">
          <span class="stat-icon">🦙</span>
          <span class="stat-badge" :class="store.isLlamaOnline ? 'badge-green' : 'badge-red'">
            {{ store.isLlamaOnline ? 'ONLINE' : 'OFFLINE' }}
          </span>
        </div>
        <div class="stat-name">llama.cpp</div>
        <div class="stat-sub">{{ store.status.llamaModel }}</div>
        <div class="stat-detail">Q4_K_M · MoE · port 8080</div>
      </div>

      <!-- Ollama -->
      <div class="stat-card" :class="{'stat-card--on': store.isOllamaOnline}">
        <div class="stat-header">
          <span class="stat-icon">🦙</span>
          <span class="stat-badge" :class="store.isOllamaOnline ? 'badge-green' : 'badge-gray'">
            {{ store.isOllamaOnline ? 'ONLINE' : 'OFFLINE' }}
          </span>
        </div>
        <div class="stat-name">Ollama</div>
        <div class="stat-sub">{{ store.status.ollamaModel }}</div>
        <div class="stat-detail">AnythingLLM · port 11434</div>
      </div>

      <!-- MCP Server -->
      <div class="stat-card" :class="{'stat-card--on': store.isServerOnline}">
        <div class="stat-header">
          <span class="stat-icon">⚡</span>
          <span class="stat-badge" :class="store.isServerOnline ? 'badge-green' : 'badge-red'">
            {{ store.isServerOnline ? 'ONLINE' : 'OFFLINE' }}
          </span>
        </div>
        <div class="stat-name">MCP Server</div>
        <div class="stat-sub">Spring AI 1.0.0</div>
        <div class="stat-detail">SSE · port 5000</div>
      </div>

      <!-- ClaudeCode 连接信息 -->
      <div class="stat-card stat-card--info">
        <div class="stat-header">
          <span class="stat-icon">🔌</span>
          <span class="stat-badge badge-accent">CONFIG</span>
        </div>
        <div class="stat-name">ClaudeCode / OpenClaw</div>
        <div class="stat-sub">MCP 接入地址</div>
        <div class="stat-detail endpoint-text">{{ store.status.mcpEndpoint }}</div>
      </div>
    </section>

    <!-- MCP 工具清单 -->
    <section class="tools-section">
      <h2 class="section-title">已注册 MCP 工具 <span class="tool-count">16</span></h2>
      <div class="tool-grid">
        <div v-for="tool in mcpTools" :key="tool.name" class="tool-chip">
          <span class="tool-chip-cat" :style="{background: tool.color}">{{ tool.cat }}</span>
          <span class="tool-chip-name">{{ tool.name }}</span>
          <span class="tool-chip-desc">{{ tool.desc }}</span>
        </div>
      </div>
    </section>

    <!-- 快速对话 -->
    <section class="quick-chat">
      <h2 class="section-title">快速测试</h2>
      <div class="quick-input-row">
        <el-input
          v-model="quickPrompt"
          placeholder="输入提示词，快速测试 llama.cpp 推理..."
          class="quick-input"
          @keyup.enter="sendQuick"
        />
        <el-button type="primary" :loading="chatLoading" @click="sendQuick">发送</el-button>
      </div>
      <div v-if="quickReply" class="quick-reply">
        <span class="reply-label">回复</span>
        <pre class="reply-text">{{ quickReply }}</pre>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useAgentStore } from '@/stores/useAgentStore.js'
import { chat } from '@/api/index.js'
import { ElMessage } from 'element-plus'

const store = useAgentStore()
const quickPrompt = ref('')
const quickReply  = ref('')
const chatLoading = ref(false)

// MCP 工具描述
const mcpTools = [
  { name: 'generateCode',   cat: 'DEV',  color: '#1f6feb', desc: '代码生成（Java/Vue）' },
  { name: 'readFile',       cat: 'DEV',  color: '#1f6feb', desc: '读取文件内容' },
  { name: 'writeFile',      cat: 'DEV',  color: '#1f6feb', desc: '写入/创建文件' },
  { name: 'listFiles',      cat: 'DEV',  color: '#1f6feb', desc: '列出目录内容' },
  { name: 'optimizeSql',    cat: 'DEV',  color: '#1f6feb', desc: 'SQL 优化分析' },
  { name: 'compileProject', cat: 'DEV',  color: '#1f6feb', desc: 'Maven/npm 编译' },
  { name: 'executeCommand', cat: 'SYS',  color: '#388bfd', desc: 'Windows 命令执行' },
  { name: 'parseLog',       cat: 'SYS',  color: '#388bfd', desc: 'AI 日志分析' },
  { name: 'postToQQChannel',cat: 'COM',  color: '#3fb950', desc: 'QQ 频道发帖' },
  { name: 'pushToFeishu',   cat: 'COM',  color: '#3fb950', desc: '飞书消息推送' },
  { name: 'generatePost',   cat: 'COM',  color: '#3fb950', desc: 'AI 生成社群帖子' },
  { name: 'notifyResult',   cat: 'COM',  color: '#3fb950', desc: 'Agent 结果回传' },
]

async function sendQuick() {
  if (!quickPrompt.value.trim()) return
  if (!store.isLlamaOnline) {
    ElMessage.warning('llama.cpp 未启动，请先运行 start-llamacpp.bat')
    return
  }
  chatLoading.value = true
  quickReply.value  = ''
  try {
    const resp = await chat(quickPrompt.value)
    quickReply.value = resp.data
  } catch (e) {
    quickReply.value = '❌ ' + e.message
  } finally {
    chatLoading.value = false
  }
}
</script>

<style scoped>
.page-wrap   { height: 100vh; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 24px; }
.page-header { display: flex; align-items: center; justify-content: space-between; }
.page-title  { font-size: 20px; font-weight: 700; color: var(--text-1); font-family: var(--font-mono); }
.section-title { font-size: 13px; font-weight: 600; color: var(--text-2); text-transform: uppercase; letter-spacing: 1px; margin-bottom: 12px; }
.tool-count  { background: var(--accent-dim); color: var(--accent); font-size: 11px; padding: 1px 6px; border-radius: 10px; margin-left: 6px; }

/* 状态卡片 */
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; }
.stat-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
  transition: border-color .2s;
}
.stat-card--on   { border-color: rgba(63,185,80,.3); }
.stat-card--info { border-color: rgba(0,212,170,.25); }
.stat-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.stat-icon   { font-size: 22px; }
.stat-badge  { font-family: var(--font-mono); font-size: 10px; font-weight: 700; padding: 2px 7px; border-radius: 4px; }
.badge-green { background: rgba(63,185,80,.15); color: var(--green); }
.badge-red   { background: rgba(248,81,73,.15);  color: var(--red); }
.badge-gray  { background: rgba(72,79,88,.3);    color: var(--text-3); }
.badge-accent{ background: var(--accent-dim);    color: var(--accent); }
.stat-name   { font-weight: 700; font-size: 15px; color: var(--text-1); }
.stat-sub    { font-family: var(--font-mono); font-size: 12px; color: var(--accent); margin-top: 3px; }
.stat-detail { font-size: 11px; color: var(--text-3); margin-top: 6px; font-family: var(--font-mono); }
.endpoint-text { color: var(--text-2) !important; word-break: break-all; }

/* 工具清单 */
.tool-grid { display: flex; flex-wrap: wrap; gap: 8px; }
.tool-chip {
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 5px 10px;
  font-size: 12px;
}
.tool-chip-cat  { font-size: 10px; font-family: var(--font-mono); font-weight: 700; color: #0d1117; padding: 1px 5px; border-radius: 3px; }
.tool-chip-name { font-family: var(--font-mono); color: var(--text-1); }
.tool-chip-desc { color: var(--text-3); }

/* 快速对话 */
.quick-chat       { background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px; padding: 16px; }
.quick-input-row  { display: flex; gap: 8px; }
.quick-input      { flex: 1; }
.quick-reply      { margin-top: 14px; border-top: 1px solid var(--border); padding-top: 14px; }
.reply-label      { font-size: 11px; color: var(--accent); font-family: var(--font-mono); text-transform: uppercase; }
.reply-text {
  margin-top: 8px;
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--text-1);
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  background: var(--bg-deep);
  border-radius: 6px;
  padding: 12px;
  max-height: 300px;
  overflow-y: auto;
}
</style>
