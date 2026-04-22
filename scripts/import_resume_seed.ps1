[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:9090/api/ragent",
    [string]$Token,
    [string]$Username,
    [string]$Password,
    [string]$EmbeddingModel,
    [string]$NameSuffix = "demo",
    [switch]$SkipChunk,
    [int]$ChunkTimeoutSeconds = 900
)

. (Join-Path $PSScriptRoot "resume_common.ps1")

$BaseUrl = $BaseUrl.TrimEnd("/")
$tokenValue = Resolve-AuthToken -BaseUrl $BaseUrl -Token $Token -Username $Username -Password $Password
$headers = New-AuthHeaders -Token $tokenValue
$resumeKitRoot = Get-ResumeKitRoot
$outputDir = Get-ResumeOutputDir
$kbSeed = Read-JsonFile -Path (Join-Path $resumeKitRoot "knowledge-bases.json")
$intentSeed = Read-JsonFile -Path (Join-Path $resumeKitRoot "intent-tree.json")
$sampleSeed = Read-JsonFile -Path (Join-Path $resumeKitRoot "sample-questions.json")
$safeSuffix = Sanitize-CollectionSuffix -Value $NameSuffix

function Get-ExistingKnowledgeBase {
    param([string]$Name)

    $uri = "$BaseUrl/knowledge-base?current=1&size=100&name=$([uri]::EscapeDataString($Name))"
    $page = Invoke-ApiJson -Method GET -Uri $uri -Headers $headers
    foreach ($record in @($page.records)) {
        if ($record.name -eq $Name) {
            return $record
        }
    }
    return $null
}

function Get-ExistingDocuments {
    param([string]$KbId)

    $uri = "$BaseUrl/knowledge-base/$KbId/docs?pageNo=1&pageSize=200"
    $page = Invoke-ApiJson -Method GET -Uri $uri -Headers $headers
    $map = @{}
    foreach ($record in @($page.records)) {
        $map[$record.docName] = $record
    }
    return $map
}

function Wait-DocumentReady {
    param(
        [string]$DocId,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $doc = Invoke-ApiJson -Method GET -Uri "$BaseUrl/knowledge-base/docs/$DocId" -Headers $headers
        if ($doc.status -eq "success") {
            return $doc
        }
        if ($doc.status -eq "failed") {
            throw "Document chunking failed: $DocId"
        }
        Start-Sleep -Seconds 2
    }

    throw "Timed out waiting for document chunking: $DocId"
}

function Upload-Document {
    param(
        [string]$KbId,
        [psobject]$DocumentConfig
    )

    $filePath = Join-Path $resumeKitRoot $DocumentConfig.path
    if (-not (Test-Path -LiteralPath $filePath)) {
        throw "Document file not found: $filePath"
    }

    $arguments = @(
        "-sS",
        "-X", "POST",
        "-H", "Authorization: $tokenValue",
        "-F", "sourceType=file",
        "-F", "processMode=$($DocumentConfig.processMode)",
        "-F", "chunkStrategy=$($DocumentConfig.chunkStrategy)"
    )

    foreach ($field in "targetChars", "maxChars", "minChars", "overlapChars", "chunkSize", "overlapSize") {
        $property = $DocumentConfig.PSObject.Properties[$field]
        if ($null -ne $property -and $null -ne $property.Value) {
            $arguments += @("-F", ("{0}={1}" -f $field, $property.Value))
        }
    }

    $arguments += @("-F", "file=@$filePath", "$BaseUrl/knowledge-base/$KbId/docs/upload")

    $raw = & curl.exe @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "curl upload failed for $filePath"
    }

    $response = $raw | ConvertFrom-Json
    if ("$($response.code)" -ne "0") {
        throw "Upload failed for ${filePath}: $($response.message)"
    }

    return $response.data
}

function Start-ChunkIfNeeded {
    param(
        [string]$DocId,
        [string]$Status
    )

    if ($SkipChunk) {
        return
    }

    if ($Status -eq "success" -or $Status -eq "running") {
        return
    }

    [void](Invoke-ApiJson -Method POST -Uri "$BaseUrl/knowledge-base/docs/$DocId/chunk" -Headers $headers)
}

function Build-KbName {
    param([string]$BaseName)

    if ([string]::IsNullOrWhiteSpace($NameSuffix)) {
        return $BaseName
    }
    return "$BaseName [$NameSuffix]"
}

function Build-CollectionName {
    param([string]$BaseName)

    if ([string]::IsNullOrWhiteSpace($safeSuffix)) {
        return $BaseName
    }
    return ($BaseName + $safeSuffix)
}

$manifest = [ordered]@{
    generatedAt     = (Get-Date).ToString("s")
    baseUrl         = $BaseUrl
    nameSuffix      = $NameSuffix
    knowledgeBases  = [ordered]@{}
    documentIndex   = [ordered]@{}
    intentNodes     = [ordered]@{}
    sampleQuestions = [ordered]@{}
}

foreach ($kb in @($kbSeed.knowledgeBases)) {
    $kbName = Build-KbName -BaseName $kb.name
    $collectionName = Build-CollectionName -BaseName $kb.collectionName
    $requestedEmbeddingModel = if ([string]::IsNullOrWhiteSpace($EmbeddingModel)) { [string]$kb.embeddingModel } else { $EmbeddingModel }
    $resolvedEmbedding = Assert-ModelReady -BaseUrl $BaseUrl -Headers $headers -Group embedding -RequestedModel $requestedEmbeddingModel
    $resolvedEmbeddingModel = [string]$resolvedEmbedding.id
    $kbRecord = Get-ExistingKnowledgeBase -Name $kbName

    if ($null -eq $kbRecord) {
        $createBody = @{
            name = $kbName
            embeddingModel = $resolvedEmbeddingModel
            collectionName = $collectionName
        }
        $kbId = [string](Invoke-ApiJson -Method POST -Uri "$BaseUrl/knowledge-base" -Headers $headers -Body $createBody)
        $kbRecord = Invoke-ApiJson -Method GET -Uri "$BaseUrl/knowledge-base/$kbId" -Headers $headers
    } elseif ([string]$kbRecord.embeddingModel -ne $resolvedEmbeddingModel) {
        throw "Knowledge base '$kbName' already exists with embedding model '$($kbRecord.embeddingModel)', but this import resolved to '$resolvedEmbeddingModel'. Use a new -NameSuffix or delete and recreate that knowledge base."
    }

    $manifest.knowledgeBases[$kb.key] = [ordered]@{
        id             = [string]$kbRecord.id
        name           = $kbRecord.name
        embeddingModel = [string]$kbRecord.embeddingModel
        collectionName = $kbRecord.collectionName
        documents      = [ordered]@{}
    }

    $existingDocs = Get-ExistingDocuments -KbId ([string]$kbRecord.id)
    foreach ($doc in @($kb.documents)) {
        $expectedDocName = Split-Path -Path $doc.path -Leaf
        $currentDoc = $null
        if ($existingDocs.ContainsKey($expectedDocName)) {
            $currentDoc = $existingDocs[$expectedDocName]
        }

        if ($null -eq $currentDoc) {
            $currentDoc = Upload-Document -KbId ([string]$kbRecord.id) -DocumentConfig $doc
        }

        Start-ChunkIfNeeded -DocId ([string]$currentDoc.id) -Status ([string]$currentDoc.status)
        $finalDoc = if ($SkipChunk) {
            Invoke-ApiJson -Method GET -Uri "$BaseUrl/knowledge-base/docs/$($currentDoc.id)" -Headers $headers
        } else {
            Wait-DocumentReady -DocId ([string]$currentDoc.id) -TimeoutSeconds $ChunkTimeoutSeconds
        }

        $manifest.knowledgeBases[$kb.key].documents[$doc.key] = [ordered]@{
            id         = [string]$finalDoc.id
            docName    = $finalDoc.docName
            status     = $finalDoc.status
            chunkCount = $finalDoc.chunkCount
            path       = $doc.path
        }

        $manifest.documentIndex[$doc.key] = [ordered]@{
            kbKey   = $kb.key
            kbId    = [string]$kbRecord.id
            docId   = [string]$finalDoc.id
            docName = $finalDoc.docName
            status  = $finalDoc.status
        }
    }
}

$existingTree = Invoke-ApiJson -Method GET -Uri "$BaseUrl/intent-tree/trees" -Headers $headers
$existingNodeMap = @{}
foreach ($node in @(Flatten-IntentTree -Nodes @($existingTree))) {
    $existingNodeMap[$node.intentCode] = $node
}

foreach ($node in @($intentSeed.nodes)) {
    if ($existingNodeMap.ContainsKey($node.intentCode)) {
        $currentNode = $existingNodeMap[$node.intentCode]
        $manifest.intentNodes[$node.key] = [ordered]@{
            id         = [string]$currentNode.id
            intentCode = $currentNode.intentCode
            name       = $currentNode.name
            kbKey      = $node.kbKey
        }
        continue
    }

    $promptSnippetProperty = $node.PSObject.Properties["promptSnippet"]
    $body = @{
        intentCode    = $node.intentCode
        name          = $node.name
        level         = [int]$node.level
        parentCode    = $node.parentCode
        description   = $node.description
        examples      = @($node.examples)
        topK          = [int]$node.topK
        kind          = [int]$node.kind
        sortOrder     = [int]$node.sortOrder
        enabled       = [int]$node.enabled
        promptSnippet = $(if ($null -ne $promptSnippetProperty) { $promptSnippetProperty.Value } else { $null })
    }

    if (-not [string]::IsNullOrWhiteSpace([string]$node.kbKey)) {
        $body.kbId = [string]$manifest.knowledgeBases[$node.kbKey].id
    }

    $nodeId = [string](Invoke-ApiJson -Method POST -Uri "$BaseUrl/intent-tree" -Headers $headers -Body $body)
    $manifest.intentNodes[$node.key] = [ordered]@{
        id         = $nodeId
        intentCode = $node.intentCode
        name       = $node.name
        kbKey      = $node.kbKey
    }
}

$samplePage = Invoke-ApiJson -Method GET -Uri "$BaseUrl/sample-questions?current=1&size=200" -Headers $headers
$sampleMap = @{}
foreach ($item in @($samplePage.records)) {
    $sampleMap[$item.question] = $item
}

foreach ($question in @($sampleSeed.questions)) {
    if ($sampleMap.ContainsKey($question.question)) {
        $existing = $sampleMap[$question.question]
        $manifest.sampleQuestions[$question.key] = [ordered]@{
            id          = [string]$existing.id
            title       = $existing.title
            description = $existing.description
            question    = $existing.question
        }
        continue
    }

    $questionId = [string](Invoke-ApiJson -Method POST -Uri "$BaseUrl/sample-questions" -Headers $headers -Body @{
            title       = $question.title
            description = $question.description
            question    = $question.question
        })

    $manifest.sampleQuestions[$question.key] = [ordered]@{
        id          = $questionId
        title       = $question.title
        description = $question.description
        question    = $question.question
    }
}

$manifestPath = Join-Path $outputDir "import-manifest.json"
Write-JsonFile -Path $manifestPath -Data $manifest

Write-Host "Seed import completed." -ForegroundColor Green
Write-Host "Manifest: $manifestPath"
