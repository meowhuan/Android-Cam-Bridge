#Requires -Version 7.0
param(
  [string]$DriverDir = "",
  [string]$InfName = "acb-aoa.inf",
  [string]$CatalogName = "acb-aoa.cat",
  [string]$PfxName = "acb-aoa-test.pfx",
  [string]$PfxPassword = "acb-test-driver",
  [string]$ExistingPfxPath = "",
  [string]$ExistingCerPath = "",
  [string]$CertSubject = "CN=Android Cam Bridge Test Driver",
  [ValidateSet("CurrentUser", "LocalMachine")]
  [string]$CertStoreScope = "CurrentUser",
  [switch]$InstallCertificate = $true,
  [switch]$ForceNewCertificate,
  [switch]$UseMakeCatFallback,
  [string]$TimestampUrl = ""
)

$ErrorActionPreference = "Stop"

if (-not $DriverDir) {
  $DriverDir = Join-Path $PSScriptRoot "..\drivers\aoa-winusb"
}
$DriverDir = (Resolve-Path $DriverDir).Path
$infPath = Join-Path $DriverDir $InfName
$catPath = Join-Path $DriverDir $CatalogName
$cerPath = [IO.Path]::ChangeExtension($catPath, ".cer")
$pfxPath = Join-Path $DriverDir $PfxName

if (-not (Test-Path $infPath)) {
  throw "INF not found: $infPath"
}

function Find-WindowsKitTool {
  param([string]$ToolName)

  $roots = @(
    "C:\Program Files (x86)\Windows Kits\10\bin",
    "C:\Program Files\Windows Kits\10\bin"
  )

  $candidates = foreach ($root in $roots) {
    if (Test-Path $root) {
      Get-ChildItem $root -Recurse -File -Filter $ToolName -ErrorAction SilentlyContinue
    }
  }

  return $candidates |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName
}

function Find-TrustedPathTool {
  param([string]$ToolName)

  $cmd = Get-Command $ToolName -All -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandType -eq "Application" } |
    Select-Object -First 1
  if (-not $cmd) {
    return $null
  }

  $path = $cmd.Source
  if (-not $path -or -not (Test-Path $path)) {
    return $null
  }

  try {
    $version = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($path)
    $company = $version.CompanyName
    $product = $version.ProductName
    if ($company -match "Microsoft" -or $product -match "Windows") {
      return $path
    }
  } catch {
    return $null
  }

  return $null
}

function Get-StoreLocationEnum {
  param([string]$Scope)
  return [System.Security.Cryptography.X509Certificates.StoreLocation]::$Scope
}

function Import-CertificateToStore {
  param(
    [System.Security.Cryptography.X509Certificates.X509Certificate2]$Certificate,
    [string]$StoreName,
    [string]$Scope
  )

  $store = [System.Security.Cryptography.X509Certificates.X509Store]::new(
    $StoreName,
    (Get-StoreLocationEnum $Scope)
  )
  try {
    $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
    $store.Add($Certificate)
  } finally {
    $store.Close()
  }
}

function Find-CodeSigningCertificate {
  param(
    [string]$Scope,
    [string]$Subject
  )

  try {
    $store = [System.Security.Cryptography.X509Certificates.X509Store]::new(
      [System.Security.Cryptography.X509Certificates.StoreName]::My,
      (Get-StoreLocationEnum $Scope)
    )
    $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadOnly)
    return $store.Certificates |
      Where-Object {
        $_.Subject -eq $Subject -and
        (($_.EnhancedKeyUsageList | ForEach-Object { $_.ObjectId.Value }) -contains "1.3.6.1.5.5.7.3.3")
      } |
      Sort-Object NotAfter -Descending |
      Select-Object -First 1
  } catch {
    return $null
  } finally {
    if ($store) { $store.Close() }
  }
}

function New-MakeCatDefinition {
  param(
    [string]$DefinitionPath,
    [string]$CatalogFileName,
    [string]$InfFileName
  )

  $content = @"
[CatalogHeader]
Name=$CatalogFileName
ResultDir=.
PublicVersion=0x0000001
EncodingType=0x00010001
CATATTR1=0x10010001:OSAttr:2:10.0

[CatalogFiles]
<hash>$InfFileName=$InfFileName
"@

  Set-Content -Path $DefinitionPath -Value $content -Encoding ASCII
}

$pathInf2Cat = Find-TrustedPathTool -ToolName "inf2cat.exe"
$inf2cat = Find-WindowsKitTool -ToolName "inf2cat.exe"
if (-not $inf2cat -and $pathInf2Cat) {
  $inf2cat = $pathInf2Cat
}
$makecat = Find-WindowsKitTool -ToolName "makecat.exe"
$signtool = Find-WindowsKitTool -ToolName "signtool.exe"

if (-not $signtool) {
  throw "signtool.exe not found. Install Windows SDK / WDK first."
}

Push-Location $DriverDir
try {
  if ($inf2cat) {
    & $inf2cat "/driver:$DriverDir" "/os:10_X64"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $catPath)) {
      throw "Inf2Cat failed to generate $CatalogName"
    }
    Write-Host "Catalog generated via Inf2Cat: $catPath"
  } elseif ($UseMakeCatFallback) {
    if (-not $makecat) {
      throw "Inf2Cat.exe and MakeCat.exe are both unavailable. Install WDK first."
    }
    $cdfPath = Join-Path $DriverDir "acb-aoa.cdf"
    New-MakeCatDefinition -DefinitionPath $cdfPath -CatalogFileName $CatalogName -InfFileName $InfName
    & $makecat "-v" $cdfPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $catPath)) {
      throw "MakeCat failed to generate $CatalogName"
    }
    Remove-Item $cdfPath -Force -ErrorAction SilentlyContinue
    Write-Warning "Catalog generated via MakeCat fallback. For INF-based packages, Inf2Cat remains the recommended path."
  } else {
    $rawInf2Cat = Get-Command inf2cat.exe -All -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($rawInf2Cat) {
      throw "A command named inf2cat.exe was found at '$($rawInf2Cat.Source)', but it does not look like the Microsoft WDK Inf2Cat.exe. Install WDK or rerun with -UseMakeCatFallback."
    }
    throw "Inf2Cat.exe not found. Install WDK or rerun with -UseMakeCatFallback."
  }
} finally {
  Pop-Location
}

$cert = $null
if ($ExistingPfxPath) {
  $resolvedPfx = (Resolve-Path $ExistingPfxPath).Path
  $pfxPath = $resolvedPfx
  $flags = [System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]::Exportable
  $cert = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($pfxPath, $PfxPassword, $flags)
  Write-Host "Using existing PFX: $pfxPath"
} elseif (-not $ForceNewCertificate) {
  $cert = Find-CodeSigningCertificate -Scope $CertStoreScope -Subject $CertSubject
  if ($cert) {
    Write-Host "Reusing existing certificate from $CertStoreScope store: $($cert.Thumbprint)"
  }
}

if (-not $cert) {
  $storePath = "Cert:\$CertStoreScope\My"
  $cert = New-SelfSignedCertificate `
    -Type CodeSigningCert `
    -Subject $CertSubject `
    -CertStoreLocation $storePath `
    -FriendlyName "ACB AOA Test Driver" `
    -HashAlgorithm "SHA256" `
    -KeyAlgorithm "RSA" `
    -KeyLength 2048 `
    -KeyExportPolicy Exportable `
    -NotAfter (Get-Date).AddYears(3)
  Write-Host "Created test code-signing certificate in ${storePath}: $($cert.Thumbprint)"
}

if ($ExistingCerPath) {
  $resolvedCer = (Resolve-Path $ExistingCerPath).Path
  Copy-Item $resolvedCer $cerPath -Force
} else {
  Export-Certificate -Cert $cert -FilePath $cerPath -Force | Out-Null
}

if (-not $ExistingPfxPath) {
  $securePassword = ConvertTo-SecureString -String $PfxPassword -AsPlainText -Force
  Export-PfxCertificate -Cert $cert -FilePath $pfxPath -Password $securePassword -Force | Out-Null
}

if ($InstallCertificate) {
  try {
    Import-CertificateToStore -Certificate $cert -StoreName "Root" -Scope $CertStoreScope
    Import-CertificateToStore -Certificate $cert -StoreName "TrustedPublisher" -Scope $CertStoreScope
    Write-Host "Installed certificate into $CertStoreScope Root and TrustedPublisher stores"
  } catch {
    Write-Warning "Failed to import certificate into $CertStoreScope stores: $($_.Exception.Message)"
    Write-Warning "You can import $cerPath manually, or rerun with -InstallCertificate:`$false."
  }
}

$signArgs = @(
  "sign",
  "/v",
  "/fd", "sha256",
  "/f", $pfxPath,
  "/p", $PfxPassword
)
if ($TimestampUrl) {
  $signArgs += @("/tr", $TimestampUrl, "/td", "sha256")
}
$signArgs += $catPath

& $signtool @signArgs
if ($LASTEXITCODE -ne 0) {
  throw "SignTool failed to sign $catPath"
}

Write-Host "Signed driver catalog: $catPath"
Write-Host "Exported certificate: $cerPath"
Write-Host "Signing PFX: $pfxPath"
Write-Host "Reminder: test-signed driver installation usually requires TESTSIGNING mode on the target machine."
