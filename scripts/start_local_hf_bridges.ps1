param(
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"

Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "start_hf_embedding_bridge.ps1"),
    "-Python", $Python
)

Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "start_qwen_vl_embedding_bridge.ps1"),
    "-Python", $Python
)

Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "start_hf_rerank_bridge.ps1"),
    "-Python", $Python
)
