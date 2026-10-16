import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getStatus, getTools } from '@/api/index.js'

export const useAgentStore = defineStore('agent', () => {
  const status = ref({
    server: 'unknown', ollama: 'unknown', engine: 'ollama',
    model: '', ollamaModel: '', assistantMode: 'read-only',
    workspaceRoot: '', mcpEndpoint: 'http://127.0.0.1:5000/sse'
  })
  const tools = ref([])
  const toolsLoaded = ref(false)
  const toolsError = ref('')
  const statusError = ref('')
  const loading = ref(false)
  const lastRefresh = ref(null)
  const isOllamaOnline = computed(() => status.value.ollama === 'online')
  const isServerOnline = computed(() => status.value.server === 'running')
  const modelName = computed(() => status.value.model || status.value.ollamaModel || '等待模型信息')

  async function refreshStatus() {
    if (loading.value) return
    loading.value = true
    try {
      const resp = await getStatus()
      if (resp.code !== 200) throw new Error(resp.message || '状态读取失败')
      status.value = resp.data
      statusError.value = ''
      lastRefresh.value = new Date()
    } catch (e) {
      status.value = { ...status.value, server: 'unknown', ollama: 'unknown' }
      statusError.value = e.message
    } finally {
      loading.value = false
    }
  }

  async function refreshTools() {
    try {
      const resp = await getTools()
      if (resp.code !== 200 || !Array.isArray(resp.data)) throw new Error(resp.message || '工具清单读取失败')
      tools.value = resp.data
      toolsLoaded.value = true
      toolsError.value = ''
    } catch (e) {
      toolsError.value = e.message
    }
  }

  return { status, tools, toolsLoaded, toolsError, statusError, loading, lastRefresh,
    isOllamaOnline, isServerOnline, modelName, refreshStatus, refreshTools }
})
