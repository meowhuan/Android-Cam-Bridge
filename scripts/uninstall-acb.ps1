#Requires -Version 7.0
param(
  [string]$InstallRoot = "C:\Program Files\ACB"
)

$ErrorActionPreference = "Stop"
$manifestPath = Join-Path $InstallRoot "install-manifest.json"
if (!(Test-Path $manifestPath)) {
  throw "Manifest not found: $manifestPath"
}

$virtualCamDll = Join-Path $InstallRoot "virtualcam-driver\acb-virtualcam.dll"
if (Test-Path $virtualCamDll) {
  try {
    $proc = Start-Process -FilePath "regsvr32.exe" -ArgumentList @("/u", "/s", $virtualCamDll) -Wait -PassThru -NoNewWindow
    if ($proc.ExitCode -ne 0) {
      Write-Warning "Virtual camera unregistration returned exit code $($proc.ExitCode)."
    }
  } catch {
    Write-Warning "Virtual camera unregistration failed: $($_.Exception.Message)"
  }
}

$meta = Get-Content $manifestPath -Raw | ConvertFrom-Json
foreach ($file in $meta.files) {
  if (Test-Path $file) {
    Remove-Item $file -Force -ErrorAction SilentlyContinue
  }
}

$dirs = @(
  (Join-Path $InstallRoot "receiver"),
  (Join-Path $InstallRoot "gui"),
  (Join-Path $InstallRoot "virtualcam-bridge"),
  (Join-Path $InstallRoot "virtualcam-driver"),
  (Join-Path $InstallRoot "drivers"),
  $InstallRoot
)

foreach ($d in $dirs) {
  if (Test-Path $d) {
    try {
      Remove-Item $d -Recurse -Force -ErrorAction Stop
    } catch {
      # ignore non-empty directories with external files
    }
  }
}

Write-Host "Uninstall completed (best effort)."
Write-Host "Note: AOA WinUSB driver association is not removed automatically."
