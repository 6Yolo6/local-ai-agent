# 本机环境与验收记录

验证日期：2026-10-18（Asia/Shanghai）。项目目录：D:/ai/local-ai-agent。环境安装基线为 2026-10-16，以下追加本次接入与生命周期验收。

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
- GYING 只读接入：独立片库模式、公开 GET 搜索/详情、NORMAL 且已审核资源过滤、来源与本次查询时间展示。站内 `/dbt/` 账号种子下载入口不会返回，磁力和网盘归档保留。
- GYING 无本次工具调用的回答先触发一次原生查询重试，仍无调用时返回 GYING_EVIDENCE_REQUIRED；不接受凭记忆编造的片库结果。
- RAG、API Key/配额、机器人发布为后续阶段。

## 验收

- 2026-10-19 独立 Maven verify：47 项测试通过，无跳过，包含真实 Windows 符号链接、GYING GET/资源过滤、桥接回传重试不重复推理、自然片名提取和生命周期身份校验。
- 前端会话处理：5 项通过；独立 pnpm 10.28.2 frozen-lockfile 完整安装和生产构建成功。
- Router HTTP：6 项测试通过；npm ci 成功。npm 审计报告现有锁文件含 6 项依赖漏洞（3 moderate、2 high、1 critical），本轮没有扩展为依赖升级。
- Node 生命周期：2 项通过，验证旧实例请求拒绝与先排空再写完成确认。
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

2026-10-18 已将 backend、router、frontend 按实例握手平滑停止并重新部署；退出后均验证原进程消失、端口释放与匹配的 stop.completed.json。后端在途真实模型请求另行验收：10:15:58.634 开始 draining，10:15:58.924 完整回答返回，10:15:59.039 才写入 stopped 确认；没有强制结束进程。

`stop-local.ps1 -CheckOnly` 只读预检，最后一行 JSON 提供 runningServices 与 instances；`start-local.ps1 -Services ...` 仅恢复指定服务。当前 Ollama 存量进程无可验证隔离控制台，普通全量 stop 在预检拒绝，三个应用可用 `-Services frontend,router,backend -Graceful` 独立停止。`-ForReboot` 仅用于已安排的正常 Windows 重启：先排空应用、确认无已连接客户端、卸载所有模型，再标记 Ollama 仍运行并交由 Windows 正常关机。此验收没有重启电脑，也没有强杀 Ollama。

原始源码归档：G:/local-ai-agent/backups/dacb308-source.zip。
旧 pnpm 11 前端依赖备份：.local/frontend-node_modules-pnpm11。
当前使用的是独立 pnpm 10 安装的依赖。

2026-10-19 已恢复片库空闲连接问题，并部署本机 outbound bridge 代码；生产桥接凭据尚未启用。真实 Qwen 搜索星际穿越、15 秒空闲后用 id `bDeK` 追问详情/两条存量资源、明确片名提取和批量请求拒绝均通过。项目网页中英/明暗/手机桌面、匿名登录、历史、失败/离线/新对话五组隔离浏览器场景通过。外部资源可用性和真实群消息未验收。

后端启动改为 `.local/runtime/backend/<SHA256>/local-ai-agent.jar` 的校验副本，避免直接 Maven 打包覆盖运行中的 JAR。旧运行文件被失败打包替换后产生依赖缺失、排空失败的问题已通过恢复完整构建及正常退出信号修复；随后实例正常握手停止和重新启动通过。没有强杀或重启电脑。构建/候选证据位于 `E:/gying-tools/releases/ai-assistant-20261019`；本机原包保存在 `G:/local-ai-agent/backups/ai-catalog-20261019/before.jar`。

本次替换前的运行包保存在 `G:/local-ai-agent/backups/local-ai-20261018/previous-local-ai-agent.jar`，SHA-256 为 `0D717D8246BE3FDD25A54B040CA7C5D899DC5478FEEAACBF533D5A91D6CA4071`。生命周期身份、真实浏览器/模型与排空证据保存在 `.local/qa/`，不进 Git。回退后端时先按相同握手停止 backend，再复制此包到 backend/target/local-ai-agent-1.0.0.jar 并用 start-local.ps1 -Service backend 启动。

如需撤回代码，使用正常 Git revert 提交，先停本项目服务，再切换版本并重新构建。原始版本含旧电脑路径，不能直接作为本机可运行包替换。不要重置或删除用户数据；独立安装的运行环境可保留用于恢复。
