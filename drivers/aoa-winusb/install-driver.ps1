# Install AOA WinUSB Driver
# Run as Administrator

Write-Host "Installing Android Cam Bridge AOA WinUSB driver..." -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$infPath   = Join-Path $scriptDir "acb-aoa.inf"

if (-not (Test-Path $infPath)) {
    Write-Host "ERROR: $infPath not found" -ForegroundColor Red
    exit 1
}

# Check admin
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "ERROR: This script must be run as Administrator" -ForegroundColor Red
    Write-Host "Right-click PowerShell -> Run as Administrator" -ForegroundColor Yellow
    exit 1
}

# Install using pnputil
Write-Host "Installing driver from: $infPath"
$result = pnputil /add-driver $infPath /install 2>&1
Write-Host $result

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "SUCCESS: AOA WinUSB driver installed." -ForegroundColor Green
    Write-Host "You may need to unplug and replug the Android device." -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host "WARNING: pnputil returned exit code $LASTEXITCODE" -ForegroundColor Yellow
    Write-Host "The driver may still work. Try connecting your Android device." -ForegroundColor Yellow
}
