# Install AOA WinUSB Driver
# Run as Administrator

Write-Host "Installing Android Cam Bridge AOA WinUSB driver..." -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$infPath   = Join-Path $scriptDir "acb-aoa.inf"
$catPath   = Join-Path $scriptDir "acb-aoa.cat"

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

# Signing hint
if (-not (Test-Path $catPath)) {
    Write-Host "WARNING: Catalog file not found: $catPath" -ForegroundColor Yellow
    Write-Host "This driver package is not signed yet." -ForegroundColor Yellow
    Write-Host "Run .\\scripts\\sign-aoa-driver.ps1 first, then enable TESTSIGNING on the target machine if needed." -ForegroundColor Yellow
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
    if ($result -match "数字签名" -or $result -match "signature") {
        Write-Host "Driver signature check failed." -ForegroundColor Yellow
        Write-Host "1) Generate/sign acb-aoa.cat via .\\scripts\\sign-aoa-driver.ps1" -ForegroundColor Yellow
        Write-Host "2) Import the test certificate" -ForegroundColor Yellow
        Write-Host "3) Enable TESTSIGNING and reboot if this is a test-signed package" -ForegroundColor Yellow
        Write-Host "4) Or use Zadig to bind the AOA device to WinUSB for local development" -ForegroundColor Yellow
    } else {
        Write-Host "The driver may still work. Try reconnecting your Android device." -ForegroundColor Yellow
    }
}
