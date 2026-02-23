# Media Center - Firewall Cleanup Script
# Run as Administrator!
# Removes all Media Center peer port firewall rules.

# Check admin
if (-NOT ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "ERROR: Run this script as Administrator!" -ForegroundColor Red
    exit 1
}

$rules = Get-NetFirewallRule -DisplayName "MediaCenter-PeerPort-*" -ErrorAction SilentlyContinue | Where-Object { $_.Group -eq "java-media-center" }

if ($rules) {
    $rules | Remove-NetFirewallRule
    Write-Host "All Media Center peer port firewall rules removed." -ForegroundColor Green
}
else {
    Write-Host "No Media Center firewall rules found." -ForegroundColor Yellow
}
