# GYING 结构化检索候选

本轮候选由 MovieQueryPlanner → 私有 loopback GyingCatalogClient → 确定性片库查询 → BGE-M3/BM25 候选融合 → 资料摘录/固定搜索来源组成；不执行模型生成 SQL/URL，不发送工作区内容到外部模型。

`LOCAL_AI_GYING_RESEARCH_MODEL` 默认 `qwen3:4b-instruct-2507-q4_K_M`，只用于 GYING 条件抽取/相关性核对。工作区聊天仍使用原 local-ai.ollama.model。生产未替换。 Windows 启动器从本机 .local/environment.json 的可选 gyingResearchEnabled（布尔）、gyingResearchModel、gyingSemanticIndex（绝对本机路径或空）恢复三项环境配置；未设置时保留既有环境/default。部署时明确写入，回退设 false/清空索引，并保持桥接 state/key。

`LOCAL_AI_GYING_SEMANTIC_INDEX` 指向受控二进制文件，`LOCAL_AI_EMBEDDING_MODEL` 默认 bge-m3。文件格式 GYINGSEM2、modelDigest、1024 维归一 float32 与实际片库 fingerprint 有界验证；模型/维度变更拒绝使用，索引失败回退 BM25，不绕过硬条件。

构建默认 CPU；监督初始化可显式 `--gpu-build`，运行中查询仍 CPU，避免同时申请第二份 GPU 模型。构建器复用模型 digest 隔离缓存、发布前再次核对模型、备份旧文件并原子替换。应在模型调用空闲窗口构建；索引只含公开影片元数据，不含用户、账号或资源地址。

```
python -X utf8 scripts/build-semantic-index.py --output <外部索引路径>
python -X utf8 scripts/build-semantic-index.py --catalog <公开快照路径> --output <外部索引路径> --gpu-build --run
```

线上首次启用内部 documents 接口后，以真实公共 projection 增量刷新；须传已有 bridge token 的文件路径，不把值写到命令、日志或文档。恢复旧索引和清空 `LOCAL_AI_GYING_SEMANTIC_INDEX` 可退回 BM25；旧 JAR 回退保留加密任务/回执 state/key，不重放旧任务。

82 项 verify、构建器 5 项测试通过；启动配置恢复/回退 9 项行为验证通过。候选相关性围绕核心主题判断，允许细节近似，仍核对明确排除和实际简介依据。真实开发题已对照 1.7B、普通 4B 与指令版 4B；结构合法并不证明剧情相关，最终质量必须依据实际候选/原文/来源复核。统一报告见 Movie 仓库 `docs/ai-query-plan-v2-20261025.md`；候选与观测在 `E:/gying-tools/releases/ai-query-plan-v2-20261025/`。
