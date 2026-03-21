[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:9090/api/ragent",
    [string]$Token,
    [string]$Username,
    [string]$Password,
    [string]$BenchmarkJsonPath,
    [string]$EvalJsonPath,
    [string]$Window = "24h"
)

. (Join-Path $PSScriptRoot "resume_common.ps1")

$BaseUrl = $BaseUrl.TrimEnd("/")
$tokenValue = Resolve-AuthToken -BaseUrl $BaseUrl -Token $Token -Username $Username -Password $Password
$headers = New-AuthHeaders -Token $tokenValue
$outputDir = Get-ResumeOutputDir

if ([string]::IsNullOrWhiteSpace($BenchmarkJsonPath)) {
    $BenchmarkJsonPath = Join-Path $outputDir "benchmark-latest.json"
}
if ([string]::IsNullOrWhiteSpace($EvalJsonPath)) {
    $EvalJsonPath = Join-Path $outputDir "retrieval-eval-latest.json"
}

$overview = Invoke-ApiJson -Method GET -Uri "$BaseUrl/admin/dashboard/overview?window=$([uri]::EscapeDataString($Window))" -Headers $headers
$performance = Invoke-ApiJson -Method GET -Uri "$BaseUrl/admin/dashboard/performance?window=$([uri]::EscapeDataString($Window))" -Headers $headers
$qualityTrends = Invoke-ApiJson -Method GET -Uri "$BaseUrl/admin/dashboard/trends?metric=quality&window=7d&granularity=day" -Headers $headers

$dashboardSnapshot = [ordered]@{
    generatedAt   = (Get-Date).ToString("s")
    overview      = $overview
    performance   = $performance
    qualityTrends = $qualityTrends
}

$dashboardJsonPath = Join-Path $outputDir "dashboard-snapshot.json"
Write-JsonFile -Path $dashboardJsonPath -Data $dashboardSnapshot

$benchmark = $null
if (Test-Path -LiteralPath $BenchmarkJsonPath) {
    $benchmark = Read-JsonFile -Path $BenchmarkJsonPath
}

$evaluation = $null
if (Test-Path -LiteralPath $EvalJsonPath) {
    $evaluation = Read-JsonFile -Path $EvalJsonPath
}

$bulletRecall = if ($null -ne $evaluation) {
    "Built dual retrieval and rerank with query rewrite and intent routing; on $($evaluation.caseCount) regression cases, Top-$($evaluation.topN) recall reached $($evaluation.recallAtNPercent)% and answer keyword coverage reached $($evaluation.avgKeywordCoveragePercent)%."
} else {
    "Built dual retrieval and rerank with query rewrite and intent routing for enterprise knowledge QA."
}

$bulletPerf = if ($null -ne $benchmark) {
    "Implemented Redis + Lua + ZSET based queue limiting and SSE resource protection; under $($benchmark.concurrency) concurrent clients and $($benchmark.requests) requests, single-node throughput reached about $($benchmark.throughputQps) QPS with avg RT $($benchmark.avgTotalMs) ms and P95 $($benchmark.p95TotalMs) ms."
} else {
    "Implemented Redis + Lua + ZSET based queue limiting and SSE resource protection for high-concurrency chat scenarios."
}

$bulletObs = "Built RAG trace and dashboard observability for latency, success rate, error rate, no-doc rate, and slow-request monitoring."

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("# Resume Metrics Summary") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("## Dashboard Snapshot") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("- Window: $Window") | Out-Null
$lines.Add("- Total Users: $($overview.kpis.totalUsers.value)") | Out-Null
$lines.Add("- Active Users: $($overview.kpis.activeUsers.value)") | Out-Null
$lines.Add("- Total Sessions: $($overview.kpis.totalSessions.value)") | Out-Null
$lines.Add("- Sessions In Window: $($overview.kpis.sessions24h.value)") | Out-Null
$lines.Add("- Total Messages: $($overview.kpis.totalMessages.value)") | Out-Null
$lines.Add("- Messages In Window: $($overview.kpis.messages24h.value)") | Out-Null
$lines.Add("- Avg Latency: $($performance.avgLatencyMs) ms") | Out-Null
$lines.Add("- P95 Latency: $($performance.p95LatencyMs) ms") | Out-Null
$lines.Add("- Success Rate: $($performance.successRate)%") | Out-Null
$lines.Add("- Error Rate: $($performance.errorRate)%") | Out-Null
$lines.Add("- No-Doc Rate: $($performance.noDocRate)%") | Out-Null
$lines.Add("- Slow Rate: $($performance.slowRate)%") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("## Benchmark Snapshot") | Out-Null
$lines.Add("") | Out-Null

if ($null -ne $benchmark) {
    $lines.Add("- Concurrency: $($benchmark.concurrency)") | Out-Null
    $lines.Add("- Requests: $($benchmark.requests)") | Out-Null
    $lines.Add("- Throughput: $($benchmark.throughputQps) QPS") | Out-Null
    $lines.Add("- Avg Total RT: $($benchmark.avgTotalMs) ms") | Out-Null
    $lines.Add("- P95 Total RT: $($benchmark.p95TotalMs) ms") | Out-Null
    $lines.Add("- Avg TTFB: $($benchmark.avgTtfbMs) ms") | Out-Null
    $lines.Add("- P95 TTFB: $($benchmark.p95TtfbMs) ms") | Out-Null
} else {
    $lines.Add("- Benchmark file not found. Run benchmark_rag_chat.ps1 first.") | Out-Null
}

$lines.Add("") | Out-Null
$lines.Add("## Retrieval Evaluation Snapshot") | Out-Null
$lines.Add("") | Out-Null

if ($null -ne $evaluation) {
    $lines.Add("- Dataset: $($evaluation.datasetName)") | Out-Null
    $lines.Add("- Case Count: $($evaluation.caseCount)") | Out-Null
    $lines.Add("- Top-$($evaluation.topN) Recall: $($evaluation.recallAtNPercent)%") | Out-Null
    $lines.Add("- Avg Keyword Coverage: $($evaluation.avgKeywordCoveragePercent)%") | Out-Null
} else {
    $lines.Add("- Evaluation file not found. Run evaluate_rag_retrieval.ps1 first.") | Out-Null
}

$lines.Add("") | Out-Null
$lines.Add("## Resume Ready Bullets") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("1. $bulletRecall") | Out-Null
$lines.Add("2. $bulletPerf") | Out-Null
$lines.Add("3. $bulletObs") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("## Recommended Next Optimizations") | Out-Null
$lines.Add("") | Out-Null
$lines.Add("1. Add queue wait time into trace and dashboard so queueing cost and model cost can be separated.") | Out-Null
$lines.Add("2. Record predicted and expected labels for intent resolution to compute intent accuracy.") | Out-Null
$lines.Add("3. Add human-scored answer labels to the regression set beyond keyword coverage.") | Out-Null

$summaryPath = Join-Path $outputDir "resume-metrics-summary.md"
Set-Content -LiteralPath $summaryPath -Value ($lines -join [Environment]::NewLine) -Encoding UTF8

Write-Host "Resume summary exported." -ForegroundColor Green
Write-Host "Dashboard JSON: $dashboardJsonPath"
Write-Host "Summary Markdown: $summaryPath"
