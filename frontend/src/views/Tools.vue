<template>
  <div class="page-wrap">
    <div class="page-header">
      <h1 class="page-title">MCP 工具集</h1>
      <el-tag type="success" size="small">16 个工具已注册</el-tag>
    </div>

    <div class="tool-groups">
      <div v-for="group in toolGroups" :key="group.cat" class="tool-group">
        <div class="group-header">
          <span class="group-dot" :style="{background: group.color}" />
          <span class="group-name">{{ group.label }}</span>
        </div>
        <div class="tool-list">
          <div v-for="t in group.tools" :key="t.name" class="tool-row">
            <div class="tool-info">
              <span class="tool-name">{{ t.name }}</span>
              <span class="tool-desc">{{ t.desc }}</span>
            </div>
            <div class="tool-params">
              <el-tag v-for="p in t.params" :key="p" size="small" effect="plain" class="param-tag">{{ p }}</el-tag>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
const toolGroups = [
  {
    cat: 'DEV', label: '开发类工具', color: '#1f6feb',
    tools: [
      { name: 'generateCode',   desc: '根据需求生成 Java/Vue3/SQL 代码（调用 Qwen3 本地推理）', params: ['language','requirement','context'] },
      { name: 'readFile',       desc: '读取本地文件内容，支持绝对/相对路径', params: ['filePath'] },
      { name: 'writeFile',      desc: '写入/追加文件内容，自动创建父目录', params: ['filePath','content','append'] },
      { name: 'listFiles',      desc: '列出目录内容（文件和子目录）', params: ['dirPath'] },
      { name: 'deleteFile',     desc: '删除文件（空目录），拒绝非空目录（安全保护）', params: ['filePath'] },
      { name: 'optimizeSql',    desc: 'AI 分析 SQL 性能问题并提供优化建议', params: ['sql','tableStructure'] },
      { name: 'compileProject', desc: '执行 Maven/npm 编译命令（支持 pom.xml 和 package.json 项目）', params: ['projectPath','command'] },
    ]
  },
  {
    cat: 'SYS', label: '系统类工具', color: '#388bfd',
    tools: [
      { name: 'executeCommand', desc: 'Windows 命令执行（内置危险命令黑名单保护）', params: ['command','workingDir'] },
      { name: 'parseLog',       desc: 'AI 读取日志文件并分析错误/异常，给出修复建议', params: ['logFilePath','maxLines'] },
      { name: 'readLastLines',  desc: '读取文件末尾 N 行（类似 Linux tail 命令）', params: ['filePath','lineCount'] },
      { name: 'getSystemInfo',  desc: '获取 JVM 内存、CPU、关键服务运行状态', params: [] },
    ]
  },
  {
    cat: 'COM', label: '社群类工具', color: '#3fb950',
    tools: [
      { name: 'postToQQChannel',  desc: 'QQ 频道发帖（需 go-cqhttp）', params: ['content','channelId'] },
      { name: 'sendQQMessage',    desc: 'QQ 私聊或群消息发送', params: ['targetId','messageType','content'] },
      { name: 'pushToFeishu',     desc: '飞书 Webhook 机器人消息推送（支持 Markdown）', params: ['title','content'] },
      { name: 'generatePost',     desc: 'AI 生成社群帖子内容（可指定风格和平台）', params: ['topic','style','platform'] },
      { name: 'notifyResult',     desc: 'Agent 任务完成后回传结果到手机', params: ['channel','targetId','result'] },
    ]
  }
]
</script>

<style scoped>
.page-wrap { height: 100vh; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 20px; }
.page-header { display: flex; align-items: center; gap: 12px; }
.page-title  { font-family: var(--font-mono); font-size: 20px; font-weight: 700; color: var(--text-1); }
.tool-groups { display: flex; flex-direction: column; gap: 20px; }
.tool-group  { background: var(--bg-card); border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
.group-header{ display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--border); }
.group-dot   { width: 8px; height: 8px; border-radius: 50%; }
.group-name  { font-family: var(--font-mono); font-size: 13px; font-weight: 700; color: var(--text-1); }
.tool-list   { display: flex; flex-direction: column; }
.tool-row    { padding: 12px 16px; border-bottom: 1px solid var(--border); display: flex; align-items: center; gap: 16px; }
.tool-row:last-child { border-bottom: none; }
.tool-info   { flex: 1; display: flex; flex-direction: column; gap: 3px; }
.tool-name   { font-family: var(--font-mono); font-size: 13px; color: var(--accent); }
.tool-desc   { font-size: 12px; color: var(--text-3); }
.tool-params { display: flex; flex-wrap: wrap; gap: 4px; }
.param-tag   { font-family: var(--font-mono); font-size: 11px !important; }
</style>
