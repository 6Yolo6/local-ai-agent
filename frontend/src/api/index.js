import axios from 'axios'
import { ElMessage } from 'element-plus'

// Axios 实例（vite proxy 转发到 http://127.0.0.1:5000）
const api = axios.create({
  baseURL: '/api',
  timeout: 180000,  // 模型推理最长 3 分钟
  headers: { 'Content-Type': 'application/json' }
})

// 响应拦截器：统一错误提示
api.interceptors.response.use(
  (resp) => resp.data,
  (error) => {
    const msg = error.response?.data?.message || error.message || '请求失败'
    ElMessage.error(`接口错误：${msg}`)
    return Promise.reject(error)
  }
)

// ── 服务状态 ──────────────────────────────────────────────────

/** 获取所有服务状态 */
export const getStatus  = () => api.get('/status')
/** 获取模型列表 */
export const getModels  = () => api.get('/models')
/** 获取 llama.cpp 推理指标 */
export const getMetrics = () => api.get('/metrics')

// ── 对话 ──────────────────────────────────────────────────────

/**
 * 发送对话请求
 * @param {string} prompt  用户输入
 * @param {string} system  系统提示词（可选）
 * @param {string} engine  推理引擎：'llama'（默认）或 'ollama'
 */
export const chat = (prompt, system = '', engine = 'llama') =>
  api.post('/chat', { prompt, system, engine })

export default api
