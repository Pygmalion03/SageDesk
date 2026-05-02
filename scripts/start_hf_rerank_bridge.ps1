param(
    [string]$Python = "python",
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8126
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$venvPath = Join-Path $projectRoot ".venv-hf-rerank-bridge"
$venvPython = Join-Path $venvPath "Scripts\python.exe"
$requirements = Join-Path $PSScriptRoot "hf_rerank_bridge_requirements.txt"
$bridgeScript = Join-Path $PSScriptRoot "hf_rerank_bridge.py"

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
if (-not $env:HF_RERANK_MODEL) {
    $env:HF_RERANK_MODEL = "Qwen/Qwen3-Reranker-0.6B"
}
if (-not $env:HF_RERANK_DEVICE) {
    $env:HF_RERANK_DEVICE = "cuda"
}
if (-not $env:HF_RERANK_HOST) {
    $env:HF_RERANK_HOST = $BindHost
}
if (-not $env:HF_RERANK_PORT) {
    $env:HF_RERANK_PORT = [string]$Port
}

& $venvPython $bridgeScript --host $BindHost --port $Port
