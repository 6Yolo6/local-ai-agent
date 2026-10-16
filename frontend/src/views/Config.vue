<template>
  <div class="page-wrap">
    <h1 class="page-title">本机配置</h1>
    <section class="config-block">
      <h2>Ollama 轻量模型</h2>
      <p>当前助手通过本机 Ollama 运行轻量模型，适合 4 GB 显存环境。Router 将查询统一交给助手处理。</p>
      <dl><dt>推理引擎</dt><dd>{{ store.status.engine || 'ollama' }}</dd>
        <dt>当前模型</dt><dd>{{ store.modelName }}</dd>
        <dt>工作区</dt><dd>{{ store.status.workspaceRoot || '等待后端返回' }}</dd>
        <dt>工作模式</dt><dd>{{ store.status.assistantMode || 'read-only' }}</dd></dl>
    </section>
    <section class="config-block">
      <h2>启动与验证</h2>
      <p>在项目根目录运行 scripts/start-all.bat。模型、工作区和超时配置位于 backend/src/main/resources/application.yml。</p>
      <pre>Ollama           http://127.0.0.1:11434
后端健康检查      http://127.0.0.1:5000/api/health
助手查询接口      POST /api/assistant
可选 Router      POST http://127.0.0.1:5002/api/agent</pre>
    </section>
    <section class="config-block">
      <h2>只读查询范围</h2>
      <p>可用工具以“只读工具”页的实时清单为准。文件读取受工作区边界保护。每次模型和工具执行均可在对话回复与“执行日志”中核对。</p>
      <p>当前阶段支持本机查询，暂不启用文件写入、命令执行或社群消息发布。</p>
    </section>
    <section class="config-block">
      <h2>MCP 接入地址</h2>
      <pre>{{ store.status.mcpEndpoint }}</pre>
    </section>
  </div>
</template>

<script setup>
import { useAgentStore } from '@/stores/useAgentStore.js'
const store = useAgentStore()
</script>

<style scoped>
.page-wrap { height: 100vh; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 20px; }
.page-title { font: 700 20px var(--font-mono); color: var(--text-1); }
.config-block { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 18px; }
h2 { font-size: 14px; color: var(--accent); margin-bottom: 12px; }
p { font-size: 13px; line-height: 1.8; color: var(--text-2); margin-top: 8px; }
pre { margin-top: 12px; font: 12px/1.8 var(--font-mono); color: var(--text-1); white-space: pre-wrap; overflow-wrap: anywhere; }
dl { display: grid; grid-template-columns: 90px 1fr; gap: 12px; margin-top: 18px; font-size: 12px; }
dt { color: var(--text-2); } dd { overflow-wrap: anywhere; font-family: var(--font-mono); }
</style>
