# Resume Kit

这套文件的目标是把 `ragent` 变成一个可量化、可演示、可复现的简历项目。

它包含 4 类内容：

1. `knowledge-bases.json`
用于批量创建知识库和导入演示文档。

2. `intent-tree.json`
用于批量创建意图树，让双路检索和意图路由在 dashboard 里有完整演示素材。

3. `sample-questions.json`
用于首页和管理端的样例问题。

4. `rag-eval-dataset.json`
用于离线回归，统计 Top-N 文档命中率和答案关键词覆盖率。

## 建议流程

1. 启动后端、MySQL、Redis、Milvus、对象存储，以及你本地可用的 Embedding / LLM 模型。
2. 运行导入脚本，批量创建知识库、上传文档、触发切块、导入意图树和样例问题。
3. 运行并发压测脚本，拿到 QPS、平均 RT、P95、TTFB。
4. 运行检索评估脚本，拿到 Top-3 文档命中率和答案关键词覆盖率。
5. 运行导出脚本，把 dashboard 指标和压测/评估结果汇总成一份 Markdown。

## 推荐命令

如果你使用 `pwsh`:

```powershell
pwsh -File E:\Projects\ragent\scripts\import_resume_seed.ps1 `
  -BaseUrl http://localhost:9090/api/ragent `
  -Username admin `
  -Password admin `
  -EmbeddingModel qwen-emb-8b

pwsh -File E:\Projects\ragent\scripts\benchmark_rag_chat.ps1 `
  -BaseUrl http://localhost:9090/api/ragent `
  -Username admin `
  -Password admin `
  -Concurrency 10 `
  -Requests 30

pwsh -File E:\Projects\ragent\scripts\evaluate_rag_retrieval.ps1 `
  -BaseUrl http://localhost:9090/api/ragent `
  -Username admin `
  -Password admin

pwsh -File E:\Projects\ragent\scripts\export_resume_metrics.ps1 `
  -BaseUrl http://localhost:9090/api/ragent `
  -Username admin `
  -Password admin
```

如果你使用 Windows PowerShell 5.1，把 `pwsh` 换成 `powershell` 即可。

## 输出文件

脚本默认会在 `resume-kit/output` 下生成这些文件：

- `import-manifest.json`
- `benchmark-latest.json`
- `benchmark-latest.md`
- `retrieval-eval-latest.json`
- `retrieval-eval-latest.md`
- `resume-metrics-summary.md`
- `dashboard-snapshot.json`

## 指标口径

- `Top-3 文档命中率`
  表示每个问题至少命中 1 篇期望文档的比例，适合写成“复杂问答召回能力”。

- `答案关键词覆盖率`
  表示答案中覆盖预期关键词的平均比例，它是“终端问答准确率”的保守代理指标，适合在简历里写成“自建回归集答案覆盖率”。

- `TTFB`
  首包返回时间，更适合展示排队与检索链路效率。

- `RT / P95`
  压测脚本统计的是整条 SSE 请求完成时间；dashboard 里的 `avgLatency/p95Latency` 来自 Trace，经过本次代码补齐后，会覆盖从排队获取到流结束的完整生命周期。

## 你可以直接写进简历的点

- 双路检索 + Rerank + 问题重写 + 意图路由
- Redis + Lua + ZSET 全局排队限流
- RAG Trace + Dashboard 可观测体系
- 自建知识库回归集与压测脚本
- 结构化切块、向量检索、知识库导入链路

## 本地压测模式

如果你想降低云端 API 成本，并把压测切到本机 GPU 路线，可以使用 `local-bench` profile。

1. 启动 Ollama：

```powershell
ollama serve
```

2. 拉取本地聊天模型：

```powershell
ollama pull qwen3:4b
```

3. 启动后端时加上：

```text
-Dspring.profiles.active=local-bench
```

这个 profile 会把聊天模型切到本地 `qwen3-local-fast`（`qwen3:4b`），把 `rerank` 临时切到 `rerank-noop`，并把全局聊天并发从默认保护值提升到 `2`，更适合 `4070S` 这类单卡机器做本地单机压测。现有知识库向量索引可以直接复用，不需要为了聊天压测重新导入知识库。

4. 重新跑压测：

```powershell
powershell -ExecutionPolicy Bypass -File E:\Projects\ragent\scripts\benchmark_rag_chat.ps1 `
  -BaseUrl http://localhost:9090/api/ragent `
  -Username admin `
  -Password admin `
  -Question "Please answer in one sentence: what is the core idea of the dual-retrieval plus rerank pipeline in this project?" `
  -Concurrency 2 `
  -Requests 10
```

如果你使用的是 Windows PowerShell 5.1，压测问题尽量先用英文或 ASCII 字符，避免命令行传中文时出现乱码，干扰本地模型耗时与结果判断。
