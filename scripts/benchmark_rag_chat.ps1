[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:9090/api/ragent",
    [string]$Token,
    [string]$Username,
    [string]$Password,
    [string]$Question = "Please answer in one sentence: what is the core idea of the dual-retrieval plus rerank pipeline in this project?",
    [int]$Concurrency = 5,
    [int]$Requests = 20,
    [switch]$DeepThinking
)

. (Join-Path $PSScriptRoot "resume_common.ps1")

$BaseUrl = $BaseUrl.TrimEnd("/")
$tokenValue = Resolve-AuthToken -BaseUrl $BaseUrl -Token $Token -Username $Username -Password $Password
$headers = New-AuthHeaders -Token $tokenValue
[void](Assert-ModelReady -BaseUrl $BaseUrl -Headers $headers -Group chat)
$outputDir = Get-ResumeOutputDir
$runId = (Get-Date).ToString("yyyyMMdd-HHmmss")
$runDir = Ensure-Directory -Path (Join-Path $outputDir ("benchmark-run-" + $runId))
$writeOut = "http_code=%{http_code}`ntime_starttransfer=%{time_starttransfer}`ntime_total=%{time_total}`nsize_download=%{size_download}`n"

function Parse-BenchmarkJobResult {
    param(
        [Parameter(Mandatory = $true)]
        [object]$JobItem
    )

    Receive-Job -Job $JobItem.Job | Out-Null
    Remove-Job -Job $JobItem.Job -Force

    $metricMap = @{}
    $exitCode = -1
    if (Test-Path -LiteralPath $JobItem.MetricsFile) {
        foreach ($line in Get-Content -LiteralPath $JobItem.MetricsFile -Encoding UTF8) {
            if ($line -match "^EXIT=(?<value>-?\d+)$") {
                [void][int]::TryParse([string]$matches["value"], [ref]$exitCode)
                continue
            }
            if ($line -match "^(?<key>[^=]+)=(?<value>.*)$") {
                $metricMap[$matches["key"]] = $matches["value"]
            }
        }
    }

    $bodyText = ""
    if (Test-Path -LiteralPath $JobItem.BodyFile) {
        $bodyText = Get-Content -LiteralPath $JobItem.BodyFile -Raw -Encoding UTF8
    }

    $httpCode = 0
    if ($metricMap.ContainsKey("http_code")) {
        [void][int]::TryParse([string]$metricMap["http_code"], [ref]$httpCode)
    }

    $timeTotalMs = 0.0
    if ($metricMap.ContainsKey("time_total")) {
        $timeTotalMs = [math]::Round(([double]$metricMap["time_total"] * 1000.0), 2)
    }

    $ttfbMs = 0.0
    if ($metricMap.ContainsKey("time_starttransfer")) {
        $ttfbMs = [math]::Round(([double]$metricMap["time_starttransfer"] * 1000.0), 2)
    }

    $downloadBytes = 0
    if ($metricMap.ContainsKey("size_download")) {
        [void][int]::TryParse([string]$metricMap["size_download"], [ref]$downloadBytes)
    }

    $trimmedBody = $bodyText.Trim()
    $jsonErrorCode = $null
    $jsonErrorMessage = $null
    $jsonSuccess = $null
    if (-not [string]::IsNullOrWhiteSpace($trimmedBody) -and $trimmedBody.StartsWith("{")) {
        try {
            $jsonPayload = $trimmedBody | ConvertFrom-Json
            $codeProperty = $jsonPayload.PSObject.Properties["code"]
            $messageProperty = $jsonPayload.PSObject.Properties["message"]
            $successProperty = $jsonPayload.PSObject.Properties["success"]
            if ($null -ne $codeProperty) {
                $jsonErrorCode = [string]$codeProperty.Value
            }
            if ($null -ne $messageProperty) {
                $jsonErrorMessage = [string]$messageProperty.Value
            }
            if ($null -ne $successProperty) {
                $jsonSuccess = [bool]$successProperty.Value
            }
        } catch {
        }
    }

    $done = $bodyText -match "event:\s*done" -or $bodyText -match "\[DONE\]"
    $rejected = $bodyText -match "event:\s*reject"
    $hasErrorEvent = $bodyText -match "event:\s*error"
    $success = ($exitCode -eq 0 -and $httpCode -eq 200 -and $done -and -not $rejected -and -not $hasErrorEvent -and ($null -eq $jsonSuccess -or $jsonSuccess))

    return [pscustomobject]@{
        requestIndex       = $JobItem.RequestIndex
        conversationId     = $JobItem.ConversationId
        exitCode           = $exitCode
        httpCode           = $httpCode
        success            = $success
        rejected           = $rejected
        hasErrorEvent      = $hasErrorEvent
        timeTotalMs        = $timeTotalMs
        timeToFirstByteMs  = $ttfbMs
        sizeDownload       = $downloadBytes
        bodyFile           = $JobItem.BodyFile
        metricsFile        = $JobItem.MetricsFile
        errorCode          = $jsonErrorCode
        errorMessage       = $jsonErrorMessage
    }
}

$requestScriptBlock = {
    param($RequestBaseUrl, $RequestToken, $RequestQuestion, $RequestConversationId, $RequestBodyFile, $RequestMetricsFile, $RequestWriteOut, $RequestDeepThinking)

    $arguments = @(
        "-sS",
        "-N",
        "--no-buffer",
        "--get",
        "-H", "Authorization: $RequestToken",
        "--data-urlencode", ("question={0}" -f $RequestQuestion),
        "--data-urlencode", ("conversationId={0}" -f $RequestConversationId),
        "--data-urlencode", ("deepThinking={0}" -f $RequestDeepThinking),
        "--output", $RequestBodyFile,
        "--write-out", $RequestWriteOut,
        ("{0}/rag/v3/chat" -f $RequestBaseUrl)
    )

    $metricsRaw = & curl.exe @arguments 2>&1
    $exitCode = $LASTEXITCODE
    Set-Content -LiteralPath $RequestMetricsFile -Value (@("EXIT=$exitCode") + @($metricsRaw)) -Encoding UTF8
}

$jobs = @()
$results = @()
$wallStart = Get-Date

for ($i = 1; $i -le $Requests; $i++) {
    while (@($jobs | Where-Object { $_.Job.State -eq "Running" }).Count -ge $Concurrency) {
        $finished = Wait-Job -Job @($jobs | ForEach-Object { $_.Job }) -Any -Timeout 2
        if ($null -ne $finished) {
            $finishedItem = @($jobs | Where-Object { $_.Job.Id -eq $finished.Id }) | Select-Object -First 1
            if ($null -ne $finishedItem) {
                $results += (Parse-BenchmarkJobResult -JobItem $finishedItem)
                $jobs = @($jobs | Where-Object { $_.Job.Id -ne $finished.Id })
            }
        }
    }

    $conversationId = "resume-bench-{0}-{1}" -f $i, ([guid]::NewGuid().ToString("N").Substring(0, 8))
    $bodyFile = Join-Path $runDir ("request-{0}.sse.log" -f $i)
    $metricsFile = Join-Path $runDir ("request-{0}.metrics.txt" -f $i)
    $deepThinkingValue = ([bool]$DeepThinking).ToString().ToLowerInvariant()
    $job = Start-Job -Name ("resume-bench-" + $runId + "-" + $i) -ScriptBlock $requestScriptBlock -ArgumentList $BaseUrl, $tokenValue, $Question, $conversationId, $bodyFile, $metricsFile, $writeOut, $deepThinkingValue

    $jobs += [pscustomobject]@{
        Job            = $job
        RequestIndex   = $i
        ConversationId = $conversationId
        BodyFile       = $bodyFile
        MetricsFile    = $metricsFile
    }
}

while ($jobs.Count -gt 0) {
    $finished = Wait-Job -Job @($jobs | ForEach-Object { $_.Job }) -Any
    $finishedItem = @($jobs | Where-Object { $_.Job.Id -eq $finished.Id }) | Select-Object -First 1
    if ($null -ne $finishedItem) {
        $results += (Parse-BenchmarkJobResult -JobItem $finishedItem)
        $jobs = @($jobs | Where-Object { $_.Job.Id -ne $finished.Id })
    }
}

$wallEnd = Get-Date
$wallSeconds = [math]::Max(0.001, ($wallEnd - $wallStart).TotalSeconds)
$resultArray = @($results)
$successResults = @($resultArray | Where-Object { $_.success })
$failedResults = @($resultArray | Where-Object { -not $_.success })
$rejectedResults = @($resultArray | Where-Object { $_.rejected })
$errorCodeValues = @(
    $resultArray |
        Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_.errorCode) } |
        ForEach-Object { [string]$_.errorCode }
)
$errorCodeSummary = @($errorCodeValues | Group-Object | Sort-Object Name | ForEach-Object { "{0} x{1}" -f $_.Name, $_.Count })
$totalTimes = [double[]]@($resultArray | ForEach-Object { [double]$_.timeTotalMs })
$ttfbTimes = [double[]]@($resultArray | ForEach-Object { [double]$_.timeToFirstByteMs })

$summary = [ordered]@{
    generatedAt     = (Get-Date).ToString("s")
    baseUrl         = $BaseUrl
    question        = $Question
    concurrency     = $Concurrency
    requests        = $Requests
    successCount    = $successResults.Count
    failureCount    = $failedResults.Count
    wallTimeSeconds = [math]::Round($wallSeconds, 2)
    throughputQps   = [math]::Round(($successResults.Count / $wallSeconds), 2)
    avgTotalMs      = Get-Average -Values $totalTimes
    p95TotalMs      = Get-Percentile -Values $totalTimes -Percentile 95
    avgTtfbMs       = Get-Average -Values $ttfbTimes
    p95TtfbMs       = Get-Percentile -Values $ttfbTimes -Percentile 95
    results         = $resultArray
}

$jsonPath = Join-Path $outputDir ("benchmark-" + $runId + ".json")
$latestJsonPath = Join-Path $outputDir "benchmark-latest.json"
$mdPath = Join-Path $outputDir ("benchmark-" + $runId + ".md")
$latestMdPath = Join-Path $outputDir "benchmark-latest.md"

$markdown = @"
# Benchmark Summary

- Base URL: $BaseUrl
- Question: $Question
- Concurrency: $Concurrency
- Requests: $Requests
- Success: $($summary.successCount)
- Failure: $($summary.failureCount)
- Rejected: $($rejectedResults.Count)
- Wall Time: $($summary.wallTimeSeconds)s
- Throughput: $($summary.throughputQps) QPS
- Avg Total RT: $($summary.avgTotalMs) ms
- P95 Total RT: $($summary.p95TotalMs) ms
- Avg TTFB: $($summary.avgTtfbMs) ms
- P95 TTFB: $($summary.p95TtfbMs) ms
"@

if ($errorCodeSummary.Count -gt 0) {
    $markdown += "`n- Error Codes: $($errorCodeSummary -join ', ')"
}

Write-JsonFile -Path $jsonPath -Data $summary
Write-JsonFile -Path $latestJsonPath -Data $summary
Set-Content -LiteralPath $mdPath -Value $markdown -Encoding UTF8
Set-Content -LiteralPath $latestMdPath -Value $markdown -Encoding UTF8

Write-Host "Benchmark completed." -ForegroundColor Green
Write-Host "JSON: $jsonPath"
Write-Host "Markdown: $mdPath"
