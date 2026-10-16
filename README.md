# 🤖 本地私有化 AI Agent +RAG + 多模型架构

> **完全离线 · 数据不出本机**  
> Spring Boot MCP + llama.cpp + Vue3 + QQ/飞书 Bot

---

## 架构总览

```
┌─────────────────────────────────────────────────────────┐
│                   用户侧                                 │
│  QQ 频道/私聊  ←→  go-cqhttp  ←→  /bot/qq/webhook       │
│  飞书群消息    ←→  Webhook    ←→  /bot/feishu/webhook    │
│  Admin UI     ←→  Vue3:3000  ←→  /api/*                 │
└─────────────────────┬───────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────┐
│              Spring Boot MCP Server  :5000               │
│                                                         │
│  ┌─────────────┐  ┌─────────────┐  ┌────────────────┐  │
│  │  DevTools   │  │ SystemTools │  │ CommunityTools │  │
│  │  (7 tools)  │  │  (4 tools)  │  │   (5 tools)    │  │
│  └──────┬──────┘  └──────┬──────┘  └───────┬────────┘  │
│         └────────────────┼──────────────────┘           │
│                          │ LlamaService                  │
└──────────────────────────┼──────────────────────────────┘
                           │ HTTP /v1/chat/completions
┌──────────────────────────▼──────────────────────────────┐
│        llama.cpp Server  :8080                           │
│        Qwen3.6-35B-A3B-Q4_K_M.gguf                      │
│        MoE 架构：35B 参数 / 3B 激活 · 全 CPU 可运行      │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Ollama  :11434  （独立，供 AnythingLLM / Open WebUI）   │
└─────────────────────────────────────────────────────────┘

ClaudeCode / OpenClaw ─────► MCP SSE :5000/mcp
```

## 模块说明

| 模块                        | 功能                           | 端口 / 接口         | 必须 |
| --------------------------- | ------------------------------ | ------------------- | ------ |
| **MCP 工具服务**            | 文件操作 / 系统操作 / 编译执行 | HTTP:5000           | ✅     |
| **Qwen3.6-35B (llama.cpp)** | 主力推理 / 文本生成 / 总结     | 127.0.0.1:8080      | ✅     |
| **Qwen3:14B (Ollama)**      | 备用推理 / RAG / 路由分类器    | 127.0.0.1:11434     | ⭕ 可选 |
| **Router Layer**            | 语义路由，自动分类任务         | 127.0.0.1:5002      | ⭕ 可选 |
| **Admin UI (Vue3)**         | 服务监控 / 对话测试            | 127.0.0.1:3000      | ✅     |
| **go-cqhttp**               | QQ Bot 接入                    | 127.0.0.1:5700      | ⭕ 可选 |
| **RAG / AnythingLLM**       | 本地知识检索、文档处理         | 按需启动            | ⭕ 可选 |

---

## 环境要求

| 软件 | 版本 | 路径 | 必须 |
|------|------|------|------|
| JDK | 17 | `D:\Dev\JDK17` | ✅ |
| Maven | 3.9+ | `D:\Dev\Maven\apache-maven-3.9.9` | ✅ |
| Node.js | 24 | `D:\Dev\NodeJs-24` | ✅ |
| llama.cpp | latest | `D:\llama_cpp` | ✅ |
| 模型文件 | GGUF | `E:\AIModel\gguf\` | ✅ |
| Ollama | latest | `D:\Ollama` | ⭕ 可选 |
| go-cqhttp | v1.x | 任意目录 | ⭕ QQ Bot 需要 |

**硬件推荐**：
- RAM：≥ 32 GB（Q4_K_M 约占 22 GB）
- CPU：8 核以上（线程数 = 物理核心数）
- 磁盘：SSD（影响模型加载速度）
- GPU：可选，设置 `-ngl` 参数卸载层数可大幅提速

---

## 快速开始

### 第一步：下载 llama.cpp

```
https://github.com/ggerganov/llama.cpp/releases
→ 下载 llama-b****-bin-win-avx2-x64.zip（或 CUDA 版）
→ 解压到 D:\Dev\llama.cpp\
→ 确认存在 D:\Dev\llama.cpp\llama-server.exe
```

### 第二步：放置模型文件

```
将 Qwen_Qwen3.6-35B-A3B-Q4_K_M.gguf 放到：
E:\AIModel\gguf\Qwen_Qwen3.6-35B-A3B-Q4_K_M.gguf
```

### 第三步：配置

编辑 `backend\src\main\resources\application.yml`，按需填写：
- `local-ai.feishu.webhook-url` — 飞书机器人 Webhook
- `local-ai.qq.guild-id` / `channel-id` — QQ 频道 ID
- `local-ai.llamacpp.timeout-seconds` — 推理超时（默认 180s）

### 第四步：一键启动

```bat
双击运行：scripts\start-all.bat
```

启动顺序（自动等待）：
1. llama.cpp 加载模型（~45s）
2. Ollama 启动（~3s）
3. Router Layer 启动（语义路由，端口 5002）
4. Spring Boot 编译+启动（~25s）
5. Vue3 启动（~15s）
6. 浏览器访问 http://localhost:3000

### 手动启动顺序（推荐）

避免端口冲突和启动混乱，按以下顺序启动：

1. **启动 llama.cpp（Qwen3.6-35B）**
   ```bat
   scripts\start-llamacpp.bat
   ```

2. **启动 Ollama（Qwen3:14B / AnythingLLM）**
   ```bat
   scripts\start-ollama.bat
   ```

3. **启动 Router Layer（可选，语义路由）**
   ```bat
   scripts\start-router.bat
   ```

4. **启动 Spring Boot MCP 工具服务**
   ```bat
   scripts\start-backend.bat
   ```

5. **启动前端**
   ```bat
   scripts\start-frontend.bat
   ```

> 推理服务先于 MCP Server 启动，确保路由和工具调用可用。

### 第五步：连接 ClaudeCode

```bash
# 项目根目录已有 .claude/settings.json，直接使用：
claude  # 在 D:\Dev\project\local-ai-agent\ 目录运行

# 验证 MCP 连接
# ClaudeCode 会自动发现并连接 http://127.0.0.1:5000/mcp
```

> [!NOTE]
> **OpenClaw Gateway（可选）**：OpenClaw Gateway 是独立的外部项目（`D:\OpenClaw\openclaw-cn`），不在这个仓库中。
> 如果需要 OpenClaw 任务编排功能，请单独安装 OpenClaw：
> ```bat
> cd D:\OpenClaw\openclaw-cn
> pnpm openclaw configure
> pnpm openclaw gateway --allow-unconfigured
> ```

---

## Router Layer（可选）

Router Layer 是一个语义路由服务，位于 `router-layer/`，监听端口 **5002**：

```
用户输入 → Ollama qwen2.5-1.5b 分类器 → 分类为 chat/reasoning/code/rag/tool
  → chat        → Ollama :11434（快速问答）
  → reasoning   → llama.cpp :8080（深度推理）
  → code/tool   → MCP Server :5000（代码生成/文件操作）
  → rag         → AnythingLLM :5001（知识检索）
```

- `router.json` 定义分类器模型和路由表
- `server.js` 基于 Express，提供 `POST /api/agent` 接口
- 启动方式：`scripts\start-router.bat`

---

## 配置 ClaudeCode 接 Ollama（可选）

如需 ClaudeCode 本身也使用本地模型推理（而非 Anthropic 云端），
需要在 Ollama 和 ClaudeCode 之间加一个 API 格式转换层：

```powershell
# 方案：使用 LiteLLM 代理（将 Anthropic 格式转为 Ollama/OpenAI 格式）
pip install litellm

# 启动代理（将 Anthropic API 请求转发给 Ollama）
litellm --model ollama/qwen3:14b --port 4000

# 设置环境变量后启动 ClaudeCode
$env:ANTHROPIC_BASE_URL = "http://localhost:4000"
$env:ANTHROPIC_API_KEY  = "fake-key"
claude
```

> ⚠ 注意：本项目 MCP Server 的 AI 推理（DevTools/SystemTools 等）
> 固定使用 llama.cpp，不受此环境变量影响。
> 此设置仅影响 ClaudeCode 自身的对话推理。

---

## 服务接口速查

| 接口 | 方法 | 说明 |
|------|------|------|
| `http://127.0.0.1:5000/mcp` | SSE | MCP 工具连接端点 |
| `http://127.0.0.1:5000/api/status` | GET | 服务状态 JSON |
| `http://127.0.0.1:5000/api/chat` | POST | 直接对话（测试用）|
| `http://127.0.0.1:5000/api/health` | GET | 健康检查 |
| `http://127.0.0.1:5000/api/metrics` | GET | llama.cpp 推理指标 |
| `http://127.0.0.1:5000/bot/qq/webhook` | POST | go-cqhttp 上报 |
| `http://127.0.0.1:5000/bot/feishu/webhook` | POST | 飞书事件订阅 |
| `http://127.0.0.1:8080/health` | GET | llama.cpp 状态 |
| `http://127.0.0.1:8080/v1/chat/completions` | POST | llama.cpp OpenAI API |
| `http://127.0.0.1:11434/api/tags` | GET | Ollama 模型列表 |

---

## QQ Bot 配置（go-cqhttp）

1. 下载 go-cqhttp：https://github.com/Mrs4s/go-cqhttp/releases
2. 配置 `config.yml`：
```yaml
servers:
  - http:
      host: 127.0.0.1
      port: 5700
      post:
        - url: http://127.0.0.1:5000/bot/qq/webhook
          secret: ''
```
3. 在 QQ 群/频道 发送 `/ai 你的问题` 触发 Agent

---

## 飞书 Bot 配置

1. 飞书开放平台 → 自建应用 → 添加机器人
2. 事件订阅 → 配置地址（需要公网 IP 或 ngrok）：
   `http://你的IP:5000/bot/feishu/webhook`
3. 或使用自定义机器人 Webhook（推荐，无需公网）：
   群设置 → 机器人 → 添加 → 复制 Webhook URL → 填入 `application.yml`

---

## 常见问题

**Q: llama.cpp 加载后推理很慢？**  
A: Q4_K_M 模型首 Token 生成约 5~15s（CPU 推理）。如有 NVIDIA 显卡，
   修改 `start-llamacpp.bat` 中的 `GPU_LAYERS=20`（每层约 200MB 显存）。

**Q: 编译时 Maven 下载依赖很慢？**  
A: 在 `D:\Dev\Maven\conf\settings.xml` 添加阿里云镜像：
```xml
<mirror>
  <id>aliyun</id>
  <mirrorOf>central</mirrorOf>
  <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

**Q: npm install 很慢？**  
A: `npm config set registry https://registry.npmmirror.com`

**Q: Spring AI MCP 工具没有被注册？**  
A: 确认 `McpToolsConfig.java` 中 `MethodToolCallbackProvider.builder()` 包含了所有工具类实例。
   查看启动日志中是否有 `[MCP] 注册工具集` 的输出。

**Q: 如何新增自定义 MCP 工具？**  
A: 在任意 `@Service` 类的方法上加 `@Tool(description = "...")` 注解，
   然后在 `McpToolsConfig` 的 `MethodToolCallbackProvider.builder().toolObjects(...)` 中添加该 Bean。

---

## 技术栈

| 层 | 技术 |
|----|------|
| 后端框架 | Spring Boot 3.3.5 |
| MCP 协议 | Spring AI 1.0.0 MCP Server (WebMVC) |
| 推理引擎 | llama.cpp（HTTP 直连，OkHttp 4.12） |
| 本地模型 | Qwen3.6-35B-A3B-Q4_K_M.gguf（MoE 架构）|
| HTTP 客户端 | OkHttp 4.12.0 |
| 定时任务 | Spring @Scheduled + Quartz |
| 前端框架 | Vue 3.4 + Vite 5 |
| UI 组件库 | Element Plus 2.7 |
| 状态管理 | Pinia 2.1 |
| 代码简化 | Lombok |
| Java 版本 | JDK 17 |
| 语义路由 | Express.js + Ollama（router-layer/）|
