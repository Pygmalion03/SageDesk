[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:9090/api/ragent",
    [string]$Token,
    [string]$Username,
    [string]$Password,
    [string]$DatasetPath,
    [string]$ManifestPath,
    [int]$TracePollTimeoutSeconds = 60,
    [int]$MaxCases = 0,
    [int]$StartCaseIndex = 1,
    [switch]$RetrievalOnly
)

. (Join-Path $PSScriptRoot "resume_common.ps1")

$BaseUrl = $BaseUrl.TrimEnd("/")
$tokenValue = Resolve-AuthToken -BaseUrl $BaseUrl -Token $Token -Username $Username -Password $Password
$headers = New-AuthHeaders -Token $tokenValue
[void](Assert-ModelReady -BaseUrl $BaseUrl -Headers $headers -Group chat)
$resumeKitRoot = Get-ResumeKitRoot
$outputDir = Get-ResumeOutputDir
$datasetFile = if ([string]::IsNullOrWhiteSpace($DatasetPath)) { Join-Path $resumeKitRoot "rag-eval-dataset.json" } else { $DatasetPath }
$manifestFile = if ([string]::IsNullOrWhiteSpace($ManifestPath)) { Join-Path $outputDir "import-manifest.json" } else { $ManifestPath }

if (-not (Test-Path -LiteralPath $manifestFile)) {
    throw "Import manifest not found: $manifestFile"
}

$dataset = Read-JsonFile -Path $datasetFile
$manifest = Read-JsonFile -Path $manifestFile
$runId = (Get-Date).ToString("yyyyMMdd-HHmmss")
$runDir = Ensure-Directory -Path (Join-Path $outputDir ("retrieval-eval-run-" + $runId))

function Invoke-SseCase {
    param(
        [string]$Question,
        [string]$ConversationId,
        [string]$OutputFile
    )

    $arguments = @(
        "-sS",
        "-N",
        "--no-buffer",
        "--get",
        "-H", "Authorization: $tokenValue",
        "--data-urlencode", ("question={0}" -f $Question),
        "--data-urlencode", ("conversationId={0}" -f $ConversationId),
        "--data-urlencode", "deepThinking=false",
        "--output", $OutputFile,
        ("{0}/rag/v3/chat" -f $BaseUrl)
    )

    & curl.exe @arguments | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "curl request failed for conversation: $ConversationId"
    }
}

function Start-SseCaseAsync {
    param(
        [string]$Question,
        [string]$ConversationId,
        [string]$OutputFile
    )

    return Start-Job -ScriptBlock {
        param(
            [string]$InnerBaseUrl,
            [string]$InnerToken,
            [string]$InnerQuestion,
            [string]$InnerConversationId,
            [string]$InnerOutputFile
        )

        $arguments = @(
            "-sS",
            "-N",
            "--no-buffer",
            "--get",
            "-H", "Authorization: $InnerToken",
            "--data-urlencode", ("question={0}" -f $InnerQuestion),
            "--data-urlencode", ("conversationId={0}" -f $InnerConversationId),
            "--data-urlencode", "deepThinking=false",
            "--output", $InnerOutputFile,
            ("{0}/rag/v3/chat" -f $InnerBaseUrl)
        )

        & curl.exe @arguments 2>$null | Out-Null
        return $LASTEXITCODE
    } -ArgumentList $BaseUrl, $tokenValue, $Question, $ConversationId, $OutputFile
}

function Complete-SseJob {
    param(
        [Parameter(Mandatory = $true)]
        [System.Management.Automation.Job]$Job,
        [string]$ConversationId,
        [int]$TimeoutSeconds = 30,
        [switch]$AllowInterruptedStream
    )

    $null = Wait-Job -Job $Job -Timeout $TimeoutSeconds
    if ($Job.State -eq "Running") {
        Stop-Job -Job $Job | Out-Null
        throw "Timed out waiting for SSE stream to finish after retrieval stop: $ConversationId"
    }

    $exitCodes = @(Receive-Job -Job $Job -Keep)
    if ($Job.State -eq "Failed") {
        $jobError = ($Job.ChildJobs | Select-Object -First 1).JobStateInfo.Reason
        throw "Async curl job failed for conversation: $ConversationId. $jobError"
    }

    if ($exitCodes.Count -gt 0) {
        $lastExitCode = [int]$exitCodes[-1]
        if ($AllowInterruptedStream -and $lastExitCode -eq 18) {
            return
        }
    }

    if ($exitCodes.Count -gt 0 -and [int]$exitCodes[-1] -ne 0) {
        throw "curl request failed for conversation: $ConversationId"
    }
}

function Stop-TraceTask {
    param([string]$TaskId)

    if ([string]::IsNullOrWhiteSpace($TaskId)) {
        return
    }

    try {
        [void](Invoke-ApiJson -Method POST -Uri "$BaseUrl/rag/v3/stop?taskId=$([uri]::EscapeDataString($TaskId))" -Headers $headers)
    } catch {
        Write-Warning ("Failed to stop task {0}: {1}" -f $TaskId, $_.Exception.Message)
    }
}

function Get-TraceDetailByConversation {
    param(
        [string]$ConversationId,
        [switch]$RequireRetrieveNodeReady
    )

    $deadline = (Get-Date).AddSeconds($TracePollTimeoutSeconds)
    $lastDetail = $null
    while ((Get-Date) -lt $deadline) {
        $uri = "$BaseUrl/rag/traces/runs?current=1&size=10&conversationId=$([uri]::EscapeDataString($ConversationId))"
        $page = Invoke-ApiJson -Method GET -Uri $uri -Headers $headers
        $records = @($page.records)
        if ($records.Count -gt 0) {
            $traceId = [string]$records[0].traceId
            $detail = Invoke-ApiJson -Method GET -Uri "$BaseUrl/rag/traces/runs/$traceId" -Headers $headers
            $lastDetail = $detail
            if ($detail.run.status -ne "RUNNING") {
                return $detail
            }

            $retrieveNode = @($detail.nodes | Where-Object {
                    $_.nodeType -eq "RETRIEVE_CHANNEL" -or $_.nodeName -eq "multi-channel-retrieval"
                }) | Select-Object -First 1
            if ($null -ne $retrieveNode -and $retrieveNode.status -ne "RUNNING") {
                return $detail
            }
        }
        Start-Sleep -Seconds 2
    }

    if ($RequireRetrieveNodeReady) {
        throw "Timed out waiting for retrieval node detail: $ConversationId"
    }

    if ($null -ne $lastDetail) {
        return $lastDetail
    }

    throw "Timed out waiting for trace detail: $ConversationId"
}

function Parse-AnswerText {
    param([string]$Path)

    $builder = New-Object System.Text.StringBuilder
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if (-not $line.StartsWith("data:")) {
            continue
        }

        $payload = $line.Substring(5).Trim()
        if ([string]::IsNullOrWhiteSpace($payload) -or $payload -eq "[DONE]") {
            continue
        }

        if (-not $payload.StartsWith("{")) {
            continue
        }

        try {
            $json = $payload | ConvertFrom-Json
            $typeProperty = $json.PSObject.Properties["type"]
            $deltaProperty = $json.PSObject.Properties["delta"]
            $contentProperty = $json.PSObject.Properties["content"]
            if ($null -ne $typeProperty -and $typeProperty.Value -eq "response") {
                if ($null -ne $deltaProperty -and $null -ne $deltaProperty.Value) {
                    [void]$builder.Append([string]$deltaProperty.Value)
                } elseif ($null -ne $contentProperty -and $null -ne $contentProperty.Value) {
                    [void]$builder.Append([string]$contentProperty.Value)
                }
            }
        } catch {
        }
    }

    return $builder.ToString()
}

function Resolve-ExpectedDocIds {
    param([string[]]$DocKeys)

    $result = New-Object System.Collections.Generic.List[string]
    foreach ($docKey in $DocKeys) {
        $docProperty = $manifest.documentIndex.PSObject.Properties[$docKey]
        if ($null -ne $docProperty) {
            $result.Add([string]$docProperty.Value.docId) | Out-Null
        }
    }
    return $result
}

$docChunkCache = @{}

function Get-DocumentChunkIds {
    param([string]$DocId)

    if ([string]::IsNullOrWhiteSpace($DocId)) {
        return @()
    }

    if ($docChunkCache.ContainsKey($DocId)) {
        return @($docChunkCache[$DocId])
    }

    $page = Invoke-ApiJson -Method GET -Uri "$BaseUrl/knowledge-base/docs/$DocId/chunks?pageNo=1&pageSize=1000" -Headers $headers
    $chunkIds = @($page.records | ForEach-Object { [string]$_.id })
    $docChunkCache[$DocId] = $chunkIds
    return $chunkIds
}

function Resolve-ExpectedChunkIds {
    param([string[]]$DocKeys)

    $expectedDocIds = @(Resolve-ExpectedDocIds -DocKeys $DocKeys)
    $chunkIds = New-Object System.Collections.Generic.List[string]
    foreach ($docId in $expectedDocIds) {
        foreach ($chunkId in @(Get-DocumentChunkIds -DocId $docId)) {
            $chunkIds.Add($chunkId) | Out-Null
        }
    }
    return $chunkIds
}

$caseResults = @()
$datasetTopNProperty = $dataset.PSObject.Properties["topN"]
$defaultTopN = if ($null -ne $datasetTopNProperty -and $null -ne $datasetTopNProperty.Value) { [int]$datasetTopNProperty.Value } else { 3 }

$datasetCases = @($dataset.cases)
if ($StartCaseIndex -lt 1) {
    throw "StartCaseIndex must be greater than or equal to 1."
}
if ($StartCaseIndex -gt 1) {
    $datasetCases = @($datasetCases | Select-Object -Skip ($StartCaseIndex - 1))
}
if ($MaxCases -gt 0) {
    $datasetCases = @($datasetCases | Select-Object -First $MaxCases)
}

foreach ($caseItem in $datasetCases) {
    $conversationId = "resume-eval-{0}-{1}" -f $caseItem.id, ([guid]::NewGuid().ToString("N").Substring(0, 8))
    $sseFile = Join-Path $runDir ($caseItem.id + ".sse.log")

    $detail = $null
    $sseJob = $null
    try {
        if ($RetrievalOnly) {
            $sseJob = Start-SseCaseAsync -Question $caseItem.question -ConversationId $conversationId -OutputFile $sseFile
            $detail = Get-TraceDetailByConversation -ConversationId $conversationId -RequireRetrieveNodeReady
            if ($detail.run.status -eq "RUNNING") {
                Stop-TraceTask -TaskId ([string]$detail.run.taskId)
            }
            Complete-SseJob -Job $sseJob -ConversationId $conversationId -AllowInterruptedStream
        } else {
            Invoke-SseCase -Question $caseItem.question -ConversationId $conversationId -OutputFile $sseFile
            $detail = Get-TraceDetailByConversation -ConversationId $conversationId
        }
    } finally {
        if ($null -ne $sseJob) {
            Remove-Job -Job $sseJob -Force -ErrorAction SilentlyContinue | Out-Null
        }
    }

    $retrieveNode = @($detail.nodes | Where-Object { $_.nodeType -eq "RETRIEVE_CHANNEL" -or $_.nodeName -eq "multi-channel-retrieval" }) |
        Select-Object -First 1

    $retrieveExtra = $null
    if ($null -ne $retrieveNode -and -not [string]::IsNullOrWhiteSpace([string]$retrieveNode.extraData)) {
        $retrieveExtra = $retrieveNode.extraData | ConvertFrom-Json
    }

    $topNProperty = $caseItem.PSObject.Properties["topN"]
    $topN = if ($null -ne $topNProperty -and $null -ne $topNProperty.Value) { [int]$topNProperty.Value } else { $defaultTopN }
    $returnedIds = @()
    if ($null -ne $retrieveExtra) {
        $resultChunksProperty = $retrieveExtra.PSObject.Properties["resultChunks"]
        $resultChunkIdsProperty = $retrieveExtra.PSObject.Properties["resultChunkIds"]
        if ($null -ne $resultChunksProperty -and $null -ne $resultChunksProperty.Value) {
            $returnedIds = @($resultChunksProperty.Value | ForEach-Object { [string]$_.id })
        } elseif ($null -ne $resultChunkIdsProperty -and $null -ne $resultChunkIdsProperty.Value) {
            $returnedIds = @($resultChunkIdsProperty.Value | ForEach-Object { [string]$_ })
        }
    }

    $topIds = @($returnedIds | Select-Object -First $topN)
    $expectedIds = @(Resolve-ExpectedDocIds -DocKeys @($caseItem.expectedDocKeys))
    $expectedChunkIds = @(Resolve-ExpectedChunkIds -DocKeys @($caseItem.expectedDocKeys))
    $matchedIds = @($topIds | Where-Object { $expectedChunkIds -contains $_ })
    $recallHit = ($matchedIds.Count -gt 0)

    $keywords = @($caseItem.answerKeywords)
    $matchedKeywords = @()
    $coverage = $null
    if (-not $RetrievalOnly) {
        $answerText = Parse-AnswerText -Path $sseFile
        foreach ($keyword in $keywords) {
            if ($answerText -like ("*" + $keyword + "*")) {
                $matchedKeywords += $keyword
            }
        }

        if ($keywords.Count -gt 0) {
            $coverage = [double]([math]::Round(($matchedKeywords.Count / $keywords.Count) * 100.0, 2))
        } else {
            $coverage = 0.0
        }
    }

    $caseResults += [pscustomobject]@{
            id                 = $caseItem.id
            question           = $caseItem.question
            traceId            = $detail.run.traceId
            taskId             = $detail.run.taskId
            status             = $detail.run.status
            topN               = $topN
            expectedDocKeys    = @($caseItem.expectedDocKeys)
            expectedDocIds     = @($expectedIds)
            expectedChunkIds   = @($expectedChunkIds)
            returnedChunkIds   = @($topIds)
            recallHit          = $recallHit
            matchedChunkIds    = @($matchedIds)
            keywordCoveragePct = $coverage
            matchedKeywords    = @($matchedKeywords)
            sseFile            = $sseFile
        }
}

$caseArray = @($caseResults)
$recallHits = @($caseArray | Where-Object { $_.recallHit })
$coverageValues = if ($RetrievalOnly) {
    @()
} else {
    [double[]]@($caseArray | ForEach-Object { [double]$_.keywordCoveragePct })
}
$avgKeywordCoverage = if ($RetrievalOnly) { $null } else { [double](Get-Average -Values $coverageValues) }
$evaluationMode = if ($RetrievalOnly) { "retrieval-only" } else { "full-chat" }

$summary = [pscustomobject]@{
    generatedAt               = (Get-Date).ToString("s")
    datasetName               = [string]$dataset.datasetName
    evaluationMode            = $evaluationMode
    caseCount                 = [int]$caseArray.Count
    startCaseIndex            = [int]$StartCaseIndex
    topN                      = [int]$defaultTopN
    recallAtNPercent          = [double]([math]::Round((@($recallHits).Count / [math]::Max(1, $caseArray.Count)) * 100.0, 2))
    avgKeywordCoveragePercent = $avgKeywordCoverage
    cases                     = $caseArray
}

$jsonPath = Join-Path $outputDir ("retrieval-eval-" + $runId + ".json")
$latestJsonPath = Join-Path $outputDir "retrieval-eval-latest.json"
$mdPath = Join-Path $outputDir ("retrieval-eval-" + $runId + ".md")
$latestMdPath = Join-Path $outputDir "retrieval-eval-latest.md"

$coverageLine = if ($RetrievalOnly) { "N/A (retrieval-only mode)" } else { "$($summary.avgKeywordCoveragePercent)%" }

$markdown = @"
# Retrieval Evaluation Summary

- Dataset: $($summary.datasetName)
- Evaluation Mode: $($summary.evaluationMode)
- Start Case Index: $($summary.startCaseIndex)
- Case Count: $($summary.caseCount)
- Top-$($summary.topN) Recall: $($summary.recallAtNPercent)%
- Avg Keyword Coverage: $coverageLine
"@

Write-JsonFile -Path $jsonPath -Data $summary
Write-JsonFile -Path $latestJsonPath -Data $summary
Set-Content -LiteralPath $mdPath -Value $markdown -Encoding UTF8
Set-Content -LiteralPath $latestMdPath -Value $markdown -Encoding UTF8

Write-Host "Retrieval evaluation completed." -ForegroundColor Green
Write-Host "JSON: $jsonPath"
Write-Host "Markdown: $mdPath"
