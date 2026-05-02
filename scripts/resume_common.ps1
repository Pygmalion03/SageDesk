Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:RagSettingsCache = $null

function Get-WorkspaceRoot {
    return (Split-Path -Parent $PSScriptRoot)
}

function Get-ResumeKitRoot {
    return (Join-Path (Get-WorkspaceRoot) "resume-kit")
}

function Ensure-Directory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        [void](New-Item -ItemType Directory -Path $Path -Force)
    }
    return $Path
}

function Get-ResumeOutputDir {
    return (Ensure-Directory -Path (Join-Path (Get-ResumeKitRoot) "output"))
}

function Read-JsonFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [object]$Data
    )

    $dir = Split-Path -Parent $Path
    if ($dir) {
        Ensure-Directory -Path $dir | Out-Null
    }
    $json = $Data | ConvertTo-Json -Depth 20
    Set-Content -LiteralPath $Path -Value $json -Encoding UTF8
}

function New-AuthHeaders {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Token
    )

    return @{ Authorization = $Token }
}

function Resolve-AuthToken {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,
        [string]$Token,
        [string]$Username,
        [string]$Password
    )

    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        return $Token
    }

    if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
        throw "Please provide either -Token or both -Username and -Password."
    }

    $loginBody = @{
        username = $Username
        password = $Password
    } | ConvertTo-Json

    $response = Invoke-RestMethod -Method Post `
        -Uri ($BaseUrl.TrimEnd("/") + "/auth/login") `
        -ContentType "application/json; charset=utf-8" `
        -Body $loginBody

    if ($null -eq $response -or "$($response.code)" -ne "0") {
        $message = if ($null -ne $response) { $response.message } else { "empty login response" }
        throw "Login failed: $message"
    }

    if ([string]::IsNullOrWhiteSpace([string]$response.data.token)) {
        throw "Login succeeded but token is empty."
    }

    return [string]$response.data.token
}

function Invoke-ApiJson {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet("GET", "POST", "PUT", "PATCH", "DELETE")]
        [string]$Method,
        [Parameter(Mandatory = $true)]
        [string]$Uri,
        [hashtable]$Headers,
        [object]$Body
    )

    $params = @{
        Method     = $Method
        Uri        = $Uri
        TimeoutSec = 180
    }

    if ($Headers) {
        $params.Headers = $Headers
    }

    if ($PSBoundParameters.ContainsKey("Body")) {
        $params.Body = ($Body | ConvertTo-Json -Depth 20)
        $params.ContentType = "application/json; charset=utf-8"
    }

    $response = Invoke-RestMethod @params
    if ($null -ne $response -and $response.PSObject.Properties["code"]) {
        if ("$($response.code)" -ne "0") {
            throw "API request failed: $($response.message)"
        }
        return $response.data
    }

    return $response
}

function Get-RagSettings {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,
        [Parameter(Mandatory = $true)]
        [hashtable]$Headers
    )

    if ($null -ne $script:RagSettingsCache) {
        return $script:RagSettingsCache
    }

    $script:RagSettingsCache = Invoke-ApiJson -Method GET -Uri ($BaseUrl.TrimEnd("/") + "/rag/settings") -Headers $Headers
    return $script:RagSettingsCache
}

function Resolve-ModelCandidate {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,
        [Parameter(Mandatory = $true)]
        [hashtable]$Headers,
        [Parameter(Mandatory = $true)]
        [ValidateSet("chat", "embedding", "rerank")]
        [string]$Group,
        [string]$RequestedModel
    )

    $settings = Get-RagSettings -BaseUrl $BaseUrl -Headers $Headers
    $groupSettings = $settings.ai.$Group
    if ($null -eq $groupSettings) {
        throw "AI settings group not found: $Group"
    }

    $effectiveModel = if ([string]::IsNullOrWhiteSpace($RequestedModel)) {
        [string]$groupSettings.defaultModel
    } else {
        $RequestedModel.Trim()
    }

    $candidates = @($groupSettings.candidates)
    foreach ($candidate in $candidates) {
        if ([string]$candidate.id -eq $effectiveModel -or [string]$candidate.model -eq $effectiveModel) {
            $providerName = [string]$candidate.provider
            $providerConfig = $null
            if ($null -ne $settings.ai.providers) {
                $providerProperty = $settings.ai.providers.PSObject.Properties[$providerName]
                if ($null -ne $providerProperty) {
                    $providerConfig = $providerProperty.Value
                }
            }

            return [pscustomobject]@{
                group          = $Group
                requestedModel = $RequestedModel
                effectiveModel = $effectiveModel
                id             = [string]$candidate.id
                model          = [string]$candidate.model
                provider       = $providerName
                providerConfig = $providerConfig
            }
        }
    }

    $available = @(
        $candidates | ForEach-Object {
            "{0} ({1})" -f ([string]$_.id), ([string]$_.model)
        }
    ) -join ", "

    throw "Model not found for group '$Group': $effectiveModel. Available IDs/models: $available"
}

function Assert-ModelReady {
    param(
        [Parameter(Mandatory = $true)]
        [string]$BaseUrl,
        [Parameter(Mandatory = $true)]
        [hashtable]$Headers,
        [Parameter(Mandatory = $true)]
        [ValidateSet("chat", "embedding", "rerank")]
        [string]$Group,
        [string]$RequestedModel
    )

    $resolved = Resolve-ModelCandidate -BaseUrl $BaseUrl -Headers $Headers -Group $Group -RequestedModel $RequestedModel
    $providerName = [string]$resolved.provider
    $provider = $resolved.providerConfig

    if ($null -eq $provider) {
        throw "Provider config not found for model '$($resolved.id)' (provider: $providerName)."
    }

    if ($providerName -eq "bailian" -and [string]::IsNullOrWhiteSpace([string]$provider.apiKey)) {
        throw "Model '$($resolved.id)' uses provider '$providerName', but its apiKey is empty. Update your AI provider config before running this script."
    }

    if ($providerName -eq "ollama") {
        $providerUrl = [string]$provider.url
        if ([string]::IsNullOrWhiteSpace($providerUrl)) {
            $providerUrl = "http://localhost:11434"
        }

        $tagsUri = $providerUrl.TrimEnd("/") + "/api/tags"
        try {
            $tags = Invoke-RestMethod -Method GET -Uri $tagsUri -TimeoutSec 10
        } catch {
            throw "Model '$($resolved.id)' uses Ollama, but Ollama is not reachable at $providerUrl. Start Ollama first."
        }

        $models = @($tags.models)
        if ($models.Count -eq 0) {
            throw "Ollama is running at $providerUrl, but no models are installed. Run: ollama pull $($resolved.model)"
        }

        $matched = $false
        foreach ($modelEntry in $models) {
            $nameProperty = $modelEntry.PSObject.Properties["name"]
            $modelProperty = $modelEntry.PSObject.Properties["model"]
            $name = if ($null -ne $nameProperty) { [string]$nameProperty.Value } else { "" }
            $model = if ($null -ne $modelProperty) { [string]$modelProperty.Value } else { "" }
            if ($name -eq $resolved.model -or $model -eq $resolved.model) {
                $matched = $true
                break
            }
        }

        if (-not $matched) {
            throw "Ollama is running at $providerUrl, but model '$($resolved.model)' is not installed. Run: ollama pull $($resolved.model)"
        }
    }

    return $resolved
}

function Flatten-IntentTree {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Nodes
    )

    $result = New-Object System.Collections.Generic.List[object]

    function Add-NodeRecursive {
        param([object]$Node)
        $result.Add($Node) | Out-Null
        $childrenProperty = $Node.PSObject.Properties["children"]
        if ($null -ne $childrenProperty -and $null -ne $childrenProperty.Value) {
            foreach ($child in @($childrenProperty.Value)) {
                Add-NodeRecursive -Node $child
            }
        }
    }

    foreach ($node in $Nodes) {
        Add-NodeRecursive -Node $node
    }

    return $result
}

function Get-LatestMatchingFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Directory,
        [Parameter(Mandatory = $true)]
        [string]$Filter
    )

    if (-not (Test-Path -LiteralPath $Directory)) {
        return $null
    }

    return Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Get-Average {
    param([double[]]$Values)

    if ($null -eq $Values -or $Values.Count -eq 0) {
        return 0
    }

    $sum = 0.0
    foreach ($value in $Values) {
        $sum += [double]$value
    }
    return [math]::Round(($sum / $Values.Count), 2)
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percentile = 95
    )

    if ($null -eq $Values -or $Values.Count -eq 0) {
        return 0
    }

    $sorted = @($Values | Sort-Object)
    $index = [math]::Ceiling($sorted.Count * ($Percentile / 100.0)) - 1
    if ($index -lt 0) {
        $index = 0
    }
    if ($index -ge $sorted.Count) {
        $index = $sorted.Count - 1
    }
    return [math]::Round([double]$sorted[$index], 2)
}

function Format-Double {
    param(
        [double]$Value,
        [int]$Digits = 1
    )

    return [math]::Round($Value, $Digits)
}

function Sanitize-CollectionSuffix {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ""
    }

    $lower = $Value.ToLowerInvariant()
    $builder = New-Object System.Text.StringBuilder
    foreach ($char in $lower.ToCharArray()) {
        if (($char -ge [char]'a' -and $char -le [char]'z') -or ($char -ge [char]'0' -and $char -le [char]'9')) {
            [void]$builder.Append($char)
        }
    }

    return $builder.ToString()
}
