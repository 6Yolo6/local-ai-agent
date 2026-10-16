import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: { '@': resolve(__dirname, 'src') }
  },

  server: {
    host: '127.0.0.1',
    port: 3000,
    proxy: {
      // 所有 /api 和 /bot 请求代理到 Spring Boot MCP 服务
      '/api': {
        target: 'http://127.0.0.1:5000',
        changeOrigin: true
      },
      '/bot': {
        target: 'http://127.0.0.1:5000',
        changeOrigin: true
      }
    }
  },

  build: {
    outDir: 'dist',
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'vendor':        ['vue', 'vue-router', 'pinia', 'axios']
        }
      }
    }
  }
})
