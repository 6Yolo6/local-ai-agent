# 本机环境与验收记录

验证日期：2026-10-16（Asia/Shanghai）。项目目录：D:/ai/local-ai-agent。

## 环境

| 组件 | 版本 / 设置 | 本机路径 |
| --- | --- | --- |
| GPU | GTX 1650 Ti，4096 MiB，驱动 560.94 | 使用 CUDA 12 |
| Java | 已有 JDK 17.0.1 | D:/Java17/jdk-17.0.1 |
| Node.js | 已有 20.17.0 | D:/nvm/nodejs/node.exe |
| Maven | 3.9.9，官方 SHA512 已核验 | G:/local-ai-agent/runtime/apache-maven-3.9.9 |
| pnpm | 10.28.2，独立安装 | G:/local-ai-agent/runtime/pnpm/node_modules/.bin/pnpm.cmd |
| Ollama | 0.11.11，官方 SHA256 已核验 | G:/local-ai-agent/runtime/ollama-0.11.11 |
| 模型 | qwen3:1.7b，Q4_K_M | G:/local-ai-agent/models |
| 依赖缓存 | Maven、npm、pnpm | G:/local-ai-agent/cache |

原有 G:/Ollama 0.5.7 安装及其他已有模型未替换。未修改系统 PATH。私有路径保存在 .local/environment.json，不进 Git。

模型大小为 1,359,293,444 字节，摘要为 8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7。标签为 qwen3:1.7b，Ollama 元数据 parameter_size 显示 2.0B。

4096 上下文、thinking=false、单并发时，Ollama 报告 GPU 模型占用 2,335,211,861 字节，约 2.18 GiB。短句首次加载约 5.36 秒；不作为通用性能基准。重启后 Ollama 会按当时空闲显存自动调整 GPU/CPU 分配，另一次记录 GPU 为约 1.57 GiB，因此不能承诺始终全量驻留 GPU。默认不启动第二模型、llama.cpp、AnythingLLM 或机器人网关。

## 已实现

- 原生工具循环、多轮历史、真实模型和工具 trace、动态工具清单。
- 四项自主只读工具：getSystemInfo、listFiles、readFile、readLastLines。
- readFile 固定读取前 3000 字符，单文件最多 256 KiB，显式返回截断标志。
- 拒绝工作区外路径、符号链接/目录联接、网络路径、NTFS 数据流及私有配置/凭据路径。
- 最多 4 次工具调用、6 轮推理、180 秒总时限、单工具 5 秒、单请求并发。
- 4K 上下文预算预检，裁剪较早历史并提示；工具证据截断时提示，仍超限报错。
- 小模型返回工具协议文字时有限重试；不将文字当成可执行指令，仍异常则报 MODEL_RESPONSE_INVALID。
- 全局 trace 只保留最近 200 条内存记录，省略文件正文；当前请求可展开具体返回结果。
- Router 统一进入助手，不再额外加载分类模型。
- 旧 MCP 单独保留 17 项工具，包含写文件、命令和外发能力；自主助手不调用这些能力。
- RAG、GYing 集成、API Key/配额、机器人发布为后续阶段。

## 验收

- Maven verify：25 项测试通过，无跳过，包含真实 Windows 符号链接检查。
- 前端会话处理：4 项通过；独立 pnpm 10.28.2 frozen-lockfile 完整安装和生产构建成功。
- Router HTTP：5 项测试通过。
- 真实模型：目录列表、读取 package.json 并准确回答 name/dev、连续追问均通过。
- 真实边界：越界返回 PATH_DENIED；删除请求拒绝；伪造 system 历史在调用模型前拒绝。
- 停止本项目 Ollama 后返回 MODEL_UNAVAILABLE 与错误 trace，已重新启动恢复。
- Edge 浏览器：真实聊天、发送 2 条 history 的追问、展开工具记录、真实日志和动态工具页面通过，无 pageerror。
- MCP SSE：/sse 初始化、notifications/initialized、tools/list 成功，发现 17 项工具；没有执行外发或写入。
- PowerShell 脚本语法、重复启动和按进程归属停止已验证。

验收 JSON 和截图在 .local/，不进 Git。Vite 有依赖注释及 bundle 大小提示，构建成功。小模型仍可能理解错误，不能把本次验收等同于通用问答准确率评测；具体依据可查看执行记录。

## 启停与恢复

运行 scripts/start-all.bat，访问 http://127.0.0.1:3000。检查 scripts/check-local.ps1；停止 scripts/stop-all.bat。

服务只监听 127.0.0.1，后台启动。停止脚本核对 PID、启动时间和可执行文件，只结束本项目管理的进程。

原始源码归档：G:/local-ai-agent/backups/dacb308-source.zip。
旧 pnpm 11 前端依赖备份：.local/frontend-node_modules-pnpm11。
当前使用的是独立 pnpm 10 安装的依赖。

如需撤回代码，使用正常 Git revert 提交，先停本项目服务，再切换版本并重新构建。原始版本含旧电脑路径，不能直接作为本机可运行包替换。不要重置或删除用户数据；独立安装的运行环境可保留用于恢复。
