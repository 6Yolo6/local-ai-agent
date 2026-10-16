<template>
  <div class="page-wrap">
    <div class="page-header">
      <h1 class="page-title">只读工具</h1>
      <el-tag size="small">{{ store.toolsLoaded ? store.tools.length + ' 个可用工具' : '正在读取清单' }}</el-tag>
      <el-button size="small" @click="store.refreshTools">刷新</el-button>
    </div>
    <p class="page-note">清单由后端实时提供。助手根据查询自动选择工具，文件访问范围由工作区配置限定。</p>
    <el-alert v-if="store.toolsError" :title="store.toolsError" type="error" :closable="false" />
    <div v-if="store.toolsLoaded && !store.tools.length" class="page-note">当前没有可用工具。</div>
    <div class="tool-list">
      <article v-for="tool in store.tools" :key="tool.name" class="tool-row">
        <div class="tool-name">{{ tool.name }}</div>
        <p class="tool-desc">{{ tool.description }}</p>
        <details><summary>参数定义</summary><pre>{{ formatParameters(tool.parameters) }}</pre></details>
      </article>
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useAgentStore } from '@/stores/useAgentStore.js'
const store = useAgentStore()
const formatParameters = (value) => {
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
  }
  return JSON.stringify(value || {}, null, 2)
}
onMounted(store.refreshTools)
</script>

<style scoped>
.page-wrap { height: 100vh; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 20px; }
.page-header { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.page-title { font: 700 20px var(--font-mono); color: var(--text-1); }
.page-note { color: var(--text-2); font-size: 12px; line-height: 1.7; }
.tool-list { display: flex; flex-direction: column; gap: 12px; }
.tool-row { padding: 16px; background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; }
.tool-name { font: 700 13px var(--font-mono); color: var(--accent); }
.tool-desc { margin: 8px 0 12px; font-size: 13px; color: var(--text-2); }
summary { font-size: 12px; color: var(--text-2); cursor: pointer; }
pre { font: 12px/1.6 var(--font-mono); color: var(--text-1); white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 10px; }
</style>
