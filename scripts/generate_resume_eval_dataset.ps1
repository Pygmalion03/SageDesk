[CmdletBinding()]
param(
    [string]$OutputPath = "E:\Projects\ragent\resume-kit\rag-eval-dataset.json",
    [string]$Version = "v2"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-EvalCase {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Id,
        [Parameter(Mandatory = $true)]
        [string]$Question,
        [Parameter(Mandatory = $true)]
        [string[]]$ExpectedDocKeys,
        [Parameter(Mandatory = $true)]
        [string[]]$AnswerKeywords
    )

    return [ordered]@{
        id              = $Id
        question        = $Question
        expectedDocKeys = $ExpectedDocKeys
        answerKeywords  = $AnswerKeywords
    }
}

$cases = @(
    (New-EvalCase -Id "eval_001" -Question "这个项目为什么不是普通聊天 Demo，而是更偏企业知识问答系统？" -ExpectedDocKeys @("project_overview") -AnswerKeywords @("企业知识问答", "Demo", "生产链路"))
    (New-EvalCase -Id "eval_002" -Question "这个项目为什么能同时体现 Java 后端工程能力和 RAG 算法链路能力？" -ExpectedDocKeys @("project_overview", "retrieval_pipeline") -AnswerKeywords @("Java", "RAG", "工程能力"))
    (New-EvalCase -Id "eval_003" -Question "一次对话请求进入系统后，主链路大概会经过哪些阶段？" -ExpectedDocKeys @("project_overview") -AnswerKeywords @("限流", "问题重写", "SSE"))
    (New-EvalCase -Id "eval_004" -Question "RAGChatController 之后，服务端会先做哪几步处理？" -ExpectedDocKeys @("project_overview") -AnswerKeywords @("对话记忆", "意图识别", "检索"))
    (New-EvalCase -Id "eval_005" -Question "如果一个请求命中的其实是系统能力型意图，而不是知识库型意图，会走什么路径？" -ExpectedDocKeys @("project_overview") -AnswerKeywords @("Prompt", "MCP", "系统能力"))
    (New-EvalCase -Id "eval_006" -Question "bootstrap、framework、infra-ai、frontend 在这个项目里分别负责什么？" -ExpectedDocKeys @("project_overview") -AnswerKeywords @("bootstrap", "framework", "infra-ai", "frontend"))
    (New-EvalCase -Id "eval_007" -Question "为什么说这个项目天然适合写进简历？" -ExpectedDocKeys @("project_overview", "resume_highlights") -AnswerKeywords @("量化", "Java 后端", "RAG"))
    (New-EvalCase -Id "eval_008" -Question "这个项目里最值得量化的后端工程指标有哪些？" -ExpectedDocKeys @("project_overview", "queue_stability") -AnswerKeywords @("QPS", "P95", "错误率"))
    (New-EvalCase -Id "eval_009" -Question "这个项目里最值得量化的 RAG 效果指标有哪些？" -ExpectedDocKeys @("project_overview", "retrieval_pipeline") -AnswerKeywords @("Top-3", "关键词覆盖率", "无文档回复率"))
    (New-EvalCase -Id "eval_010" -Question "为什么这个项目看起来更像生产链路，而不是简单套壳调用大模型？" -ExpectedDocKeys @("project_overview", "trace_dashboard") -AnswerKeywords @("知识库导入", "限流", "可观测"))

    (New-EvalCase -Id "eval_011" -Question "这个项目的双路检索是由哪两条链路组成的？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("意图定向检索", "全局向量检索", "双路检索"))
    (New-EvalCase -Id "eval_012" -Question "为什么做了意图识别之后，还需要全局向量兜底检索？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("意图误判", "兜底", "漏召回"))
    (New-EvalCase -Id "eval_013" -Question "意图定向检索主要解决什么问题，全局向量检索又主要解决什么问题？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("意图", "向量", "召回"))
    (New-EvalCase -Id "eval_014" -Question "在什么情况下系统会更依赖全局向量兜底，而不是只走意图定向检索？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("置信度不高", "无明确意图", "全局向量"))
    (New-EvalCase -Id "eval_015" -Question "为什么进入检索前要先做问题重写？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("口语化", "向量检索", "重写"))
    (New-EvalCase -Id "eval_016" -Question "为什么要把复合问题拆成多个子问题再检索？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("复合问题", "子问题", "意图"))
    (New-EvalCase -Id "eval_017" -Question "问题重写和子问题拆分这一步，对复杂企业知识问答有什么实际价值？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("复杂企业知识问答", "重写", "拆分"))
    (New-EvalCase -Id "eval_018" -Question "Rerank 在这条链路里是放在什么位置上的？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("候选 Chunk", "重排", "Rerank"))
    (New-EvalCase -Id "eval_019" -Question "为什么描述 Rerank 收益时，更适合写 Top-N 命中率，而不是只说答案更准？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("Top-N", "命中率", "量化"))
    (New-EvalCase -Id "eval_020" -Question "如果要量化双路检索和 Rerank 的收益，最推荐统计哪几项指标？" -ExpectedDocKeys @("retrieval_pipeline") -AnswerKeywords @("Top-3", "Top-5", "关键词覆盖率", "无文档回复率"))

    (New-EvalCase -Id "eval_021" -Question "为什么 RAG 对话比普通 CRUD 接口更需要做全局排队限流？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("长连接", "流式输出", "资源消耗高"))
    (New-EvalCase -Id "eval_022" -Question "如果不对 RAG SSE 对话做并发约束，最容易出现什么问题？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("雪崩", "RT 抬升", "SSE 连接堆积"))
    (New-EvalCase -Id "eval_023" -Question "这个项目的全局排队限流方案核心用了哪些组件？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("Redis", "Lua", "ZSET"))
    (New-EvalCase -Id "eval_024" -Question "进入请求先排队，再做执行许可控制，这两层设计各自解决什么问题？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("排队", "执行许可", "活跃 SSE"))
    (New-EvalCase -Id "eval_025" -Question "为什么要用 Redis 原子脚本来保证 claim 行为一致？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("原子脚本", "claim", "脏状态"))
    (New-EvalCase -Id "eval_026" -Question "这个项目里为什么会把会话记忆压缩也归到稳定性方案里？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("会话记忆压缩", "Token", "内存"))
    (New-EvalCase -Id "eval_027" -Question "压测 RAG SSE 对话时，为什么不能只看 QPS？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("RT", "TTFB", "P95"))
    (New-EvalCase -Id "eval_028" -Question "一次 SSE 请求的 RT 在这个项目里是怎么定义的？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("SSE 请求", "发起到完成", "总耗时"))
    (New-EvalCase -Id "eval_029" -Question "为什么 TTFB 更能体现排队、检索和首轮模型响应速度？" -ExpectedDocKeys @("queue_stability") -AnswerKeywords @("TTFB", "排队", "首包"))
    (New-EvalCase -Id "eval_030" -Question "如果把这个限流方案写进简历，最该带上的几个性能词是什么？" -ExpectedDocKeys @("queue_stability", "resume_highlights") -AnswerKeywords @("QPS", "P95", "无 OOM", "稳定性"))

    (New-EvalCase -Id "eval_031" -Question "RAG 项目里为什么必须做 Trace 和 dashboard，而不能只靠日志排查？" -ExpectedDocKeys @("trace_dashboard") -AnswerKeywords @("答案不准", "系统变慢", "可观测"))
    (New-EvalCase -Id "eval_032" -Question "这个项目里的可观测体系主要想解决哪两类问题？" -ExpectedDocKeys @("trace_dashboard") -AnswerKeywords @("答案不准", "系统变慢", "链路"))
    (New-EvalCase -Id "eval_033" -Question "当前链路里重点可观测的 Trace 节点有哪些？" -ExpectedDocKeys @("trace_dashboard") -AnswerKeywords @("query rewrite", "intent resolve", "retrieval", "llm routing"))
    (New-EvalCase -Id "eval_034" -Question "为什么 multi-channel retrieval 这个节点特别值得单独打点？" -ExpectedDocKeys @("trace_dashboard", "retrieval_pipeline") -AnswerKeywords @("多通道检索", "耗时", "返回 Chunk"))
    (New-EvalCase -Id "eval_035" -Question "多通道检索节点现在会额外记录哪些信息？" -ExpectedDocKeys @("trace_dashboard") -AnswerKeywords @("检索通道", "耗时", "文档 ID", "分数"))
    (New-EvalCase -Id "eval_036" -Question "Overview 看板里主要统计哪些业务量指标？" -ExpectedDocKeys @("trace_dashboard") -AnswerKeywords @("总用户数", "活跃用户数", "会话数", "消息数"))
    (New-EvalCase -Id "eval_037" -Question "Performance 看板里主要统计哪些性能或质量指标？" -ExpectedDocKeys @("trace_dashboard") -AnswerKeywords @("平均延迟", "P95", "成功率", "错误率"))
    (New-EvalCase -Id "eval_038" -Question "Trends 看板适合观察哪些趋势性数据？" -ExpectedDocKeys @("trace_dashboard") -AnswerKeywords @("会话", "消息", "活跃用户", "平均延迟"))
    (New-EvalCase -Id "eval_039" -Question "为什么说现在可以直接基于 Trace 做离线评估，而不是手工看日志？" -ExpectedDocKeys @("trace_dashboard") -AnswerKeywords @("Trace", "离线评估", "日志"))
    (New-EvalCase -Id "eval_040" -Question "如果把 dashboard 指标写进简历，最容易被追问的是哪几类数据？" -ExpectedDocKeys @("trace_dashboard", "resume_highlights") -AnswerKeywords @("平均 RT", "P95", "成功率", "无文档回复率"))

    (New-EvalCase -Id "eval_041" -Question "知识库文档导入后，文件、数据库记录和向量索引各自是怎么分工的？" -ExpectedDocKeys @("ingestion_chunking") -AnswerKeywords @("对象存储", "数据库", "Milvus"))
    (New-EvalCase -Id "eval_042" -Question "为什么上传后的文档不是同步处理，而是触发异步切块任务？" -ExpectedDocKeys @("ingestion_chunking") -AnswerKeywords @("异步", "切块", "向量化"))
    (New-EvalCase -Id "eval_043" -Question "这个项目支持的两类文档处理模式分别是什么？" -ExpectedDocKeys @("ingestion_chunking") -AnswerKeywords @("chunk", "pipeline", "处理模式"))
    (New-EvalCase -Id "eval_044" -Question "如果是简历演示类知识库，为什么更推荐结构感知切块？" -ExpectedDocKeys @("ingestion_chunking") -AnswerKeywords @("结构感知", "段落语义", "问答"))
    (New-EvalCase -Id "eval_045" -Question "这套推荐的切块参数大概是什么量级？" -ExpectedDocKeys @("ingestion_chunking") -AnswerKeywords @("targetChars", "maxChars", "overlapChars"))
    (New-EvalCase -Id "eval_046" -Question "为什么推荐 targetChars=900、maxChars=1200、overlapChars=120 这一组参数？" -ExpectedDocKeys @("ingestion_chunking") -AnswerKeywords @("关键描述", "Chunk", "结构化"))
    (New-EvalCase -Id "eval_047" -Question "知识库导入完成后，第一时间要检查哪些状态和字段？" -ExpectedDocKeys @("ingestion_chunking") -AnswerKeywords @("success", "chunkCount", "resultChunkIds"))
    (New-EvalCase -Id "eval_048" -Question "为什么说 file storage 和 vector index 分离后，后续重切块和重建索引会更容易？" -ExpectedDocKeys @("ingestion_chunking") -AnswerKeywords @("文件存储", "向量索引", "重建索引"))
    (New-EvalCase -Id "eval_049" -Question "如果 Trace 里的 resultChunkIds 不能稳定命中目标文档，说明应该优先排哪一段？" -ExpectedDocKeys @("ingestion_chunking", "trace_dashboard") -AnswerKeywords @("resultChunkIds", "检索", "切块"))
    (New-EvalCase -Id "eval_050" -Question "为什么知识库导入链路本身也值得写进简历，而不只是写问答效果？" -ExpectedDocKeys @("ingestion_chunking", "project_overview") -AnswerKeywords @("导入链路", "切块", "向量检索"))

    (New-EvalCase -Id "eval_051" -Question "如果简历里只能保留一条 RAG 效果亮点，最推荐写什么？" -ExpectedDocKeys @("resume_highlights", "retrieval_pipeline") -AnswerKeywords @("双路检索", "Rerank", "Top-3"))
    (New-EvalCase -Id "eval_052" -Question "如果简历里只能保留一条后端性能亮点，最推荐写什么？" -ExpectedDocKeys @("resume_highlights", "queue_stability") -AnswerKeywords @("Redis", "SSE", "P95"))
    (New-EvalCase -Id "eval_053" -Question "如果简历里只能保留一条可观测性亮点，最推荐写什么？" -ExpectedDocKeys @("resume_highlights", "trace_dashboard") -AnswerKeywords @("Trace", "Dashboard", "成功率"))
    (New-EvalCase -Id "eval_054" -Question "推荐亮点一最适合回答哪些面试问题？" -ExpectedDocKeys @("resume_highlights") -AnswerKeywords @("召回率", "RAG", "量化"))
    (New-EvalCase -Id "eval_055" -Question "推荐亮点二最适合回答哪些面试问题？" -ExpectedDocKeys @("resume_highlights") -AnswerKeywords @("高并发", "限流", "Java 后端"))
    (New-EvalCase -Id "eval_056" -Question "推荐亮点三最适合回答哪些面试问题？" -ExpectedDocKeys @("resume_highlights") -AnswerKeywords @("线上观测", "排查", "可维护"))
    (New-EvalCase -Id "eval_057" -Question "如果面试深挖这个项目，回答顺序为什么建议先讲业务目标？" -ExpectedDocKeys @("resume_highlights", "project_overview") -AnswerKeywords @("业务目标", "企业知识问答", "聊天 Demo"))
    (New-EvalCase -Id "eval_058" -Question "为什么回答完业务目标后，第二步应该讲链路设计？" -ExpectedDocKeys @("resume_highlights", "retrieval_pipeline") -AnswerKeywords @("重写", "双路检索", "流式回答"))
    (New-EvalCase -Id "eval_059" -Question "为什么在讲完整体链路后，要再专门讲工程难点？" -ExpectedDocKeys @("resume_highlights", "queue_stability") -AnswerKeywords @("SSE", "并发", "限流"))
    (New-EvalCase -Id "eval_060" -Question "为什么这个项目只要把业务目标、链路、工程难点和数据四步讲顺，就会很像真实落地项目？" -ExpectedDocKeys @("resume_highlights", "project_overview", "trace_dashboard") -AnswerKeywords @("业务目标", "工程化", "量化数据"))
)

$dataset = [ordered]@{
    datasetName = "resume-rag-eval-$Version"
    topN        = 3
    cases       = $cases
}

$json = $dataset | ConvertTo-Json -Depth 8
Set-Content -LiteralPath $OutputPath -Value $json -Encoding UTF8

$outputDirectory = Split-Path -Parent $OutputPath
$outputName = [System.IO.Path]::GetFileNameWithoutExtension($OutputPath)
$versionedOutputPath = Join-Path $outputDirectory ($outputName + "-$Version.json")
Set-Content -LiteralPath $versionedOutputPath -Value $json -Encoding UTF8

Write-Host "Generated dataset: $OutputPath"
Write-Host "Generated dataset copy: $versionedOutputPath"
Write-Host "Case count: $($cases.Count)"


