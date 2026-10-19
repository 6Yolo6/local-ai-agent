# Local AI Agent

适合 4GB 显存电脑的本地只读查询助手：Vue 管理界面 → Spring Boot 工具循环 → Ollama / Qwen3 1.7B。支持连续对话、读取工作区源码、查询系统信息，查询 GYING 片库与资源记录，以及查看真实模型与工具调用记录。

## 当前能力

- `POST /api/assistant` 接收问题与历史，模型按需调用工具，工具结果回传模型后生成最终回答。
- 查询对话可选择“本机查询”或“GYING 片库”，切换会清空历史。`scope=local`（默认）开放 `getSystemInfo`、`listFiles`、`readFile`、`readLastLines`；`scope=gying` 只开放 `searchMovies`、`getMovieDetails`，两套工具权限隔离。
- 工作区边界、参数检查、结果大小、超时、并发和调用次数均在服务端约束。GYING 模式仅查询本机片库的公开 GET 接口，附来源与查询时间，资源链接未实时核验。
- 日志页显示真实 trace。全局 trace 保存在内存，重启后清空；对话页展示本次请求的具体工具结果。
- Router 的旧 `POST /api/agent` 入口统一转发到同一个助手，不再额外加载分类模型。
- 保留 Spring AI MCP SSE 接口，以及旧开发/社群工具供手动接入；这些能力与自主只读助手分开。MCP 仍包含写文件、执行命令及外发工具，不能按只读接口对待。

RAG 文档索引、多租户 API Key/配额与机器人发布尚未实现。GYING 的接口与边界见 [docs/gying-integration.md](docs/gying-integration.md)。

## 本机启动

这台电脑的私有路径已保存到 `.local/environment.json`，不会提交 Git。

```powershell
# 在仓库根目录执行
.\scripts\start-all.bat
.\scripts\check-local.ps1
.\scripts\stop-all.bat

# 修改后端代码后先停后端，再执行测试、打包并启动
.\scripts\stop-local.ps1 -Service backend
.\scripts\start-all.bat -Build
```

- 管理界面：[http://127.0.0.1:3000](http://127.0.0.1:3000)
- 后端健康检查：[http://127.0.0.1:5000/api/health](http://127.0.0.1:5000/api/health)
- Router：[http://127.0.0.1:5002/health](http://127.0.0.1:5002/health)
- MCP SSE：`http://127.0.0.1:5000/sse`，消息端点 `/mcp/message`

所有服务默认仅监听回环地址。启动器在后台运行服务，日志写入 `logs/`，PID 和启动时间记录在 `.local/`；停止脚本仅停止与记录匹配的进程，不按进程名称批量结束其他程序。

受管理服务还使用实例 ID 和本机文件握手确认就绪、排空和退出。`stop-local.ps1 -Graceful -Services frontend,router,backend` 会等待在途请求完成，核对退出确认并验证端口已释放；失败时保留进程，不强制结束。`-CheckOnly` 仅预检，最后一行 JSON 包含 `runningServices` 与 `instances`。`start-local.ps1 -Services ...` 可恢复预检记录的服务子集。

Ollama 普通停止需要可验证的独立控制台。当前本机存量 Ollama 无此控制台，普通全量停止会在预检阶段拒绝；三个应用服务仍可单独平滑停止。只有已安排正常 Windows 重启时才使用 `-ForReboot`：先排空应用、确认无已连接客户端、卸载模型，再将仍在运行的 Ollama 留给 Windows 正常关机。该路径不会伪称 Ollama 已退出，也不会调用强制结束。

## 另一台 Windows 电脑

先安装 JDK 17+ 与 Node.js 20+，设置 `JAVA_HOME`，再执行：

```powershell
.\scripts\setup-local.ps1 -RuntimeRoot 'G:\local-ai-agent' -InstallTools
.\scripts\start-all.bat -Build -PullModel
```

请将 RuntimeRoot 换为该电脑空间充足的路径。安装器会侧装 Maven 3.9.9、pnpm 10.28.2 与 Ollama 0.11.11；下载 Maven/Ollama 后校验官方摘要，不修改系统 PATH 或替换原软件。Ollama 固定此版本用于本机现有 NVIDIA 驱动的 CUDA 12 支持。模型下载到 RuntimeRoot/models，依赖缓存到 RuntimeRoot/cache。

可通过 `-JavaHome`、`-NodeExe`、`-MavenHome`、`-OllamaExe`、`-PnpmExe` 指定已安装软件。旧 Ollama 0.5.x 不满足本项目 Qwen3 非思考模式的要求。

默认模型 `qwen3:1.7b`（Q4_K_M），上下文 4096、关闭 thinking、一次加载一个模型、单请求并发。无需安装旧配置中的 14B/35B 模型、llama.cpp、AnythingLLM 或机器人网关。

## 演示

在对话页依次提问：

1. “列出工作区根目录里的文件和目录。”
2. “读取 frontend/package.json，告诉我项目名称和 dev 启动命令。”
3. “你刚才读的是哪个文件？项目名称是什么？”

展开每条回答的调用记录，可检查实际使用的工具、参数、状态、耗时和结果。模型能力有限，文件是否存在及具体内容以工具结果为准。

```json
POST /api/assistant
{
  "message": "列出工作区根目录",
  "history": []
}
```

响应使用 `{code,message,data,timestamp}` 包装；`data` 包含 `answer`、`requestId`、`model`、`status`、`scope`、`trace`；GYING 查询成功后另有 `sources`，失败时含 `error`。调用方应检查业务 `code` 和 `data.error`。工具发现为 `GET /api/assistant/tools?scope=local` 或 `?scope=gying`，最近记录为 `GET /api/traces`。

## 开发验证

```powershell
. .\scripts\local-common.ps1
& $MavenExe "-Dmaven.repo.local=$MavenRepository" -f backend/pom.xml verify
& $LocalConfig.pnpmExe --dir frontend install --frozen-lockfile --store-dir (Join-Path $LocalConfig.cacheDir 'pnpm')
& $LocalConfig.pnpmExe --dir frontend build
& $NpmExe --prefix router-layer ci
& $NpmExe --prefix router-layer test
& $NodeExe --test scripts/lifecycle.test.cjs
```

新增自主工具需要同步只读 allowlist、JSON schema、参数与路径校验及测试；仅添加 MCP `@Tool` 不会自动赋予助手权限。旧 llama.cpp 引擎仅在 `LOCAL_AI_LLAMACPP_ENABLED=true` 时启用；普通模式下旧代码生成工具复用 Ollama。

本机环境、验收记录与恢复方法见 [docs/local-setup.md](docs/local-setup.md)。
