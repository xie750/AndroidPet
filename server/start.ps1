$ErrorActionPreference = "Stop"
$serviceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $serviceRoot

if (-not (Test-Path -LiteralPath ".env")) {
    throw "Missing server/.env. Run setup.ps1 first."
}

uv sync --extra dev
uv run feiwu-bridge

