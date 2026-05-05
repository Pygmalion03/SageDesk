param(
    [string]$Python = "python",
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8115,
    [string]$TorchIndexUrl = $env:RAG_TORCH_INDEX_URL
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$venvPath = Join-Path $projectRoot ".venv-qwen-vl-embedding-bridge"
$venvPython = Join-Path $venvPath "Scripts\\python.exe"
$requirements = Join-Path $PSScriptRoot "qwen_vl_embedding_bridge_requirements.txt"
$bridgeScript = Join-Path $PSScriptRoot "qwen_vl_embedding_bridge.py"

if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
    Write-Host "Qwen3-VL embedding bridge already listening on ${BindHost}:$Port"
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

if (-not $env:HF_HOME) {
    $env:HF_HOME = "E:\hugging_face"
}
if (-not $env:HF_HUB_CACHE) {
    $env:HF_HUB_CACHE = Join-Path $env:HF_HOME "hub"
}
if (-not $env:QWEN_VL_EMBEDDING_MODEL) {
    $env:QWEN_VL_EMBEDDING_MODEL = "Qwen/Qwen3-VL-Embedding-2B"
}
if (-not $env:QWEN_VL_EMBEDDING_HOST) {
    $env:QWEN_VL_EMBEDDING_HOST = $BindHost
}
if (-not $env:QWEN_VL_EMBEDDING_PORT) {
    $env:QWEN_VL_EMBEDDING_PORT = [string]$Port
}
if (-not $env:QWEN_VL_EMBEDDING_DEVICE) {
    $env:QWEN_VL_EMBEDDING_DEVICE = "cuda"
}
if (-not $env:QWEN_VL_EMBEDDING_DIMENSIONS) {
    $env:QWEN_VL_EMBEDDING_DIMENSIONS = "1024"
}

& $venvPython $bridgeScript --host $BindHost --port $Port
