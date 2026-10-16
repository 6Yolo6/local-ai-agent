import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/',        name: 'Dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '仪表盘' } },
  { path: '/chat',    name: 'Chat',      component: () => import('@/views/Chat.vue'),      meta: { title: '本地查询助手' } },
  { path: '/tools',   name: 'Tools',     component: () => import('@/views/Tools.vue'),     meta: { title: '只读工具' } },
  { path: '/logs',    name: 'Logs',      component: () => import('@/views/Logs.vue'),      meta: { title: '日志' } },
  { path: '/config',  name: 'Config',    component: () => import('@/views/Config.vue'),    meta: { title: '配置' } },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.afterEach((to) => {
  document.title = `${to.meta.title || ''} · Local AI Agent`
})

export default router
