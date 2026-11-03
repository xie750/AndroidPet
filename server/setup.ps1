$ErrorActionPreference = "Stop"
$serviceRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $serviceRoot

if (Test-Path -LiteralPath ".env") {
    throw "server/.env already exists. Setup stopped to avoid overwriting existing tokens."
}

$clientToken = & "python" "-c" 'import secrets; print(secrets.token_urlsafe(32))'
$adminToken = & "python" "-c" 'import secrets; print(secrets.token_urlsafe(32))'
$encryptionKey = & "python" "-c" 'import base64, os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())'
$template = Get-Content -Raw -LiteralPath ".env.example"
$template = $template.Replace("replace-with-a-long-random-token", $clientToken.Trim())
$template = $template.Replace("replace-with-a-different-long-random-token", $adminToken.Trim())
$template = $template.Replace("replace-with-a-valid-fernet-key", $encryptionKey.Trim())
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Join-Path $serviceRoot ".env"), $template, $utf8NoBom)

Write-Host "Configuration created: server/.env" -ForegroundColor Green
Write-Host "Admin page token (FEIWU_ADMIN_TOKEN):" -ForegroundColor Cyan
Write-Host $adminToken.Trim()
Write-Host "Keep it private. Configure the provider API key in the admin page after startup."

