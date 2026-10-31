# GYING 结构化检索

本轮查询由 MovieQueryPlanner → 私有 loopback GyingCatalogClient → 确定性片库查询 → BGE-M3/BM25 候选融合 → 资料摘录/固定搜索来源组成；不执行模型生成 SQL/URL，不发送工作区内容到外部模型。

`LOCAL_AI_GYING_RESEARCH_MODEL` 默认 `qwen3:4b-instruct-2507-q4_K_M`，只用于 GYING 条件抽取/相关性核对。工作区聊天仍使用原 local-ai.ollama.model。2026-10-25已部署并明确启用影视研究模型与索引，工作区聊天模型保持原配置。 Windows 启动器从本机 .local/environment.json 的可选 gyingResearchEnabled（布尔）、gyingResearchModel、gyingSemanticIndex（绝对本机路径或空）恢复三项环境配置；未设置时保留既有环境/default。部署时明确写入，回退设 false/清空索引，并保持桥接 state/key。

`LOCAL_AI_GYING_SEMANTIC_INDEX` 指向受控二进制文件，`LOCAL_AI_EMBEDDING_MODEL` 默认 bge-m3。文件格式 GYINGSEM2、modelDigest、1024 维归一 float32 与实际片库 fingerprint 有界验证；模型/维度变更拒绝使用，索引失败回退 BM25，不绕过硬条件。

构建默认 CPU；监督初始化可显式 `--gpu-build`，运行中查询仍 CPU，避免同时申请第二份 GPU 模型。构建器复用模型 digest 隔离缓存、发布前再次核对模型、备份旧文件并原子替换。应在模型调用空闲窗口构建；索引只含公开影片元数据，不含用户、账号或资源地址。

初始化的 `--batch` 是每次提交的影片数（1–32）；`--num-batch` 是推理计算批量（256/512/1024，默认512）。本机同16份输入的512/1024向量余弦均为1，但与32份构建交错切换时出现HTTP500，完整构建已恢复512；不把小批量对照当成全量稳定性验收。性能测量含排队/加载，不能推广为固定加速倍数，不改变在线CPU查询配置。

```
python -X utf8 scripts/build-semantic-index.py --output <外部索引路径>
python -X utf8 scripts/build-semantic-index.py --catalog <公开快照路径> --output <外部索引路径> --gpu-build --run
```

线上首次启用内部 documents 接口后，以真实公共 projection 增量刷新；须传已有 bridge token 的文件路径，不把值写到命令、日志或文档。恢复旧索引和清空 `LOCAL_AI_GYING_SEMANTIC_INDEX` 可退回 BM25；旧 JAR 回退保留加密任务/回执 state/key，不重放旧任务。

87 项 verify、构建器 9 项测试通过；启动配置恢复/回退 9 项行为验证通过。候选相关性围绕核心主题判断，允许细节近似，仍核对明确排除和实际简介依据。真实开发题已对照 1.7B、普通 4B 与指令版 4B；结构合法并不证明剧情相关，最终质量必须依据实际候选/原文/来源复核。统一报告见 Movie 仓库 `docs/ai-query-plan-v2-20261025.md`；候选与观测在 `E:/gying-tools/releases/ai-query-plan-v2-20261025/`。

联网记录区分原始返回、身份排除和相关性保留数量。已知影片的条目名/年份精确匹配时，可保留实际搜索链接并标注film-identity，仅核对身份，剧情仍来自片库；不把链接当作已访问的剧情网页。恢复fixture子JVM限制192MiB堆并补退出日志，87项verify通过（含Spring装配回归）。

16:00部署时JAR代码提交`8a65287`、SHA`b7bd517cbe6f7c5b8cdf01ce0d8872460958fc8b108653d7c8882fa74ff7f399`，完整JAR隔离健康/握手/优雅退出通过；首次装配失败回退证据保留。生产索引11,272条、SHA`f74cb9c6afd7c5b029305a3c0a141a3d1348eb5116285fb7de43bcdbc9b43eb4`，原11,220对照索引/报告provenance不改写。受控GET导出100项分页、1秒节奏与数字Retry-After最多两次重试（每次≤60秒），401/重定向/超界等待不重试；9项行为验证通过。网站真实登录条件查询与定期增量调度未复验/登记。

## 2026-10-25 17:19 服务可用性补丁

当时上线代码`cd79797`、JAR SHA`33d9b6748cdac41f82e7d7ac9afbcda4f672d2bf71fce640c235f8e3efdf3c37`，91项verify与完整JAR15000隔离健康/实例握手/优雅退出通过。冷片库实际15.05秒超过旧10秒read，改为read/call30秒、connect2秒，所有研究片库请求继续受原总体deadline约束。Windows启动默认通过独立WMI隐藏壳，内部ServiceHost处于Job则拒绝；6项真实合成Job与9项研究配置测试通过。

17:19–17:20仅平滑替换Agent backend；首次公网403验收失败已回退，第二次经浏览器标识入口复验部署成功。三份age认证回退在`G:/gying-backups/ai-agent-20261025T091941.886931Z`，保留旧JAR、私有state/key/token/config与旧启动入口。其他本机实例、Movie容器、配置与生效索引hash保持。实际条件27.98秒、语义62.50秒、Bing17.42秒、生产只读队列7.52秒完成；人物额外排除与年份包含边界偏差仍记录，completed不代表全正确。网站登录条件查询未复验，未真实外发；完整证据见Movie仓库`docs/ai-assistant-availability-20261025.md`与`E:/gying-tools/releases/ai-assistant-unavailable-20261025/`。

## 2026-10-29 类别与评分来源修复：先暂缓，后上线

新增ExplicitQueryChoices校正显式类别/评分来源：否定剧集不覆盖肯定电影，否定IMDb不覆盖豆瓣；书名号内容不作类别条件，枚举否定与“不是只用”等独占否定有回归；多选/冲突无法表达时澄清。未修数量、严格时长、跨轮年份范围替换与更早列表指代。

104项Maven verify全部通过，最终JAR SHA-256 `01eab8e20a5ecbbbc8677d1000fecb7e87479402f3e839c3b9444ff39336782b`。15000隔离健康、实例握手和正常退出通过。候选/回归/旧包在 `E:/gying-tools/releases/ai-quality-20261029/`。这是早先暂缓轮次的记录：当时运行JAR仍是10月25日33d9b674…，用户要求暂缓，那个轮次未替换或重启服务；后续上线结果见下段。

Movie仓库新增开发网站runner和84条历史回答的模型辅助复核，真人评分分开。旧服务上的两条新专项查询因片库研究接口500没有有效queryPlan；Movie后端日志确认Java heap OOM，不能把这组失败算作条件解析评分。上述两条失败属于恢复前观测，不用于证明修复前后质量变化；新15轮网站USER采集仍未执行，不用测试数量宣称模型质量提升。

10月29日用户恢复上线授权后，Movie后端内存与缓存修复已另行部署；Agent经用户重试及管理员确认，于20:56–20:57替换为代码`9cd1d2b`对应的完整JAR，SHA-256 `01eab8e20a5ecbbbc8677d1000fecb7e87479402f3e839c3b9444ff39336782b`，使用对应hash的不可变运行副本。旧实例正常退出、新实例ready，三份age回退材料完成hash与认证解密校验；当前state/key/token/config及索引保持。21:00两条实际查询的类别与评分来源均通过，但两条都遗漏评分降序，第二条遗漏剧情类型，因此只计专项通过，不计完整需求满足。部署及回退证据在 `E:/gying-tools/releases/ai-recovery-20261029-1750/`；Movie仓库 `docs/evaluation/resource-recovery-20261029/README.md` 保留完整验收范围。

## 2026-10-31 条件与会话改进候选（生产未替换）

本轮先复核82条历史未满足回答的逐轮依据，再检查现役仍有缺口的行为。旧输出、冻结题与模型评分保留；这些回答来自10月23日的旧实现，不能当成10月31日生产失败率。本轮候选聚焦通用条件提取和会话边界，未声明82条问题全部修复。

- 数量识别覆盖“给两部”“最多三部”“列出”“前三”；明确评分排序使用同一来源的评分降序。剧情剧集、音乐与歌舞进入类型条件；剧情说明不自动变成剧情类型过滤。
- 时长的严格大于/小于转换成整数分钟的开放边界，保留上下界与小时换算。完整数字先用BigDecimal核对契约，超大数、负数、超范围评分/年份/时长/序号会澄清，不截取数字或静默放宽；互相矛盾的条件也先澄清。
- 明确的参数变更与排序追问继承既有条件。范围替换移除旧的同维度两端，保留其余条件；明确限定刚才结果时继续在原候选集合筛选，其他范围替换回到片库检索。
- “最开始/第一轮/最早那份”的候选身份无法由当前仅含最新一轮的上下文核实时，要求片名或原列表，不把最新首项冒充更早首项。书名中的数字、类型、语言、评分来源、序号及“类似/网上查”等字样不作为筛选或路由指令。

范围替换新增受限`replaceRanges`数组，允许`year`、`runtime`、`rating`，最多3项且不允许重复；必须是`inheritContext=true`且每个指定维度有本轮显式新边界。Agent只根据用户明确的范围替换生成该指令，不接受模型凭空重置。Movie后端先清旧维度两端再合并本轮条件，执行后的queryPlan消费并移除指令。普通新增单侧边界保留另一侧；继承后无交集也有效澄清。

新Agent依赖支持该契约的Movie backend，部署顺序必须先Movie backend、后Agent；旧Movie backend会拒绝未知字段。回退先恢复旧Agent再回退Movie backend，保留当前state/key与数据。10月31日此处仅记录源码候选，生产尚未替换，现役Agent仍为10月29日01eab8…；本轮不改变工作区模型、索引或其他服务配置。

首候选完成121/121项Java测试，无跳过，compile通过；源码绑定SHA-256为 `e9410defc7e5a6fc5c037fe64c1ebc26c9916da7509ef6861c9a096634b956c8`，完整JAR SHA-256为 `2156f19e7497a2a0657bc1fb4cfe617694f66e448d4131ea0c98847486d177ee`。隔离Agent经只读relay查询现役公共片库，3条实际探针的数量/同源评分排序、剧情剧集类型及严格`90 < runtime < 100`边界全部程序检查通过；共3次catalog查询，实例正常退出，未消费生产队列或执行真实外发。候选与验证证据位于 `E:/gying-tools/releases/ai-quality-improvement-20261031/`。

这3条是当前缺口的实际专项验证，不表示全部历史回答已满足需求。Movie范围替换新契约通过Java回归；后续隔离Movie候选真实类联动见下段，生产两服务/网站多轮仍未验。首候选专项查询不代替两服务按顺序部署后的真实会话验收。

本轮新增人工验收为0；模型辅助判断仍单独标明来源。网站USER的10场景/15轮采集未执行，本机候选专项查询不能替代登录网站链路或独立质量评测。主题相关性、改编关系、时效公告、内容排除及真实偏好仍需依据实际回答和来源继续复核。


### 首轮发现后的最终候选与完整实际回答

首轮真实Movie候选类与Agent2156…对冻结development-v3执行15轮，15次均completed，模型复核为满足0、部分有用1、未满足8、证据不足4、有效澄清2。随后补版本资料/年份/导演/主演实际输出、中文语言并集及语言词不作国家、原始对白/逐场内容排除未知提示，以及官方公告只读web核验。公告不以电影身份命中兜底，不用影片年份或本次查询日期代替公告发布日期；找不到证据时明确未知。

Movie另补纪录题材别名的内部等价匹配，保留原metadata/plan；不放宽其他硬条件。原作者关系、早期列表引用、核心主题提取及柔性偏好未扩展；“只核最新列表”防误选逻辑在实际已提交早期历史时仍没有正确回答，不能把这条算有效澄清。

最终Agent全套 135/135 项verify通过、compile通过，无跳过/失败/错误；源码SHA-256 `9013b26969632d0d95c7875c174203507b9b1d8228bf6343308763bd46f813af`，JAR SHA-256 `1f9d9628fbe2cdbb50190c12fcda60ae9870daeb1563caaf84ecef5ddf09f07d`。最后一项回归覆盖片库空、web有合法摘录且无identifiedTitle时仍保留未知条件提示。

第二候选Agent dc8…与Movie d0149…完整15轮为14 completed/1 WEB_TIMEOUT失败；模型辅助复核满足4（其中1为允许的诚实无结果处理）、未满足4、已完成但证据不足4、有效澄清2，另1执行失败不评分。最后新Agent1f9…再次完整15轮，13 completed/2失败（01 WEB_UNAVAILABLE、07 WEB_TIMEOUT），不以新请求覆盖旧失败；最终13条答案的模型复核为满足3（版本资料2、音乐首轮1）、有效澄清2、未满足4、证据不足4，另2次执行失败三维质量保持null；最终逐轮结论保存在Movie仓库 `docs/evaluation/quality-improvement-20261031/development-final-review.json`，实际回答见同目录 `development-final-answers.md`。

三轮都是实现方的开发观测，新增真人0，不是独立质量提升率。真实Ollama/现役索引与Movie候选真实类通过限定SELECT的SnapshotJdbcTemplate联动，使用同一12,822条公开快照；分页快照非原子事务。两进程正常退出；无生产DB连接/写入、队列领取、state持久化或外发。Movie完整Spring/调度器、生产部署和网站USER链路仍未验。

生产仍为10月29日01eab…；本轮G01–G11门禁及普通权限受管身份预检未通过，未停止或替换生产服务。固定镜像/新JAR、现役全部覆盖、先Movie后Agent及逆序回退的计划已准备在外部release；10月29日授权例外不自动复用。新部署须由本候选特定范围处理门禁，并通过管理员只读身份复核及新的加密检查点认证验证。
