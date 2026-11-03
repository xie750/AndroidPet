#requires -RunAsAdministrator
$ErrorActionPreference = "Stop"

$profile = Get-NetConnectionProfile -InterfaceAlias "WLAN"
if ($profile.NetworkCategory -eq "Public") {
    Set-NetConnectionProfile -InterfaceAlias "WLAN" -NetworkCategory Private
}

$ruleName = "Feiwu Model Bridge LAN"
$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if (-not $existing) {
    New-NetFirewallRule `
        -DisplayName $ruleName `
        -Direction Inbound `
        -Action Allow `
        -Protocol TCP `
        -LocalPort 8765 `
        -Profile Private `
        -RemoteAddress LocalSubnet | Out-Null
}

Write-Host "Feiwu LAN access is enabled on TCP 8765." -ForegroundColor Green
