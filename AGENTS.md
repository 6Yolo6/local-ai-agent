# 本地私有化 AI Agent — 项目上下文

> 此文件供 **Codex** 和 **OpenClaw** 读取，提供项目结构、工具清单、开发约定。  
> 路径：`D:\Dev\project\local-ai-agent\AGENTS.md`

---

## 项目概览

| 组件 | 技术 | 端口 | 用途 |
|------|------|------|------|
| **MCP Server** | Spring Boot 3.3 + Spring AI 1.0 | 5000 | 暴露 MCP 工具给 Codex/OpenClaw |
| **主力推理** | llama.cpp + Qwen3.6-35B-A3B-Q4_K_M | 8080 | Agent 代码生成/分析/社群内容 |
| **备用推理** | Ollama + qwen3:14b | 11434 | AnythingLLM / Open WebUI |
| **Admin UI** | Vue3 + Vite + Element Plus | 3000 | 服务监控/对话测试 |
| **QQ Bot** | go-cqhttp OneBot11 | 5700→5000 | 接收 QQ 指令，推送结果 |
| **飞书 Bot** | Webhook | 公网→5000 | 接收飞书指令，推送结果 |
| **Router Layer** | Express.js + Ollama 分类器 | 5002 | 语义路由，自动分发到不同后端 |

**完整执行流**：
```
QQ/飞书 指令
  → OpenClaw 任务拆解
  → Codex 调用 MCP 工具（代码生成/文件操作/编译）
  → LlamaService 调用 llama.cpp 推理
  → CommunityTools 回传结果到 QQ/飞书
```

---

## 目录结构

```
D:\Dev\project\local-ai-agent\
├── backend\                          # Spring Boot MCP 服务端
│   ├── pom.xml                       # Maven 依赖（Spring AI 1.0.0）
│   └── src\main\
│       ├── java\com\localai\agent\
│       │   ├── LocalAiAgentApplication.java    # 启动类
│       │   ├── config\
│       │   │   ├── McpToolsConfig.java         # ★ MCP 工具注册入口
│       │   │   └── WebConfig.java              # CORS 配置
│       │   ├── mcp\
│       │   │   ├── dev\DevTools.java           # ★ 开发类工具（7个）
│       │   │   ├── system\SystemTools.java     # ★ 系统类工具（4个）
│       │   │   └── community\CommunityTools.java # ★ 社群类工具（5个）
│       │   ├── ollama\
│       │   │   ├── LlamaService.java           # ★ 主力推理（llama.cpp）
│       │   │   └── OllamaService.java          # 备用推理（Ollama）
│       │   ├── bot\
│       │   │   ├── qq\QQBotService.java        # QQ 事件处理
│       │   │   └── feishu\FeishuBotService.java # 飞书事件处理
│       │   ├── controller\AdminController.java  # REST API 控制器
│       │   └── model\ApiResponse.java          # 统一响应体
│       └── resources\application.yml           # ★ 配置文件
├── frontend\                         # Vue3 管理后台
│   ├── src\views\
│   │   ├── Dashboard.vue             # 服务状态仪表盘
│   │   ├── Chat.vue                  # 对话 Playground
│   │   ├── Tools.vue                 # MCP 工具清单
│   │   ├── Logs.vue                  # 日志查看
│   │   └── Config.vue                # 配置说明
│   └── vite.config.js                # /api → port 5000 代理
├── scripts\
│   ├── start-all.bat                 # ★ 一键启动（推荐）
│   ├── start-llamacpp.bat            # 启动 llama.cpp
│   ├── start-ollama.bat              # 启动 Ollama（AnythingLLM用）
│   ├── start-backend.bat             # 启动 Spring Boot
│   ├── start-frontend.bat            # 启动 Vue3
│   ├── start-router.bat              # 启动 Router Layer（语义路由）
│   └── stop-all.bat                  # 停止所有服务
├── router-layer\                     # ★ 语义路由层
│   ├── server.js                     # 语义分类 + 路由到后端（端口 5002）
│   └── router.json                   # 路由规则配置
└── .Codex\
    └── settings.json                 # ★ Codex MCP 连接配置
└── AGENTS.md                         # 本文件
```

---

## MCP 工具参考

### 开发类（DevTools）

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `generateCode` | `language, requirement, context` | Qwen3 生成 Java/Vue3/SQL 代码 |
| `readFile` | `filePath` | 读取文件（绝对/相对路径均可）|
| `writeFile` | `filePath, content, append` | 写入文件，自动建目录 |
| `listFiles` | `dirPath` | 列出目录内容 |
| `deleteFile` | `filePath` | 删除文件（空目录），有安全保护 |
| `optimizeSql` | `sql, tableStructure` | AI 分析 SQL 性能并给出优化建议 |
| `compileProject` | `projectPath, command` | Maven/npm 编译，超时 5 分钟 |

### 系统类（SystemTools）

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `executeCommand` | `command, workingDir` | Windows cmd 执行，内置危险命令黑名单 |
| `parseLog` | `logFilePath, maxLines` | AI 读日志，分析错误/异常/修复建议 |
| `readLastLines` | `filePath, lineCount` | tail 功能，读末尾 N 行 |
| `getSystemInfo` | *(无)* | JVM 内存、CPU 核心数、OS 信息，Ollama 进程在线检查 |

### 社群类（CommunityTools）

| 工具名 | 参数 | 说明 |
|--------|------|------|
| `postToQQChannel` | `content, channelId` | QQ 频道发帖（需 go-cqhttp）|
| `sendQQMessage` | `targetId, messageType, content` | QQ 私聊/群消息 |
| `pushToFeishu` | `title, content` | 飞书 Webhook 推送（Markdown）|
| `generatePost` | `topic, style, platform` | AI 生成社群帖子内容 |
| `notifyResult` | `channel, targetId, result` | Agent 结果回传到手机 |

---

## Router Layer（语义路由）

Router Layer 是可选的语义路由层，位于 `router-layer/`：

```
用户输入 → Router Layer :5002（Ollama 分类器 qwen2.5-1.5b）→ 分类为 chat/reasoning/code/rag/tool
  → chat  → Ollama :11434（快速问答）
  → reasoning → llama.cpp :8080（深度推理）
  → code/tool → MCP Server :5000（代码生成/文件操作）
  → rag   → AnythingLLM :5001（知识检索）
```

- `router.json` 定义分类器和路由表
- `server.js` 基于 Express，提供 `POST /api/agent` 接口
- 启动方式：`scripts\start-router.bat`

---

## 开发约定

### 新增 MCP 工具

1. 在 `DevTools` / `SystemTools` / `CommunityTools` 中添加 `@Tool` 注解方法
2. 方法参数类型用 `String` / `int` / `boolean`（Spring AI 自动生成 JSON Schema）
3. 返回值统一用 `String`（`✅ 成功...` / `❌ 失败...` 前缀）
4. 无需手动注册，`McpToolsConfig` 中已通过 `MethodToolCallbackProvider` 自动扫描

```java
// 示例：新增一个工具
@Tool(description = "工具的清晰描述，供 AI 理解何时调用此工具")
public String myNewTool(String param1, int param2) {
    // 实现...
    return "✅ 执行结果";
}
```

### 调用 LlamaService

```java
@Autowired
private LlamaService llamaService;

// 单轮对话
String result = llamaService.chat("帮我生成...");

// 带角色设定的对话
String result = llamaService.chatWithSystem(
    "你是一个 Java 专家",   // 系统提示词
    "帮我写一个...");       // 用户输入
```

### 添加 REST API

在 `AdminController.java` 中添加 `@GetMapping` / `@PostMapping`，
返回类型统一用 `ApiResponse<T>`。

---

## 关键配置速查

```yaml
# application.yml 核心配置
server:
  port: 5000

local-ai:
  llamacpp:
    base-url: http://127.0.0.1:8080     # llama.cpp OpenAI 兼容 API
    model: local-model
    temperature: 0.7
    max-tokens: 4096
    timeout-seconds: 180                 # MoE 首 Token 较慢，建议 ≥120s
  ollama:
    base-url: http://127.0.0.1:11434    # Ollama（备用推理 / 路由分类器）
    model: qwen3:14b
    timeout-seconds: 120
  qq:
    cqhttp-url: http://127.0.0.1:5700    # go-cqhttp
    command-prefix: "/ai"
    guild-id: ""                         # QQ 频道 ID（需配置）
    channel-id: ""                       # QQ 子频道 ID（需配置）
    webhook-secret: ""                   # go-cqhttp 上报密钥
  feishu:
    webhook-url: ""                      # 飞书自定义机器人 Webhook
    app-id: ""                           # 飞书自建应用 ID
    app-secret: ""                       # 飞书自建应用密钥
    verify-token: ""                     # 飞书事件订阅验证
  file:
    workspace-root: D:/Dev/project/local-ai-agent
    upload-dir: D:/Dev/project/local-ai-agent/uploads
    log-dir: D:/Dev/project/local-ai-agent/logs
  llamacpp-install:
    home: D:/llama_cpp
    executable: D:/llama_cpp/llama-server.exe
    model-path: E:/AIModel/gguf/Qwen_Qwen3.6-35B-A3B-Q4_K_M.gguf
  maven:
    home: D:/Dev/Maven/apache-maven-3.9.9
    executable: D:/Dev/Maven/apache-maven-3.9.9/bin/mvn.cmd
  nodejs:
    home: D:/Dev/NodeJs-24
    executable: D:/Dev/NodeJs-24/node.exe
    npm-executable: D:/Dev/NodeJs-24/npm.cmd
```

---

## 依赖前置服务

启动前请确认以下软件已安装：

- **llama.cpp** → `D:\llama_cpp\llama-server.exe`
- **模型文件** → `E:\AIModel\gguf\Qwen_Qwen3.6-35B-A3B-Q4_K_M.gguf`
- **JDK 17** → `D:\Dev\JDK17`
- **Maven** → `D:\Dev\Maven\apache-maven-3.9.9`
- **Node.js 24** → `D:\Dev\NodeJs-24`
- **Ollama**（可选）→ `D:\Ollama`
- **go-cqhttp**（可选，QQ Bot）→ 任意目录

---

## OpenClaw 任务示例

```
# 示例指令（QQ/飞书 发送给 Bot）
/ai 帮我在 D:\Dev\project\local-ai-agent\backend\src 目录下
    新建一个 UserController，提供用户查询接口，
    并在完成后发飞书消息告诉我

# OpenClaw 拆解为：
1. generateCode(java, "UserController REST API")  → 生成代码
2. writeFile(...)                                 → 写入文件
3. compileProject(backend, package)               → 编译验证
4. notifyResult(feishu, "", "UserController 已创建并编译成功")
```
