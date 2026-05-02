param(
    [string]$Python = "python",
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8115
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$venvPath = Join-Path $projectRoot ".venv-qwen-vl-embedding-bridge"
$venvPython = Join-Path $venvPath "Scripts\\python.exe"
$requirements = Join-Path $PSScriptRoot "qwen_vl_embedding_bridge_requirements.txt"
$bridgeScript = Join-Path $PSScriptRoot "qwen_vl_embedding_bridge.py"

if (-not (Test-Path $venvPython)) {
    & $Python -m venv $venvPath
}

& $venvPython -m pip install --upgrade pip
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
