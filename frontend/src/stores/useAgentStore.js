import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getStatus } from '@/api/index.js'

export const useAgentStore = defineStore('agent', () => {
  // ── 状态 ──────────────────────────────────────────────────────
  const status = ref({
    server:    'unknown',
    llamacpp:  'unknown',
    ollama:    'unknown',
    llamaModel:  'Qwen3.6-35B-A3B-Q4_K_M',
    ollamaModel: 'qwen3:14b',
    mcpEndpoint: 'http://127.0.0.1:5000/mcp',
    timestamp: null
  })

  const loading    = ref(false)
  const lastRefresh = ref(null)

  // ── 计算属性 ──────────────────────────────────────────────────
  const isLlamaOnline  = computed(() => status.value.llamacpp === 'online')
  const isOllamaOnline = computed(() => status.value.ollama   === 'online')
  const isServerOnline = computed(() => status.value.server   === 'running')

  // ── Actions ───────────────────────────────────────────────────
  async function refreshStatus() {
    loading.value = true
    try {
      const resp = await getStatus()
      if (resp.code === 200) {
        status.value  = resp.data
        lastRefresh.value = new Date()
      }
    } catch (e) {
      console.warn('状态刷新失败:', e.message)
    } finally {
      loading.value = false
    }
  }

  return { status, loading, lastRefresh, isLlamaOnline, isOllamaOnline, isServerOnline, refreshStatus }
})
