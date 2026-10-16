<template>
  <div class="page-wrap">
    <div class="page-header">
      <h1 class="page-title">执行日志</h1>
      <div class="header-actions">
        <el-select v-model="kind" size="small" style="width:120px" aria-label="记录类型">
          <el-option label="全部类型" value="" /><el-option label="工具调用" value="tool" />
          <el-option label="模型请求" value="model" /><el-option label="错误" value="error" />
        </el-select>
        <el-button size="small" :loading="loading" @click="loadLogs">刷新</el-button>
      </div>
    </div>
    <p class="page-note">后端保留的最近 200 条真实执行记录；每 5 秒刷新。服务重启后记录清空。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="log-list">
      <div v-if="!filteredLogs.length" class="log-empty">{{ loading ? '正在读取…' : '暂无匹配的执行记录。发送一次查询后可在这里查看。' }}</div>
      <article v-for="line in filteredLogs" :key="line.id" class="log-entry">
        <div class="log-time">{{ formatTime(line.timestamp) }} · 请求 {{ line.requestId }}</div>
        <ExecutionTrace :trace="[line]" expanded />
      </article>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getTraces } from '@/api/index.js'
import ExecutionTrace from '@/components/ExecutionTrace.vue'
const logs = ref([])
const kind = ref('')
const loading = ref(false)
const error = ref('')
let timer
const filteredLogs = computed(() => logs.value.filter(l => !kind.value || (kind.value === 'error' ? l.status === 'error' : l.kind === kind.value)))
const formatTime = (value) => value ? new Date(value).toLocaleString('zh-CN') : ''
async function loadLogs() {
  if (loading.value) return
  loading.value = true
  try {
    const resp = await getTraces()
    if (resp.code !== 200 || !Array.isArray(resp.data)) throw new Error(resp.message || '执行记录读取失败')
    logs.value = resp.data
    error.value = ''
  } catch (e) {
    error.value = '读取失败：' + e.message
  } finally { loading.value = false }
}
onMounted(() => { loadLogs(); timer = setInterval(loadLogs, 5000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.page-wrap { height: 100vh; overflow: hidden; padding: 24px; display: flex; flex-direction: column; gap: 16px; }
.page-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.page-title { font: 700 20px var(--font-mono); color: var(--text-1); }
.header-actions { display: flex; gap: 8px; align-items: center; }
.page-note { font-size: 12px; color: var(--text-2); }
.log-list { flex: 1; overflow-y: auto; }
.log-entry { padding: 14px; margin-bottom: 10px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; }
.log-time { color: var(--text-2); font: 11px var(--font-mono); overflow-wrap: anywhere; }
.log-empty { color: var(--text-2); text-align: center; padding: 40px; }
</style>
