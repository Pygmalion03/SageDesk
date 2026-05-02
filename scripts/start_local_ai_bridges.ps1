param(
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"

Start-Process powershell -ArgumentList @(
    "-NoExit",
    "-ExecutionPolicy", "Bypass",
    "-File", (Join-Path $PSScriptRoot "start_paddle_bridge.ps1"),
    "-Python", $Python
)

& (Join-Path $PSScriptRoot "start_local_hf_bridges.ps1") -Python $Python
