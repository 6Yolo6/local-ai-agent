<template>
  <div class="chat-layout">
    <div class="chat-topbar">
      <div><span class="chat-title">本地查询助手</span><span class="model-label">Ollama · {{ store.modelName }}</span></div>
      <el-button size="small" :disabled="loading" @click="clearHistory">新对话</el-button>
    </div>
    <div class="scope-note">第一阶段 · 只读查询。可查询工作区文本文件与本机状态，执行详情会随回复展示。</div>
    <div class="messages" ref="messagesEl" aria-live="polite">
      <div v-if="!messages.length" class="empty-state">
        <div class="empty-icon">⬡</div>
        <p>用自然语言查询本机信息</p>
        <div class="empty-hints">
          <button v-for="hint in hints" :key="hint" class="hint-chip" @click="inputText = hint">{{ hint }}</button>
        </div>
      </div>
      <div v-for="(msg, i) in messages" :key="i" class="msg-row" :class="msg.role === 'user' ? 'msg-row--user' : 'msg-row--ai'">
        <div class="msg-avatar">{{ msg.role === 'user' ? '👤' : '🤖' }}</div>
        <div class="msg-bubble" :class="msg.role === 'user' ? 'bubble-user' : 'bubble-ai'">
          <div class="msg-meta">
            <span class="msg-role">{{ msg.role === 'user' ? '你' : (msg.model || '本地助手') }}</span>
            <span class="msg-time">{{ msg.time }}</span>
            <span v-if="msg.failed" class="error-label">未完成</span>
          </div>
          <pre class="msg-content">{{ msg.content }}</pre>
          <p v-if="msg.historyTrimmed" class="context-note">较早的对话已缩短，本次回答使用保留下来的上下文。</p>
          <p v-if="msg.evidenceTruncated" class="context-note">模型读取的内容已截断；可展开执行记录查看本次工具返回。</p>
          <ExecutionTrace :trace="msg.trace" :request-id="msg.requestId" :expanded="msg.failed" />
        </div>
      </div>
      <div v-if="loading" class="pending">正在查询与整理结果…</div>
    </div>
    <div class="input-area">
      <el-input v-model="inputText" type="textarea" maxlength="3000" show-word-limit :autosize="{ minRows: 2, maxRows: 6 }"
        placeholder="输入查询内容… (Ctrl+Enter 发送)" class="msg-input" @keydown.ctrl.enter.prevent="sendMessage" />
      <el-button type="primary" :loading="loading" :disabled="!inputText.trim()" class="send-btn" @click="sendMessage">发送</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { chat } from '@/api/index.js'
import { buildHistory } from '@/utils/conversation.mjs'
import { useAgentStore } from '@/stores/useAgentStore.js'
import ExecutionTrace from '@/components/ExecutionTrace.vue'

const store = useAgentStore()
const inputText = ref('')
const messages = ref([])
const loading = ref(false)
const messagesEl = ref(null)
const hints = ['列出工作区根目录的文件', '读取 AGENTS.md 并概括项目结构', '查看本机系统状态']
const clearHistory = () => { messages.value = [] }
const now = () => new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || loading.value) return
  // Only successful conversation turns become model context; UI errors and tool
  // output never become a fabricated assistant response in a later turn.
  const history = buildHistory(messages.value, text)
  const userMessage = { role: 'user', content: text, time: now(), context: false }
  messages.value.push(userMessage)
  inputText.value = ''
  loading.value = true
  await scrollBottom()
  try {
    const resp = await chat(text, history)
    const data = resp.data || {}
    const failed = resp.code !== 200 || data.status !== 'completed'
    userMessage.context = !failed
    messages.value.push({
      role: 'assistant', content: data.answer || data.error?.message || resp.message || '未收到回复',
      time: now(), model: data.model, trace: data.trace || [], requestId: data.requestId,
      context: !failed, failed, historyTrimmed: !!data.historyTrimmed, evidenceTruncated: !!data.evidenceTruncated
    })
  } catch (e) {
    messages.value.push({ role: 'assistant', content: '请求失败：' + e.message, time: now(), context: false, failed: true })
    inputText.value ||= text
  } finally {
    loading.value = false
    await scrollBottom()
  }
}

async function scrollBottom() {
  await nextTick()
  if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
}
</script>

<style scoped>
.chat-layout { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
.chat-topbar { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px; border-bottom: 1px solid var(--border); gap: 12px; }
.chat-title { font-weight: 700; color: var(--text-1); }
.model-label { font: 11px var(--font-mono); color: var(--text-2); margin-left: 14px; }
.scope-note { padding: 10px 20px; color: var(--text-2); font-size: 12px; border-bottom: 1px solid var(--border); line-height: 1.6; }
.messages { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 16px; }
.empty-state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; color: var(--text-2); padding: 30px; }
.empty-icon { font-size: 40px; color: var(--accent); }
.empty-hints { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; }
.hint-chip { background: var(--bg-card); border: 1px solid var(--border); border-radius: 20px; padding: 8px 14px; font-size: 12px; cursor: pointer; color: var(--text-2); }
.hint-chip:hover { border-color: var(--accent); color: var(--accent); }
.msg-row { display: flex; gap: 10px; align-items: flex-start; }
.msg-row--user { flex-direction: row-reverse; }
.msg-avatar { font-size: 20px; flex-shrink: 0; width: 32px; }
.msg-bubble { max-width: 85%; min-width: 100px; border-radius: 10px; padding: 12px 14px; }
.bubble-user { background: var(--accent-dim); border: 1px solid rgba(0,212,170,.2); }
.bubble-ai { background: var(--bg-card); border: 1px solid var(--border); }
.msg-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 6px; }
.msg-role { font: 700 11px var(--font-mono); color: var(--accent); }
.msg-time { font-size: 10px; color: var(--text-2); }
.msg-content { font: 13px/1.7 var(--font-mono); color: var(--text-1); white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; }
.context-note { font-size: 12px; line-height: 1.5; color: var(--text-2); margin-top: 8px; }
.error-label { color: var(--red); font-size: 11px; }
.pending { padding: 12px; font-size: 12px; color: var(--accent); }
.input-area { display: flex; gap: 10px; padding: 14px 20px; border-top: 1px solid var(--border); flex-shrink: 0; }
.msg-input { flex: 1; }
.send-btn { align-self: flex-end; height: 36px; }
@media (max-width: 700px) { .model-label { display: block; margin: 6px 0 0; } .msg-bubble { max-width: 90%; } }
</style>
