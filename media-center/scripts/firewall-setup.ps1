# Media Center - Firewall Setup Script
# Run as Administrator!
# Usage: .\firewall-setup.ps1 [-Port 6881] [-JavaPath "C:\Program Files\Java\jdk-17.0.2\bin\java.exe"]

param(
    [int]$Port = 6881,
    [string]$JavaPath = "C:\Program Files\Java\jdk-17.0.2\bin\java.exe"
)

$RuleName = "MediaCenter-PeerPort-$Port"

# Check admin
if (-NOT ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "ERROR: Run this script as Administrator!" -ForegroundColor Red
    exit 1
}

# Verify java.exe exists
if (-NOT (Test-Path $JavaPath)) {
    Write-Host "WARNING: java.exe not found at '$JavaPath'" -ForegroundColor Yellow
    Write-Host "The rule will still be created, but verify the path is correct." -ForegroundColor Yellow
}

# Remove existing rules
Get-NetFirewallRule -DisplayName "MediaCenter-PeerPort-*" -ErrorAction SilentlyContinue | Where-Object { $_.Group -eq "java-media-center" } | Remove-NetFirewallRule -ErrorAction SilentlyContinue

# Add inbound TCP rule restricted to java.exe
New-NetFirewallRule -DisplayName $RuleName `
    -Direction Inbound `
    -Protocol TCP `
    -LocalPort $Port `
    -Action Allow `
    -Profile Any `
    -Group "java-media-center" `
    -Program $JavaPath `
    -Description "Allow incoming BitTorrent peer connections for Media Center (java.exe) on port $Port"

Write-Host ""
Write-Host "Firewall rule '$RuleName' created successfully!" -ForegroundColor Green
Write-Host "  Direction : Inbound"
Write-Host "  Protocol  : TCP"
Write-Host "  Port      : $Port"
Write-Host "  Program   : $JavaPath"
Write-Host ""
Write-Host "Only java.exe can accept connections on port $Port." -ForegroundColor Cyan
