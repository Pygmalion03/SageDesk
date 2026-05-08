param(
    [string]$Python = "python",
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 18126,
    [string]$Model = $env:HF_RERANK_MODEL,
    [string]$TorchIndexUrl = $env:RAG_TORCH_INDEX_URL
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$venvPath = Join-Path $projectRoot ".venv-hf-rerank-bridge"
$venvPython = Join-Path $venvPath "Scripts\python.exe"
$requirements = Join-Path $PSScriptRoot "hf_rerank_bridge_requirements.txt"
$bridgeScript = Join-Path $PSScriptRoot "hf_rerank_bridge.py"

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "HF rerank bridge already listening on ${BindHost}:$Port"
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
if ($Model) {
    $env:HF_RERANK_MODEL = $Model
}
if (-not $env:HF_RERANK_MODEL) {
    $env:HF_RERANK_MODEL = "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1"
}
if (-not $env:HF_RERANK_DEVICE) {
    $env:HF_RERANK_DEVICE = if ($cudaAvailable -eq "true") { "cuda" } else { "cpu" }
}
if (-not $env:HF_RERANK_HOST) {
    $env:HF_RERANK_HOST = $BindHost
}
if (-not $env:HF_RERANK_PORT) {
    $env:HF_RERANK_PORT = [string]$Port
}

& $venvPython $bridgeScript --host $BindHost --port $Port
