#Requires -Version 7.0
param(
  [string]$InstallRoot = "C:\Program Files\ACB"
)

$ErrorActionPreference = "Stop"
$manifestPath = Join-Path $InstallRoot "install-manifest.json"
if (!(Test-Path $manifestPath)) {
  throw "Manifest not found: $manifestPath"
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
