param(
    [string]$Python = "python",
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8125,
    [string]$TorchIndexUrl = $env:RAG_TORCH_INDEX_URL
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$venvPath = Join-Path $projectRoot ".venv-hf-embedding-bridge"
$venvPython = Join-Path $venvPath "Scripts\python.exe"
$requirements = Join-Path $PSScriptRoot "hf_embedding_bridge_requirements.txt"
$bridgeScript = Join-Path $PSScriptRoot "hf_embedding_bridge.py"

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "HF embedding bridge already listening on ${BindHost}:$Port"
    return
}

if (-not (Test-Path $venvPython)) {
    & $Python -m venv $venvPath
}

if (-not $TorchIndexUrl) {
    $TorchIndexUrl = "https://download.pytorch.org/whl/cu126"
}

& $venvPython -m pip install --upgrade pip
& $venvPython -m pip install --upgrade torch torchvision torchaudio --index-url $TorchIndexUrl
& $venvPython -m pip install -r $requirements

$cudaAvailable = (& $venvPython -c "import torch; print('true' if torch.cuda.is_available() else 'false')" 2>$null).Trim()

if (-not $env:HF_HOME) {
    $env:HF_HOME = "E:\hugging_face"
}
if (-not $env:HF_HUB_CACHE) {
    $env:HF_HUB_CACHE = Join-Path $env:HF_HOME "hub"
}
if (-not $env:HF_EMBEDDING_MODEL) {
    $env:HF_EMBEDDING_MODEL = "Qwen/Qwen3-Embedding-0.6B"
}
if (-not $env:HF_EMBEDDING_DEVICE) {
    $env:HF_EMBEDDING_DEVICE = if ($cudaAvailable -eq "true") { "cuda" } else { "cpu" }
}
if (-not $env:HF_EMBEDDING_DIMENSIONS) {
    $env:HF_EMBEDDING_DIMENSIONS = "1024"
}
if (-not $env:HF_EMBEDDING_HOST) {
    $env:HF_EMBEDDING_HOST = $BindHost
}
if (-not $env:HF_EMBEDDING_PORT) {
    $env:HF_EMBEDDING_PORT = [string]$Port
}

& $venvPython $bridgeScript --host $BindHost --port $Port
