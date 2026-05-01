param(
    [string]$Python = "python",
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8099
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$venvPath = Join-Path $projectRoot ".venv-paddle-bridge"
$venvPython = Join-Path $venvPath "Scripts\\python.exe"
$requirements = Join-Path $PSScriptRoot "paddle_bridge_requirements.txt"
$bridgeScript = Join-Path $PSScriptRoot "paddle_bridge.py"

if (-not (Test-Path $venvPython)) {
    & $Python -m venv $venvPath
}

& $venvPython -m pip install --upgrade pip
& $venvPython -m pip install -r $requirements

if (-not $env:PADDLE_BRIDGE_HOST) {
    $env:PADDLE_BRIDGE_HOST = $BindHost
}
if (-not $env:PADDLE_BRIDGE_PORT) {
    $env:PADDLE_BRIDGE_PORT = [string]$Port
}
if (-not $env:PADDLE_MODEL) {
    $env:PADDLE_MODEL = "PaddleOCR-VL-1.5-0.9B"
}
if (-not $env:PADDLE_DEFAULT_MODE) {
    $env:PADDLE_DEFAULT_MODE = "paddleocr_vl_1_5"
}
if (-not $env:PADDLE_FALLBACK_MODE) {
    $env:PADDLE_FALLBACK_MODE = "pp_structure_v3"
}
if (-not $env:PADDLE_BRIDGE_DEVICE) {
    $env:PADDLE_BRIDGE_DEVICE = "gpu:0"
}
if (-not $env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK) {
    $env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = "True"
}

& $venvPython $bridgeScript --host $BindHost --port $Port
