<template>
  <div class="page-wrap">
    <header class="page-header">
      <h1 class="page-title">本地查询助手</h1>
      <el-button size="small" :loading="store.loading" @click="refresh">刷新</el-button>
    </header>
    <el-alert v-if="store.statusError" :title="'服务状态读取失败：' + store.statusError" type="error" :closable="false" />
    <section class="card-grid">
      <article class="stat-card">
        <div class="stat-top"><span>Ollama</span><span :class="store.isOllamaOnline ? 'online' : 'offline'">{{ store.isOllamaOnline ? '在线' : '未就绪' }}</span></div>
        <div class="stat-value">{{ store.modelName }}</div><p>本机轻量推理 · 11434</p>
      </article>
      <article class="stat-card">
        <div class="stat-top"><span>助手后端</span><span :class="store.isServerOnline ? 'online' : 'offline'">{{ store.isServerOnline ? '在线' : '未连接' }}</span></div>
        <div class="stat-value">只读查询</div><p>模型选择工具并返回执行记录 · 5000</p>
      </article>
      <article class="stat-card">
        <div class="stat-top"><span>可用工具</span><RouterLink to="/tools">查看清单</RouterLink></div>
        <div class="stat-value">{{ store.toolsLoaded ? store.tools.length : '—' }}</div><p>来自后端实际可用工具</p>
      </article>
    </section>
    <section class="panel">
      <h2>工作区</h2><p class="workspace">{{ store.status.workspaceRoot || '等待后端返回工作区路径' }}</p>
      <p class="note">文件查询限定在此目录内。对话支持连续追问，可展开执行记录查看实际读取结果。</p>
    </section>
    <section class="panel">
      <h2>快速查询</h2>
      <div class="quick-input-row">
        <el-input v-model="quickPrompt" maxlength="3000" placeholder="例如：列出工作区根目录的文件" @keyup.enter="sendQuick" />
        <el-button type="primary" :loading="chatLoading" :disabled="!quickPrompt.trim()" @click="sendQuick">发送</el-button>
      </div>
      <pre v-if="quickReply" class="reply-text">{{ quickReply }}</pre>
      <ExecutionTrace :trace="quickTrace" :request-id="requestId" />
      <RouterLink class="chat-link" to="/chat">打开完整对话 →</RouterLink>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAgentStore } from '@/stores/useAgentStore.js'
import { chat } from '@/api/index.js'
import ExecutionTrace from '@/components/ExecutionTrace.vue'
const store = useAgentStore()
const quickPrompt = ref('')
const quickReply = ref('')
const quickTrace = ref([])
const requestId = ref('')
const chatLoading = ref(false)
const refresh = () => { store.refreshStatus(); store.refreshTools() }
onMounted(store.refreshTools)
async function sendQuick() {
  if (!quickPrompt.value.trim() || chatLoading.value) return
  chatLoading.value = true
  quickReply.value = ''
  quickTrace.value = []
  requestId.value = ''
  try {
    const resp = await chat(quickPrompt.value.trim())
    const data = resp.data || {}
    quickReply.value = data.answer || data.error?.message || resp.message || '未收到回复'
    quickTrace.value = data.trace || []
    requestId.value = data.requestId || ''
  } catch (e) { quickReply.value = '请求失败：' + e.message }
  finally { chatLoading.value = false }
}
</script>

<style scoped>
.page-wrap { height: 100vh; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 24px; }
.page-header { display: flex; align-items: center; justify-content: space-between; }
.page-title { font: 700 20px var(--font-mono); color: var(--text-1); }
.card-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
.stat-card, .panel { padding: 18px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px; }
.stat-top { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; color: var(--text-2); }
.online { color: var(--green); } .offline { color: var(--yellow); }
.stat-value { font: 700 20px var(--font-mono); color: var(--text-1); margin: 18px 0 10px; overflow-wrap: anywhere; }
.stat-card p, .note { font-size: 12px; color: var(--text-2); line-height: 1.7; }
h2 { font-size: 13px; color: var(--text-2); margin-bottom: 14px; }
.workspace { font: 13px var(--font-mono); color: var(--accent); overflow-wrap: anywhere; margin-bottom: 10px; }
.quick-input-row { display: flex; gap: 8px; }
.reply-text { font: 13px/1.7 var(--font-mono); white-space: pre-wrap; overflow-wrap: anywhere; background: var(--bg-deep); margin-top: 16px; padding: 12px; border-radius: 6px; }
a { color: var(--accent); text-decoration: none; }
.chat-link { display: inline-block; margin-top: 16px; font-size: 12px; }
</style>
