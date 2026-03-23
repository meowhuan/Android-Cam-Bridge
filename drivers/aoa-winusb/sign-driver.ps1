# Android Cam Bridge - AOA Driver Test Signing
# Creates a self-signed certificate and signs the INF/CAT for development.
# Run as Administrator.
#
# NOTE: For production release, use a real EV code signing certificate
# and submit to Microsoft for attestation signing (WHQL).
# This script is for DEVELOPMENT/TESTING only.

param(
    [string]$CertName = "Android Cam Bridge Test",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$infPath   = Join-Path $scriptDir "acb-aoa.inf"
$catPath   = Join-Path $scriptDir "acb-aoa.cat"

# --- Check admin ---
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "ERROR: Run this script as Administrator" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $infPath)) {
    Write-Host "ERROR: $infPath not found" -ForegroundColor Red
    exit 1
}

# --- Step 1: Create self-signed certificate (if not already present) ---
$store = "Cert:\LocalMachine\My"
$rootStore = "Cert:\LocalMachine\Root"
$trustedPubStore = "Cert:\LocalMachine\TrustedPublisher"

$existing = Get-ChildItem $store | Where-Object { $_.Subject -like "*$CertName*" }
if ($existing -and -not $Force) {
    Write-Host "Certificate '$CertName' already exists. Use -Force to recreate." -ForegroundColor Yellow
    $cert = $existing[0]
} else {
    if ($existing) {
        Write-Host "Removing old certificate..."
        $existing | Remove-Item -Force
    }

    Write-Host "Creating self-signed code signing certificate: $CertName"
    $cert = New-SelfSignedCertificate `
        -Type CodeSigningCert `
        -Subject "CN=$CertName" `
        -FriendlyName $CertName `
        -CertStoreLocation $store `
        -NotAfter (Get-Date).AddYears(5) `
        -KeyUsage DigitalSignature `
        -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3")

    Write-Host "Certificate created: $($cert.Thumbprint)" -ForegroundColor Green
}

# --- Step 2: Trust the certificate (add to Root and TrustedPublisher) ---
Write-Host "Adding certificate to Trusted Root and TrustedPublisher stores..."

$certBytes = $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)

# Add to Root CA store
$rootCert = Get-ChildItem $rootStore | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }
if (-not $rootCert) {
    $rootStoreObj = New-Object System.Security.Cryptography.X509Certificates.X509Store("Root", "LocalMachine")
    $rootStoreObj.Open("ReadWrite")
    $rootStoreObj.Add($cert)
    $rootStoreObj.Close()
    Write-Host "  -> Added to Trusted Root Certification Authorities" -ForegroundColor Green
}

# Add to TrustedPublisher store
$pubCert = Get-ChildItem $trustedPubStore | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }
if (-not $pubCert) {
    $pubStoreObj = New-Object System.Security.Cryptography.X509Certificates.X509Store("TrustedPublisher", "LocalMachine")
    $pubStoreObj.Open("ReadWrite")
    $pubStoreObj.Add($cert)
    $pubStoreObj.Close()
    Write-Host "  -> Added to Trusted Publishers" -ForegroundColor Green
}

# --- Step 3: Enable test signing mode ---
Write-Host "Enabling Windows test signing mode..."
$bcdeditResult = bcdedit /set testsigning on 2>&1
Write-Host "  bcdedit: $bcdeditResult"

# --- Step 4: Create catalog file ---
Write-Host "Creating catalog file from INF..."

# Use inf2cat if available (from WDK), otherwise use makecat
$wdkPaths = @(
    "${env:ProgramFiles(x86)}\Windows Kits\10\bin\10.0.26100.0\x64",
    "${env:ProgramFiles(x86)}\Windows Kits\10\bin\10.0.22621.0\x64",
    "${env:ProgramFiles(x86)}\Windows Kits\10\bin\10.0.19041.0\x64"
)

$inf2cat = $null
$signtool = $null
foreach ($p in $wdkPaths) {
    if (Test-Path "$p\inf2cat.exe") { $inf2cat = "$p\inf2cat.exe" }
    if (Test-Path "$p\signtool.exe") { $signtool = "$p\signtool.exe" }
    if ($inf2cat -and $signtool) { break }
}

# Also check PATH
if (-not $signtool) {
    $signtool = Get-Command signtool.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
}
if (-not $inf2cat) {
    $inf2cat = Get-Command inf2cat.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
}

if ($inf2cat) {
    Write-Host "Using inf2cat: $inf2cat"
    & $inf2cat /driver:$scriptDir /os:10_X64,10_X86 /verbose 2>&1 | Write-Host
} else {
    Write-Host "inf2cat not found (install WDK). Creating minimal catalog manually..." -ForegroundColor Yellow
    # Create a minimal CDF file for makecat
    $cdfPath = Join-Path $scriptDir "acb-aoa.cdf"
    @"
[CatalogHeader]
Name=acb-aoa.cat
PublicVersion=0x0000001
EncodingType=0x00010001
CATATTR1=0x10010001:OSAttr:2:10.0

[CatalogFiles]
<HASH>acb-aoa.inf=$infPath
"@ | Out-File -FilePath $cdfPath -Encoding ASCII

    $makecat = Get-Command makecat.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
    if ($makecat) {
        & $makecat $cdfPath 2>&1 | Write-Host
    } else {
        Write-Host "WARNING: Neither inf2cat nor makecat found. Skipping catalog creation." -ForegroundColor Yellow
        Write-Host "The driver can still be installed with 'pnputil /add-driver acb-aoa.inf /install'" -ForegroundColor Yellow
    }
}

# --- Step 5: Sign the catalog and INF ---
if ($signtool -and (Test-Path $catPath)) {
    Write-Host "Signing catalog with certificate: $($cert.Thumbprint)"
    & $signtool sign /v /s My /n $CertName /t http://timestamp.digicert.com /fd sha256 $catPath 2>&1 | Write-Host
    Write-Host "Catalog signed successfully!" -ForegroundColor Green
} elseif ($signtool) {
    Write-Host "No catalog file to sign. Signing INF directly..."
    & $signtool sign /v /s My /n $CertName /t http://timestamp.digicert.com /fd sha256 $infPath 2>&1 | Write-Host
} else {
    Write-Host "WARNING: signtool not found (install WDK or Windows SDK)." -ForegroundColor Yellow
    Write-Host "The driver must be installed with test signing enabled." -ForegroundColor Yellow
}

# --- Step 6: Install the driver ---
Write-Host ""
Write-Host "Installing driver..."
$pnpResult = pnputil /add-driver $infPath /install 2>&1
Write-Host $pnpResult

# --- Done ---
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  AOA Driver Test Signing Complete" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Certificate: $CertName (thumbprint: $($cert.Thumbprint))"
Write-Host "Test signing: ENABLED (reboot required if first time)"
Write-Host ""
Write-Host "IMPORTANT:" -ForegroundColor Yellow
Write-Host "  1. Reboot if this is the first time enabling test signing"
Write-Host "  2. A 'Test Mode' watermark will appear on the desktop"
Write-Host "  3. For production, use a real EV code signing certificate"
Write-Host ""
