#Requires -Version 7.0
param(
  [string]$Version = "0.2.4",
  [string]$ObsIncludeDir = "",
  [string]$ObsGeneratedIncludeDir = "",
  [string]$ObsLibDir = "",
  [bool]$RequireRealObsPlugin = $false
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Resolve-Iscc {
  $cmd = Get-Command iscc -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Path }

  $candidates = @(
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe"
  )
  foreach ($c in $candidates) {
    if (Test-Path $c) { return $c }
  }
  return $null
}

Write-Host "Step 1/2: Build package payload"
$packageArgs = @(
  "-NoProfile",
  "-ExecutionPolicy", "Bypass",
  "-File", (Join-Path $PSScriptRoot "package.ps1"),
  "-Version", $Version,
  "-RequireRealObsPlugin", $RequireRealObsPlugin
)
if ($ObsIncludeDir) {
  $packageArgs += @("-ObsIncludeDir", $ObsIncludeDir)
}
if ($ObsGeneratedIncludeDir) {
  $packageArgs += @("-ObsGeneratedIncludeDir", $ObsGeneratedIncludeDir)
}
if ($ObsLibDir) {
  $packageArgs += @("-ObsLibDir", $ObsLibDir)
}
pwsh @packageArgs

$iscc = Resolve-Iscc
if (-not $iscc) {
  throw "Inno Setup (ISCC.exe) not found. Install Inno Setup 6 and retry."
}

Write-Host "Step 2/2: Compile Inno installer"
$iss = Join-Path $repoRoot "installer\inno\acb.iss"
& $iscc "/DAppVersion=$Version" $iss
if ($LASTEXITCODE -ne 0) {
  throw "Installer build failed."
}

Write-Host "Installer created under: $repoRoot\dist\installer"
