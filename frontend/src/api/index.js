import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 240000,
  headers: { 'Content-Type': 'application/json' }
})

// Preserve structured assistant errors so callers can display the real trace.
api.interceptors.response.use(
  (resp) => resp.data,
  (error) => {
    if (error.response?.data?.data?.trace) return error.response.data
    return Promise.reject(new Error(error.response?.data?.message || error.message || '请求失败'))
  }
)

export const getStatus = () => api.get('/status')
export const getModels = () => api.get('/models')
export const getTools = () => api.get('/assistant/tools')
export const getTraces = () => api.get('/traces')
export const chat = (message, history = []) => api.post('/assistant', { message, history })

export default api
