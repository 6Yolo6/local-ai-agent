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

出站 bridge 新增可选加密 checkpoint：`LOCAL_AI_GYING_STATE_FILE` 与 `LOCAL_AI_GYING_STATE_KEY_FILE` 必须同时配置，密钥为至少32字节的独立随机文件。启动器发现 `.local/gying-state-key.bin` 后启用 `.local/ai-state/bridge.bin`，否则保持原内存行为。10月22日候选因安全门禁暂停；2026-10-23用户对同候选明确“可部署”后，双服务已部署并启用，11项原安全失败保留。

推理前先保存 jobId/lease/截止时间，结果落盘后才回传；重启后有结果仅重传，无结果回传 BRIDGE_INTERRUPTED，不重放原 attempt。45秒租约由独立线程每5秒续期，服务端240秒总预算不可延长。服务器终态的同lease/同结果回执可重复确认；409清除旧回执。状态绑定原origin，最多保存15分钟；成功/冲突清除，停机期在恢复时处理过期。单文件独占锁、AES-GCM、owner-only ACL/0600和原子替换，损坏或写入失败停止执行。单机单worker，不宣称工具级恢复或exactly-once推理。

测试通过隔离fixture HTTP及独立JVM退出验证，模型为替身；2026-10-23真实内部模型查询与空闲平滑重启后2条服务端终态恢复已通过，attempt不变；真实网站用户及在途回执跨重启仍待验收。新增取消表示拒绝旧租约回执，当前只读模型调用仍可能执行至自身预算结束。2026-10-23该契约部署先升级兼容的GYing backend再升级本机bridge；该阶段回退先排空，保存旧包/快照/key，不删除私有状态，不重放历史副作用。

## 2026-11-03 角色、主题与历史（两后端已上线）

本轮Agent实现修改MovieQueryPlanner、ExplicitMetadataQuery/ExplicitQueryConstraints、TrustedCandidateHistory与GyingResearchService。原著、作者或编剧语境不会因姓名匹配新增演员/导演过滤；同一句若明确要求该人物主演或执导，相应条件继续保留。正向主题恢复、撤回偏好与明确类型继承分别处理，改编关系仍须实际来源核验。Movie片库层也检查人物角色和数字边界：“150分钟”里的“50分”不能恢复成演员，句中明确的“50分主演”仍是有效出演条件；两侧构建与验证分别记录。

可信context来自Movie已验证同用户成功Job的previousJobId链。queryPlan/candidateIds/candidates保持最新轮含义；candidateHistory按旧到新最多3轮，加最新共4轮。各轮最多30个公开ID/片名/年份，片名最多120个Java字符；expiresAt按各轮原完成时间加900秒计算，新追问不会续期。总context最多10000个Java字符，最新轮超预算时从尾部裁剪、必要时去掉queryPlan，更早轮次无法整轮加入时停止回溯并保持historyComplete=false。

“最开始列表第一部”须能证明对话起点，再按序号、片名或ID定位；多个指示和版本年份应一致。historyComplete=false、过期或身份冲突时澄清，不能把目前最早保留的一轮冒称第一轮。客户端history文字仅作对话理解，不能提供可信ID。定位旧轮后采用details/inheritContext=false/scope=catalog，移除没有在本轮明确提出的最新过滤条件；Movie claim与runningContext重新读取链路，历史详情在查询前再核有效期与当前Job租约。

新指纹版本以稳定的校验输入和previousJobId做提交幂等，执行context单独刷新。祖先过期或context裁剪不会改变同一输入的重试身份；previousJobId本身仍要通过当前归属和有效性检查。旧hash版本按原最新候选context还原兼容核对，终态恢复保存链路，但不延长原15分钟保留时间。

主题requirements由业务代码固定完整core，当前明确核心场景强化加入hard，显式“最好、尽量、优先”单列soft。软偏好之后的其他核心子句仍保留。模型按要求索引逐项返回supported/unknown/contradicted和独立quote，不能重新输出一份更窄的要求。缺项或重复项拒绝；core/hard须为supported，且引文长4–60个Java字符、逐字来自本次展示的简介前300个Java字符。soft的supported/contradicted也需要自己的有效引文，否则按未知提示；unknown允许无引文，soft未知或不符不作硬排除。每批最多4个候选、合计最多8个，保留原候选排序与deadline，延续同一次相关性核对阶段。

模型观测所用候选的完整离线构建为Movie899项中869通过、30项环境跳过，Agent173项全部通过，两侧verify/compile均exit0。一次10场景15轮隔离真实模型观测为15次completed，各轮只提交一次；使用2026-10-31分页采集的12822条公开快照，非数据库原子快照。dev-v3-01与dev-v3-10第二轮仍有核心主题误判：合法合同和真实引文只能限制格式、要求完整性及来源，不能确定性证明语义支持。逐条模型诊断保存在Movie仓库 docs/evaluation/query-quality-20261103/，不把completed或旧版网站回答计作新候选质量验收。

原15轮绑定Movie4f73846f…/Agent055f1bdc…。审查后Movie另补同一无标点句中作者与同人主演/执导的逐次姓名匹配，最终构建与确定性回归单独见Movie仓库 docs/evaluation/query-quality-20261103/movie-reviewed-build.json。该边界修复没有重跑模型，原15轮不能证明后来的Movie源码已执行这些模型问题。

最初20:46安全复检仍为58 PASS/11 FAIL/1 UNKNOWN，G01–G11未关闭，候选部署当时暂停。随后用户明确“可部署”，21:12仅本机Agent backend上线代码15a2b8a8、JAR055f1bdc…；旧7700正常停止，新PID38460/instance7d5364c2-a479-4451-b632-68cbde0afffa受管独立启动。先Agent后Movie是本轮兼容顺序：旧Agent只接受queryPlan/candidateIds/candidates，新Agent接受旧context，新Movie再增加candidateHistory/historyComplete/expiresAt。Movie backend随后于21:47:47（Asia/Shanghai）上线代码13660c9、imagef2b7361b…/实际JAR1b8a09d5…，旧容器TERM143；实际镜像与JAR绑定审查后的Movie源码。

三份新age检查点位于G:/gying-backups/ai-agent-query-quality-20261103-20261103T131135.560714Z，manifest139a4104…，完整认证与大小/hash通过。21:18独立只读复核25项通过，当前stored/lifecycle/创建时间/监听/JAR与部署回执一致，四本机health200，startup回执hostInJob=false；普通权限实时argv为UNKNOWN，部署期管理员证明按helperhash/回执绑定核对。未重复解密、未执行应用回退或真实外发；Agent部署及21:18复核期间，Ollama/Router/frontend/config/key/token/index/state保持。

Movie最终五份age检查点位于G:/gying-backups/ai-query-quality-backend-20261103-20261103T134656.321321Z，认证及离线内容通过；配置与其他容器保持，日常备份新增覆盖已apply/dry-run。出现新requestHashVersion2任务身份后禁止旧Movie自动回退，保留当前SQL/state/key，优先前向修复；两侧回退须单独检查兼容性。具体身份、回退和证据入口见[本机运行记录](local-setup.md)。

上线后本机7个与公网7个入口分次通过。公网使用7897既有代理，默认UA初次出现1010、浏览器UA首轮3项URLError，原失败保留，随后定向只读复验成功；这不构成浏览器页面验收。

21:53:54–21:55:10网站USER专项执行原dev-v3-06三轮及dev-v3-09一轮，共4次提交，无重试；4 executed/4 SUCCEEDED、机械4通过/0失败。previousJobId链正确，第三轮指向第一轮列表候选1MeM，片库导演字段为David Barnard；150>100进入有效澄清。模型诊断满足2/未满足1/证据不足1，真人0；RAYE电视特辑的mv/类别冲突仍未解。test已恢复disabled/有效设备0/token401，密码与USER角色保持，临时cnf删除。该专项不覆盖原15轮整套重跑、完整网站多轮及真人验收。 网站证据在Movie仓库 `docs/evaluation/query-quality-20261103/` 下的 `website-report.json`、`website-runtime.json`、`website-contract-validation.json`、`website-review.json`、`website-answers.md` 和 `website-review.md`，原始结果、实际正文、机械验证及模型诊断分别保存。

原15轮报告仍是部署前的公开快照开发观测，本次没有整套重跑，也未改写原评分；模型诊断满足5、部分有用2、有效澄清2、未满足4、证据不足2，三维完整12、事实维度未知3、真人0。Movie审查后边界构建与上线行为不能改算这15轮的模型结果。隔离fixture历史不代替生产同用户归属、加密队列、租约、多轮页面与真人验收；第一阶段未完成，本轮不提供独立质量提升率。新Agent的正文机制随055f…上线，早先独立4396…包和Movie网页卡片候选未在本次单独发布。
