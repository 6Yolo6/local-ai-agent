<template>
  <div class="app-shell" :class="{ dark: true }">
    <!-- ── 左侧导航栏 ── -->
    <aside class="sidebar">
      <div class="logo">
        <span class="logo-icon">⬡</span>
        <div class="logo-text">
          <span class="logo-title">Local AI</span>
          <span class="logo-sub">只读查询助手</span>
        </div>
      </div>

      <!-- 服务状态指示灯 -->
      <div class="status-bar">
        <StatusDot label="Ollama"    :online="store.isOllamaOnline" />
        <StatusDot label="MCP"       :online="store.isServerOnline" />
      </div>

      <!-- 导航菜单 -->
      <nav class="nav-menu">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          active-class="nav-item--active"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <!-- 底部模型信息 -->
      <div class="sidebar-footer">
        <div class="model-badge">
          <span class="model-dot" :class="store.isOllamaOnline ? 'dot-green' : 'dot-red'" />
          <span class="model-name">{{ store.modelName }}</span>
        </div>
        <div class="model-badge model-badge--secondary">
          <span class="model-dot" :class="store.isOllamaOnline ? 'dot-green' : 'dot-gray'" />
          <span class="model-name">Ollama · 本机轻量模型</span>
        </div>
      </div>
    </aside>

    <!-- ── 主内容区 ── -->
    <main class="main-content">
      <RouterView v-slot="{ Component }">
        <Transition name="fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
  </div>
</template>

<script setup>
import { h, onMounted, onUnmounted } from 'vue'
import { useAgentStore } from '@/stores/useAgentStore.js'
import {
  DataAnalysis,
  ChatRound,
  Tools,
  Document,
  Setting,
} from '@element-plus/icons-vue'

const store = useAgentStore()

// 导航菜单定义
const navItems = [
  { to: '/',       label: '仪表盘',       icon: DataAnalysis   },
  { to: '/chat',   label: '查询对话',  icon: ChatRound      },
  { to: '/tools',  label: '只读工具',     icon: Tools          },
  { to: '/logs',   label: '执行日志',         icon: Document       },
  { to: '/config', label: '配置',         icon: Setting        },
]

// 定时刷新服务状态（每 15 秒）
let timer = null
onMounted(() => {
  store.refreshStatus()
  timer = setInterval(store.refreshStatus, 15000)
})
onUnmounted(() => clearInterval(timer))

// StatusDot 子组件（内联）
const StatusDot = {
  name: 'StatusDot',
  props: { label: String, online: Boolean },
  setup: (props) => () => h('div', { class: 'status-dot-item' }, [
    h('span', { class: ['status-dot-circle', props.online ? 'pulse-green' : 'dot-gray'] }),
    h('span', { class: 'status-dot-label' }, props.label),
    h('span', { class: ['status-dot-val', props.online ? 'text-green' : 'text-muted'] }, props.online ? 'ON' : '—')
  ])
}
</script>

<style>
/* ── CSS 变量 / 设计令牌 ── */
:root {
  --bg-deep:    #0d1117;
  --bg-card:    #161b22;
  --bg-hover:   #1c2333;
  --border:     #21262d;
  --accent:     #00d4aa;
  --accent-dim: rgba(0,212,170,.15);
  --text-1:     #e6edf3;
  --text-2:     #8b949e;
  --text-3:     #484f58;
  --green:      #3fb950;
  --red:        #f85149;
  --yellow:     #d29922;
  --sidebar-w:  220px;
  --font-mono:  'JetBrains Mono','Fira Code','Consolas',monospace;
  --font-ui:    'IBM Plex Sans','PingFang SC','Microsoft YaHei',sans-serif;
}

*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }

html, body {
  background: var(--bg-deep);
  color: var(--text-1);
  font-family: var(--font-ui);
  font-size: 14px;
  height: 100%;
  overflow: hidden;
}

/* ── App Shell ── */
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ── 侧边栏 ── */
.sidebar {
  width: var(--sidebar-w);
  min-width: var(--sidebar-w);
  background: var(--bg-card);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 16px 16px;
  border-bottom: 1px solid var(--border);
}
.logo-icon {
  font-size: 24px;
  color: var(--accent);
  line-height: 1;
}
.logo-text { display: flex; flex-direction: column; }
.logo-title { font-family: var(--font-mono); font-weight: 700; font-size: 15px; color: var(--text-1); letter-spacing: 1px; }
.logo-sub   { font-size: 11px; color: var(--text-3); font-family: var(--font-mono); }

/* 状态灯 */
.status-bar {
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.status-dot-item {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
}
.status-dot-circle {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}
.status-dot-label { color: var(--text-2); flex: 1; }
.status-dot-val   { font-family: var(--font-mono); font-size: 11px; }
.text-green { color: var(--green); }
.text-red   { color: var(--red); }
.text-muted { color: var(--text-2); }

.pulse-green {
  background: var(--green);
  box-shadow: 0 0 0 0 rgba(63,185,80,.6);
  animation: pulse 2s infinite;
}
.dot-red  { background: var(--red); }
.dot-gray { background: var(--text-3); }

@keyframes pulse {
  0%   { box-shadow: 0 0 0 0 rgba(63,185,80,.5); }
  70%  { box-shadow: 0 0 0 5px rgba(63,185,80,0); }
  100% { box-shadow: 0 0 0 0 rgba(63,185,80,0); }
}

/* 导航 */
.nav-menu {
  flex: 1;
  padding: 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: 6px;
  color: var(--text-2);
  text-decoration: none;
  font-size: 13px;
  transition: all .15s;
}
.nav-item:hover { background: var(--bg-hover); color: var(--text-1); }
.nav-item--active { background: var(--accent-dim); color: var(--accent); }
.nav-item .el-icon { font-size: 16px; }

/* 侧边栏底部 */
.sidebar-footer {
  padding: 12px 14px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.model-badge {
  display: flex;
  align-items: center;
  gap: 7px;
}
.model-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  display: inline-block;
}
.dot-green { background: var(--green); }
.model-name { font-family: var(--font-mono); font-size: 11px; color: var(--text-2); }
.model-badge--secondary .model-name { color: var(--text-3); }

/* ── 主内容 ── */
.main-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

/* ── 路由切换动画 ── */
.fade-enter-active, .fade-leave-active { transition: opacity .15s; }
.fade-enter-from, .fade-leave-to       { opacity: 0; }

/* ── Element Plus 暗色覆盖 ── */
.el-button--primary {
  background: var(--accent) !important;
  border-color: var(--accent) !important;
  color: #0d1117 !important;
  font-weight: 600;
}
.el-button--primary:hover {
  background: #00e8bb !important;
  border-color: #00e8bb !important;
}
.el-card {
  background: var(--bg-card) !important;
  border-color: var(--border) !important;
}
</style>
