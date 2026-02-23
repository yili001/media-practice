# Media Center - DLNA Casting Firewall Setup
# Run as Administrator!
# Usage: .\firewall-casting-setup.ps1 [-StreamPort 8192] [-JavaPath "C:\Program Files\Java\jdk-17.0.2\bin\java.exe"]

param(
    [int]$StreamPort = 8192,
    [string]$JavaPath = "C:\Program Files\Java\jdk-17.0.2\bin\java.exe"
)

# Check admin
if (-NOT ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "ERROR: Run this script as Administrator!" -ForegroundColor Red
    exit 1
}

# Verify java.exe exists
if (-NOT (Test-Path $JavaPath)) {
    Write-Host "WARNING: java.exe not found at '$JavaPath'" -ForegroundColor Yellow
}

# Remove existing casting rules
Get-NetFirewallRule -DisplayName "MediaCenter-DLNA-*" -ErrorAction SilentlyContinue | Where-Object { $_.Group -eq "java-media-center" } | Remove-NetFirewallRule -ErrorAction SilentlyContinue

# Rule 1: SSDP Discovery - Inbound UDP 1900 (multicast responses from TV)
New-NetFirewallRule -DisplayName "MediaCenter-DLNA-Discovery" `
    -Direction Inbound `
    -Protocol UDP `
    -LocalPort 1900 `
    -Action Allow `
    -Profile Any `
    -Group "java-media-center" `
    -Program $JavaPath `
    -Description "Allow SSDP discovery responses from DLNA devices (java.exe)"

# Rule 2: Content Streaming - Inbound TCP on stream port (TV fetches video)
New-NetFirewallRule -DisplayName "MediaCenter-DLNA-Stream-$StreamPort" `
    -Direction Inbound `
    -Protocol TCP `
    -LocalPort $StreamPort `
    -Action Allow `
    -Profile Any `
    -Group "java-media-center" `
    -Program $JavaPath `
    -Description "Allow DLNA device to stream content from Media Center (java.exe) on port $StreamPort"

# Rule 3: SSDP Outbound multicast (M-SEARCH to 239.255.255.250)
New-NetFirewallRule -DisplayName "MediaCenter-DLNA-Search" `
    -Direction Outbound `
    -Protocol UDP `
    -RemotePort 1900 `
    -Action Allow `
    -Profile Any `
    -Group "java-media-center" `
    -Program $JavaPath `
    -Description "Allow SSDP M-SEARCH multicast for DLNA device discovery (java.exe)"

Write-Host ""
Write-Host "DLNA Casting firewall rules created successfully!" -ForegroundColor Green
Write-Host ""
Write-Host "  Rule 1 - Discovery   : Inbound  UDP 1900  (SSDP responses from TV)" 
Write-Host "  Rule 2 - Streaming   : Inbound  TCP $StreamPort  (TV fetches video)"
Write-Host "  Rule 3 - Search      : Outbound UDP 1900  (M-SEARCH to find devices)"
Write-Host "  Program              : $JavaPath"
Write-Host "  Profiles             : All (Domain, Private, Public)"
Write-Host ""
Write-Host "Your TV can now discover and stream from this PC, even on public/untrusted networks." -ForegroundColor Cyan
