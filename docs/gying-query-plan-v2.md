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

## 17:19 服务可用性补丁

当前代码`cd79797`、JAR SHA`33d9b6748cdac41f82e7d7ac9afbcda4f672d2bf71fce640c235f8e3efdf3c37`，91项verify与完整JAR15000隔离健康/实例握手/优雅退出通过。冷片库实际15.05秒超过旧10秒read，改为read/call30秒、connect2秒，所有研究片库请求继续受原总体deadline约束。Windows启动默认通过独立WMI隐藏壳，内部ServiceHost处于Job则拒绝；6项真实合成Job与9项研究配置测试通过。

17:19–17:20仅平滑替换Agent backend；首次公网403验收失败已回退，第二次经浏览器标识入口复验部署成功。三份age认证回退在`G:/gying-backups/ai-agent-20261025T091941.886931Z`，保留旧JAR、私有state/key/token/config与旧启动入口。其他本机实例、Movie容器、配置与生效索引hash保持。实际条件27.98秒、语义62.50秒、Bing17.42秒、生产只读队列7.52秒完成；人物额外排除与年份包含边界偏差仍记录，completed不代表全正确。网站登录条件查询未复验，未真实外发；完整证据见Movie仓库`docs/ai-assistant-availability-20261025.md`与`E:/gying-tools/releases/ai-assistant-unavailable-20261025/`。

## 2026-10-29 类别与评分来源候选（用户暂缓上线）

新增ExplicitQueryChoices校正显式类别/评分来源：否定剧集不覆盖肯定电影，否定IMDb不覆盖豆瓣；书名号内容不作类别条件，枚举否定与“不是只用”等独占否定有回归；多选/冲突无法表达时澄清。未修数量、严格时长、跨轮年份范围替换与更早列表指代。

104项Maven verify全部通过，最终JAR SHA-256 `01eab8e20a5ecbbbc8677d1000fecb7e87479402f3e839c3b9444ff39336782b`。15000隔离健康、实例握手和正常退出通过。候选/回归/旧包在 `E:/gying-tools/releases/ai-quality-20261029/`，原运行JAR仍是10月25日33d9b674…；用户明确暂缓上线，未替换或重启现役服务。

Movie仓库新增开发网站runner和84条历史回答的模型辅助复核，真人评分分开。旧服务上的两条新专项查询因片库研究接口500没有有效queryPlan；Movie后端日志确认Java heap OOM，不能把这组失败算作条件解析评分。运行恢复和新15轮网站USER采集仍待安排，不用测试数量宣称模型质量提升。
