# GYING 片库只读接入

实现及本机续验日期：2026-10-19（Asia/Shanghai）。

## 使用

2026-10-20 起，本机查询页面只提供工作区/系统查询，片库选择器已移除。影片查询、详情/资源问答与推荐在 GYing 项目 `/ai-assistant` 页面使用。内部 `scope=gying` 和受限工具保留用于项目网页与 OpenClaw 桥接，不与本机对话共用历史或权限。

模型可以先搜索片名，再用结果中的 id 查看简介、评分与资源状态。每次查询均显示实际接口来源、查询时间和调用记录。来源由服务端工具结果生成，不依赖模型自行引用。

```json
POST /api/assistant
{
  "scope": "gying",
  "message": "搜索流浪地球",
  "history": []
}
```

`scope` 仅接受 `local`、`gying`，省略时为 `local`。旧 Router 的 `POST /api/agent` 支持相同的 scope 字段。

## 工具与数据范围

- `searchMovies`：必填 keyword（1–60 字符），可选 year（1900–2100）、category（mv/tv/ac）、limit（1–3，默认 3）。固定查询 `/api/movies/list?page=1&size=...`；返回 id、标题、年份、分类及已有评分。
- `getMovieDetails`：必填 id（字母、数字、下划线、连字符，最多 64 字符），固定查询 `/api/movies/{id}`。简介最多 160 字符，导演/演员/类型使用少量条目。
- 资源必须同时满足 auditStatus=1、status=ACTIVE、linkStatus=NORMAL、deletedAt 为空。未知状态、未审核或失效条目不会展示。
- GYING 站内 `/dbt/` 种子下载入口需要站点账号，按 URL 路径剔除整条记录；域名镜像、大小写和百分号编码不会绕过过滤。不返回这些链接，不占用资源条数额度；磁力和自有网盘归档继续展示。
- 资源最多展示 2 条，优先夸克、迅雷、百度网盘；同时返回筛选后的总数及截断标志。来源接口已经过滤掉的资源不在本次统计范围内。
- 链接与提取码只能来自接口原值。过长（超过 600 字符）或不支持的 URL 会省略并标记，不能截短成另一条链接。外部链接不会被工具请求。

NORMAL 是片库存量状态，`validatedAt` 是已有的历史检查时间，不代表本次已验证网盘。`queriedAt` 为本次读取片库的 UTC 时间，界面转换为浏览器本地时间。

## 连接与限制

默认地址为 `http://127.0.0.1:8880`。可使用 `LOCAL_AI_GYING_BASE_URL` 或 Spring 属性 `local-ai.gying.base-url` 覆盖；仅接受带明确端口的 HTTP 字面回环地址 `127.0.0.1`、`[::1]`，不能包含用户凭据、路径、查询串或片段。不使用 localhost DNS，不走系统代理，不跟随跳转，也不自动重试。

只调用两个公开 GET；禁止搜索转存、发布、修复、管理接口。公开 GET 本身仍会产生 GYING 现有的访问/搜索统计。

单次连接最多 1 秒、读取最多 3 秒、完整 HTTP 调用最多 4 秒，响应体上限 128 KiB（同时检查 Content-Length 和实际读取量）。助手仍执行单并发、最多 4 次工具调用及 4K 上下文预算；需要时减少模型看到的搜索条目、资源条目或简介，并保留完整的本次受限工具结果供界面查看。

每次片库查询使用新回环连接，避免 Docker 转发的空闲连接复用产生 EOF。2026-10-19 真实模型搜索及空闲 15 秒后的详情追问均通过；失败不会改为任意外网请求。

## 项目网页和 OpenClaw 接入

GYing 新版提供认证用户问答队列。本机 `GyingBridge` 主动轮询固定回环端点，完成查片/详情推理并回传工具证据，无需把 Ollama、MCP 或助手端口暴露给容器/公网。只访问 `/api/internal/ai/heartbeat`、`claim`、`jobs/{id}`；专用 `X-AI-Bridge-Token` 不能用于其他内部接口。

配置 `LOCAL_AI_GYING_BRIDGE_TOKEN_FILE` 指向受保护的专用 token 文件，值需与 backend `APP_AI_BRIDGE_TOKEN` 一致；启动脚本会自动读取 `.local/gying-bridge-token.txt` 的路径。不要使用已有 `APP_INTERNAL_TOKEN`。测试可用 `LOCAL_AI_GYING_BRIDGE_BASE_URL` 覆盖队列地址，仍只接受回环 HTTP。

网页问题与 OpenClaw 明确片名提取共享单并发模型。自然语言提取只接受输入中已有的片名，不根据剧情猜测，不执行转存/批量/发布；提取成功后交回 GYing 既有单片、单资源选择链路。用户批准后，GYing/OpenClaw 配套与独立生产 token 已于 2026-10-19/20 部署。2026-10-21 生产内部队列完成真实自然片名提取与存量资源回复，转存/发布审计未变；未发送真实群消息。原生产安全门禁仍独立待处理。

模型/历史/片库文本均视为数据。GYING 模式不能读取本机文件；本机模式不能访问片库。新增工具未注册进旧 MCP 写入/外发工具集合。

## 失败与验证

GYING 不可达、超时、404、HTTP 错误、重定向、无效 JSON、响应过大或非法参数会返回结构化错误，记录在 trace 中；不会退回外网搜索或编造片库结果。

GYING 每条请求必须实际调用工具；模型直接用记忆或上一轮对话回答时，服务端会拒绝该答案并要求一次原生查询重试。仍不调用工具时返回 `GYING_EVIDENCE_REQUIRED`，不会把无来源答案标为成功。来源与查询时间始终以界面显示的服务端记录为准。

自动化测试使用本机临时 HTTP 服务，覆盖固定 GET、关键词编码、参数和地址限制、跳转拒绝、响应限额、超时、错误分类、资源过滤、URL 省略、工具模式隔离和 4K 预算。前端测试覆盖历史按模式隔离，Router 测试覆盖 scope 透传与拒绝未知模式。实机模型与浏览器验收应在统一部署后记录到 local-setup.md。

## 2026-10-22 联网影视查询

GYING scope追加searchMovieWeb，固定zh/en维基百科公开API，只传影视关键词，禁止任意URL/重定向/工作区发送；local模式仍为四项工作区工具。每次限量查询与来源摘录，网页能显示联网引用、请求步骤和无资源搜索入口。小模型自由概括可能补出不存在结局，联网回答改为确定性原文摘录；不等同全网搜索/通用语义准确率。

53项Maven verify通过，真实联网模型回归追加确定性摘录复验；只替换本地backend，旧不可变包G:/local-ai-agent/backups/ai-research-20261022/before.jar，服务平滑握手退出，未切模型/启动第二模型。生产业务发布由用户本轮明确授权，GYing原11安全失败保留。后续独立人工留出、embedding、文档RAG和API Key/配额未交付。

## 持久回执候选（2026-10-22）

出站 bridge 新增可选加密 checkpoint：`LOCAL_AI_GYING_STATE_FILE` 与 `LOCAL_AI_GYING_STATE_KEY_FILE` 必须同时配置，密钥为至少32字节的独立随机文件。启动器发现 `.local/gying-state-key.bin` 后启用 `.local/ai-state/bridge.bin`，否则保持原内存行为。此轮安全门禁阻断双服务生产替换，现役未启用。

推理前先保存 jobId/lease/截止时间，结果落盘后才回传；重启后有结果仅重传，无结果回传 BRIDGE_INTERRUPTED，不重放原 attempt。45秒租约由独立线程每5秒续期，服务端240秒总预算不可延长。服务器终态的同lease/同结果回执可重复确认；409清除旧回执。状态绑定原origin，最多保存15分钟；成功/冲突清除，停机期在恢复时处理过期。单文件独占锁、AES-GCM、owner-only ACL/0600和原子替换，损坏或写入失败停止执行。单机单worker，不宣称工具级恢复或exactly-once推理。

测试通过隔离fixture HTTP及独立JVM退出验证，模型为替身；真实站内跨重启待部署后单独验收。新增取消表示拒绝旧租约回执，当前只读模型调用仍可能执行至自身预算结束。部署先升级兼容的GYing backend再升级本机bridge；回退先排空，保存旧包/快照/key，不删除私有状态，不重放历史副作用。
