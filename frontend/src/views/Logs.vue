<template>
  <div class="page-wrap">
    <div class="page-header">
      <h1 class="page-title">实时日志</h1>
      <div class="header-actions">
        <el-select v-model="level" size="small" style="width:100px">
          <el-option label="ALL"  value="" />
          <el-option label="ERROR" value="ERROR" />
          <el-option label="WARN"  value="WARN" />
          <el-option label="INFO"  value="INFO" />
        </el-select>
        <el-button size="small" @click="loadLogs">刷新</el-button>
        <el-button size="small" @click="logs = []">清空</el-button>
      </div>
    </div>
    <div class="log-terminal" ref="termEl">
      <div v-if="!logs.length" class="log-empty">— 暂无日志，点击刷新从后端读取 —</div>
      <div v-for="(line, i) in filteredLogs" :key="i" class="log-line" :class="levelClass(line)">
        <span class="log-text">{{ line }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import api from '@/api/index.js'

const logs    = ref([])
const level   = ref('')
const termEl  = ref(null)

const filteredLogs = computed(() =>
  level.value ? logs.value.filter(l => l.includes(` ${level.value} `)) : logs.value
)

function levelClass(line) {
  if (line.includes(' ERROR ')) return 'log-error'
  if (line.includes(' WARN '))  return 'log-warn'
  if (line.includes(' DEBUG ')) return 'log-debug'
  return ''
}

async function loadLogs() {
  try {
    const resp = await api.get('/status') // 暂用 status 接口测试，实际可扩展为 /logs
    logs.value.push(`[${new Date().toLocaleTimeString()}] INFO  服务状态: ${JSON.stringify(resp.data)}`)
    await nextTick()
    if (termEl.value) termEl.value.scrollTop = termEl.value.scrollHeight
  } catch (e) {
    logs.value.push(`[${new Date().toLocaleTimeString()}] ERROR ${e.message}`)
  }
}
</script>

<style scoped>
.page-wrap   { height: 100vh; overflow: hidden; padding: 24px; display: flex; flex-direction: column; gap: 16px; }
.page-header { display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; }
.page-title  { font-family: var(--font-mono); font-size: 20px; font-weight: 700; color: var(--text-1); }
.header-actions { display: flex; gap: 8px; align-items: center; }
.log-terminal {
  flex: 1; background: #010409; border: 1px solid var(--border); border-radius: 8px;
  padding: 14px; overflow-y: auto; font-family: var(--font-mono); font-size: 12px;
  line-height: 1.7;
}
.log-empty  { color: var(--text-3); text-align: center; padding: 40px; }
.log-line   { color: var(--text-2); white-space: pre-wrap; word-break: break-all; }
.log-error  { color: #f85149; }
.log-warn   { color: #d29922; }
.log-debug  { color: var(--text-3); }
</style>
