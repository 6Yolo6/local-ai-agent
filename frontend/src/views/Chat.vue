<template>
  <div class="chat-layout">
    <!-- 顶栏 -->
    <div class="chat-topbar">
      <span class="chat-title">对话 Playground</span>
      <div class="topbar-controls">
        <!-- 引擎切换 -->
        <div class="engine-switch">
          <span class="engine-label">推理引擎</span>
          <el-radio-group v-model="engine" size="small">
            <el-radio-button value="llama">
              🦙 llama.cpp
              <span class="engine-model">Q4_K_M</span>
            </el-radio-button>
            <el-radio-button value="ollama">
              🦙 Ollama
              <span class="engine-model">14b</span>
            </el-radio-button>
          </el-radio-group>
        </div>
        <el-button size="small" @click="clearHistory">清空</el-button>
      </div>
    </div>

    <!-- 系统提示词 -->
    <div class="system-prompt-bar" :class="{ 'sys-expanded': sysExpanded }">
      <div class="sys-header" @click="sysExpanded = !sysExpanded">
        <el-icon><Setting /></el-icon>
        <span>系统提示词</span>
        <span class="sys-preview" v-if="!sysExpanded && systemPrompt">{{ systemPrompt.slice(0, 60) }}…</span>
        <el-icon class="sys-arrow" :class="{ 'sys-arrow--open': sysExpanded }"><ArrowDown /></el-icon>
      </div>
      <el-input
        v-if="sysExpanded"
        v-model="systemPrompt"
        type="textarea"
        :rows="3"
        placeholder="可选：角色设定、背景信息、输出格式要求..."
        class="sys-textarea"
      />
    </div>

    <!-- 消息列表 -->
    <div class="messages" ref="messagesEl">
      <!-- 空状态 -->
      <div v-if="!messages.length" class="empty-state">
        <div class="empty-icon">⬡</div>
        <p>选择推理引擎，开始对话</p>
        <div class="empty-hints">
          <div class="hint-chip" @click="setPrompt('你好，请介绍一下你自己和你使用的模型')">👋 模型自介</div>
          <div class="hint-chip" @click="setPrompt('帮我生成一个 Spring Boot 3 的 HelloWorld 控制器')">☕ Java 代码</div>
          <div class="hint-chip" @click="setPrompt('帮我优化这个 SQL：SELECT * FROM user WHERE name LIKE \'%张%\'')">📊 SQL 优化</div>
          <div class="hint-chip" @click="setPrompt('用 Vue3 Composition API 写一个防抖搜索框组件')">⚡ Vue3 组件</div>
        </div>
      </div>

      <!-- 消息气泡 -->
      <div
        v-for="(msg, i) in messages"
        :key="i"
        class="msg-row"
        :class="msg.role === 'user' ? 'msg-row--user' : 'msg-row--ai'"
      >
        <div class="msg-avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</div>
        <div class="msg-bubble" :class="msg.role === 'user' ? 'bubble-user' : 'bubble-ai'">
          <div class="msg-meta">
            <span class="msg-role">{{ msg.role === 'user' ? '你' : engineLabel }}</span>
            <span class="msg-time">{{ msg.time }}</span>
          </div>
          <pre class="msg-content">{{ msg.content }}</pre>
        </div>
      </div>

      <!-- 加载中 -->
      <div v-if="loading" class="msg-row msg-row--ai">
        <div class="msg-avatar">🤖</div>
        <div class="msg-bubble bubble-ai">
          <div class="typing-dots">
            <span /><span /><span />
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="input-area">
      <el-input
        v-model="inputText"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 6 }"
        placeholder="输入消息... (Ctrl+Enter 发送)"
        class="msg-input"
        @keydown.ctrl.enter="sendMessage"
      />
      <el-button
        type="primary"
        :loading="loading"
        :disabled="!inputText.trim()"
        class="send-btn"
        @click="sendMessage"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import { chat } from '@/api/index.js'
import { useAgentStore } from '@/stores/useAgentStore.js'
import { ElMessage } from 'element-plus'

const store = useAgentStore()
const engine       = ref('llama')
const systemPrompt = ref('')
const inputText    = ref('')
const messages     = ref([])
const loading      = ref(false)
const sysExpanded  = ref(false)
const messagesEl   = ref(null)

const engineLabel = computed(() =>
  engine.value === 'llama' ? 'Qwen3.6·35B' : 'Ollama·14b'
)

function setPrompt(text) { inputText.value = text }

function clearHistory() { messages.value = [] }

function now() {
  return new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  // 检查引擎状态
  if (engine.value === 'llama' && !store.isLlamaOnline) {
    ElMessage.warning('llama.cpp 未启动，请运行 start-llamacpp.bat')
    return
  }
  if (engine.value === 'ollama' && !store.isOllamaOnline) {
    ElMessage.warning('Ollama 未启动，请运行 start-ollama.bat')
    return
  }

  // 追加用户消息
  messages.value.push({ role: 'user', content: text, time: now() })
  inputText.value = ''
  loading.value   = true
  await scrollBottom()

  try {
    const resp = await chat(text, systemPrompt.value, engine.value)
    messages.value.push({ role: 'ai', content: resp.data, time: now() })
  } catch (e) {
    messages.value.push({ role: 'ai', content: '❌ 推理失败：' + e.message, time: now() })
  } finally {
    loading.value = false
    await scrollBottom()
  }
}

async function scrollBottom() {
  await nextTick()
  if (messagesEl.value) {
    messagesEl.value.scrollTop = messagesEl.value.scrollHeight
  }
}
</script>

<style scoped>
.chat-layout  { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }

/* 顶栏 */
.chat-topbar  { display: flex; align-items: center; justify-content: space-between; padding: 12px 20px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
.chat-title   { font-family: var(--font-mono); font-weight: 700; font-size: 14px; color: var(--text-1); }
.topbar-controls { display: flex; align-items: center; gap: 12px; }
.engine-switch    { display: flex; align-items: center; gap: 8px; }
.engine-label     { font-size: 12px; color: var(--text-3); }
.engine-model     { font-size: 10px; opacity: .7; margin-left: 4px; }

/* 系统提示词 */
.system-prompt-bar { border-bottom: 1px solid var(--border); padding: 8px 20px; flex-shrink: 0; }
.sys-header  { display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 12px; color: var(--text-3); user-select: none; }
.sys-header:hover { color: var(--text-2); }
.sys-preview { flex: 1; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sys-arrow   { transition: transform .2s; }
.sys-arrow--open { transform: rotate(180deg); }
.sys-textarea { margin-top: 8px; }

/* 消息列表 */
.messages { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 16px; }

.empty-state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; color: var(--text-3); padding: 40px; }
.empty-icon  { font-size: 40px; opacity: .4; }
.empty-state p { font-size: 13px; }
.empty-hints { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 8px; }
.hint-chip {
  background: var(--bg-card); border: 1px solid var(--border); border-radius: 20px;
  padding: 6px 14px; font-size: 12px; cursor: pointer; color: var(--text-2);
  transition: all .15s;
}
.hint-chip:hover { border-color: var(--accent); color: var(--accent); }

.msg-row       { display: flex; gap: 10px; align-items: flex-start; }
.msg-row--user { flex-direction: row-reverse; }
.msg-avatar    { font-size: 20px; flex-shrink: 0; width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; }
.msg-bubble    { max-width: 75%; border-radius: 10px; padding: 10px 14px; }
.bubble-user   { background: var(--accent-dim); border: 1px solid rgba(0,212,170,.2); }
.bubble-ai     { background: var(--bg-card); border: 1px solid var(--border); }
.msg-meta      { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.msg-role      { font-size: 11px; font-weight: 700; color: var(--accent); font-family: var(--font-mono); }
.msg-time      { font-size: 10px; color: var(--text-3); }
.msg-content   { font-family: var(--font-mono); font-size: 13px; color: var(--text-1); white-space: pre-wrap; word-break: break-word; line-height: 1.65; margin: 0; }

.typing-dots { display: flex; gap: 5px; padding: 4px 0; }
.typing-dots span {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--accent);
  animation: blink 1.2s infinite;
}
.typing-dots span:nth-child(2) { animation-delay: .2s; }
.typing-dots span:nth-child(3) { animation-delay: .4s; }
@keyframes blink {
  0%, 80%, 100% { opacity: .3; }
  40% { opacity: 1; }
}

/* 输入区 */
.input-area { display: flex; gap: 10px; padding: 14px 20px; border-top: 1px solid var(--border); flex-shrink: 0; }
.msg-input  { flex: 1; }
.send-btn   { align-self: flex-end; height: 36px; min-width: 60px; }
</style>
