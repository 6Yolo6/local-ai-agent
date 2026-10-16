# Local AI Agent 项目指引

## 当前结构

- backend：Java 17、Spring Boot 3.3.5、Spring AI MCP 1.0.0，127.0.0.1:5000。
- frontend：Vue 3、Vite、Element Plus，127.0.0.1:3000。
- router-layer：Express，127.0.0.1:5002，兼容 /api/agent 并转发 /api/assistant。
- 模型：Ollama / qwen3:1.7b、Q4_K_M、4096 上下文、think=false。默认不启动其他模型或 llama.cpp。
- scripts：Windows 本机环境检测、后台启动、健康检查、按 PID 与启动时间停止。
- .local/environment.json：不进 Git 的本机软件、模型与缓存路径。

仓库可移动。通用配置不要硬编码某台电脑的盘符、用户目录或软件安装目录；运行时用启动脚本和环境变量提供路径。

## 助手流程

Vue 发送 message 与 history → AssistantController → AssistantService → Ollama 原生 tool_calls → ReadOnlyTools → 结果回传 Ollama → 最终回答与 trace。

自主助手只允许 getSystemInfo、listFiles、readFile、readLastLines；不得为了修复模型行为改成任意命令执行、直接写文件或自动发送消息。保持工作区边界、敏感文件限制、链接检查、单并发、上下文预算和执行次数/时限。新增工具必须补对应的行为测试。

旧 MCP 工具通过 McpToolsConfig 中 MethodToolCallbackProvider 注册，提供 SSE /sse 和消息端点 /mcp/message。旧 MCP 含写文件、执行命令和社群工具，不能描述为只读。不要将服务默认暴露到外网，不要在测试中发布真实内容。

## 验证与运行

先读 README.md 和 docs/local-setup.md。使用 scripts/local-common.ps1 获取本机的 Java/Maven/Node/pnpm 路径，不复用原电脑路径。

- 后端：Maven verify，缓存由本机配置指定。
- 前端：pnpm install --frozen-lockfile，pnpm build。
- Router：npm ci，npm test。
- 启动：scripts/start-all.bat；后端更新时先 stop-local.ps1 -Service backend，再 start-all.bat -Build。
- 停止：scripts/stop-all.bat，仅结束受管理的本项目进程。
- 实机验收：真实模型对话、真实工具调用、多轮追问、模型不可用/越界读取等失败路径、监听地址与显存占用。

使用 rg 搜索并排除 node_modules、target、dist、.git；批量结构化修改优先 Python/Node。不要提交 .local、logs、模型文件或依赖缓存。QQ/飞书/OpenClaw 属于可选集成，本机默认不启动。
