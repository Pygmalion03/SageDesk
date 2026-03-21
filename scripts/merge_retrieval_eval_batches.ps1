[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$InputFiles,
    [string]$OutputPrefix = "retrieval-eval-merged"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "resume_common.ps1")

if ($InputFiles.Count -eq 1 -and $InputFiles[0] -like "*,*") {
    $InputFiles = @($InputFiles[0].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

if ($InputFiles.Count -eq 0) {
    throw "InputFiles cannot be empty."
}

$outputDir = Get-ResumeOutputDir
$existingFiles = @()
foreach ($file in $InputFiles) {
    if (-not (Test-Path -LiteralPath $file)) {
        throw "Batch result not found: $file"
    }
    $existingFiles += $file
}

$batchResults = @($existingFiles | ForEach-Object {
        Read-JsonFile -Path $_
    })
$allCases = @($batchResults | ForEach-Object { $_.cases })
$hits = @($allCases | Where-Object { $_.recallHit })
$failed = @($allCases | Where-Object { -not $_.recallHit })

$batches = @()
for ($index = 0; $index -lt $batchResults.Count; $index++) {
    $batch = $batchResults[$index]
    $startIndexProperty = $batch.PSObject.Properties["startCaseIndex"]
    $startCaseIndex = if ($null -ne $startIndexProperty) { $startIndexProperty.Value } else { $null }
    if ($null -eq $startCaseIndex) {
        $startCaseIndex = (($index) * [int]$batch.caseCount) + 1
    }

    $batches += [pscustomobject]@{
        startCaseIndex    = [int]$startCaseIndex
        caseCount         = [int]$batch.caseCount
        recallAtNPercent  = [double]$batch.recallAtNPercent
    }
}

$summary = [pscustomobject]@{
    generatedAt     = (Get-Date).ToString("s")
    datasetName     = [string]$batchResults[0].datasetName
    evaluationMode  = [string]$batchResults[0].evaluationMode
    batchCount      = [int]$batchResults.Count
    caseCount       = [int]$allCases.Count
    topN            = [int]$batchResults[0].topN
    recallAtNPercent = [double]([math]::Round((@($hits).Count / [math]::Max(1, $allCases.Count)) * 100.0, 2))
    hitCount        = [int]$hits.Count
    missCount       = [int]$failed.Count
    failedCaseIds   = @($failed | ForEach-Object { [string]$_.id })
    batches         = $batches
}

$jsonPath = Join-Path $outputDir ($OutputPrefix + ".json")
$mdPath = Join-Path $outputDir ($OutputPrefix + ".md")

$lines = @(
    "# Retrieval Evaluation Summary",
    "",
    ("- Dataset: {0}" -f $summary.datasetName),
    ("- Evaluation Mode: {0}" -f $summary.evaluationMode),
    ("- Batch Count: {0}" -f $summary.batchCount),
    ("- Case Count: {0}" -f $summary.caseCount),
    ("- Top-{0} Recall: {1}%" -f $summary.topN, $summary.recallAtNPercent),
    ("- Hit Count: {0}" -f $summary.hitCount),
    ("- Miss Count: {0}" -f $summary.missCount),
    ("- Failed Case IDs: {0}" -f ([string]::Join(", ", $summary.failedCaseIds)))
)

Write-JsonFile -Path $jsonPath -Data $summary
Set-Content -LiteralPath $mdPath -Value $lines -Encoding UTF8

Write-Host "Merged retrieval evaluation summary generated." -ForegroundColor Green
Write-Host "JSON: $jsonPath"
Write-Host "Markdown: $mdPath"
