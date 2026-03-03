#Requires -Version 7.0
param(
  [ValidateSet("payload", "installer")]
  [string]$Mode = "installer",
  [string]$Version = "0.2.4-local",
  [string]$ObsRepoRoot = "F:\obs-studio-32.0.4",
  [string]$ObsBuildRoot = "F:\obs-studio-32.0.4\build_x64_local",
  [switch]$AllowStubPlugin
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

$obsIncludeDir = Join-Path $ObsRepoRoot "libobs"
$obsGeneratedIncludeDir = Join-Path $ObsBuildRoot "config"
$obsLibDir = Join-Path $ObsBuildRoot "libobs\Release"

$requiredObsPaths = @(
  $obsIncludeDir,
  $obsGeneratedIncludeDir,
  $obsLibDir,
  (Join-Path $obsGeneratedIncludeDir "obsconfig.h")
)
foreach ($path in $requiredObsPaths) {
  if (-not (Test-Path $path)) {
    throw "OBS SDK path not found: $path"
  }
}

if (-not (Test-Path (Join-Path $obsLibDir "obs.lib"))) {
  throw "OBS SDK library missing: $(Join-Path $obsLibDir "obs.lib")"
}

Write-Host "Using OBS SDK:"
Write-Host "  OBS_INCLUDE_DIR=$obsIncludeDir"
Write-Host "  OBS_GENERATED_INCLUDE_DIR=$obsGeneratedIncludeDir"
Write-Host "  OBS_LIB_DIR=$obsLibDir"

$requireReal = -not $AllowStubPlugin.IsPresent

if ($Mode -eq "payload") {
  pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "package.ps1") `
    -Version $Version `
    -OutDir (Join-Path $repoRoot "dist\acb-win-x64") `
    -ObsIncludeDir $obsIncludeDir `
    -ObsGeneratedIncludeDir $obsGeneratedIncludeDir `
    -ObsLibDir $obsLibDir `
    -RequireRealObsPlugin:$requireReal
  if ($LASTEXITCODE -ne 0) { throw "Local payload build failed." }

  Write-Host "Done. Payload output: $repoRoot\dist\acb-win-x64"
  exit 0
}

pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "build-installer.ps1") `
  -Version $Version `
  -ObsIncludeDir $obsIncludeDir `
  -ObsGeneratedIncludeDir $obsGeneratedIncludeDir `
  -ObsLibDir $obsLibDir `
  -RequireRealObsPlugin:$requireReal
if ($LASTEXITCODE -ne 0) { throw "Local installer build failed." }

Write-Host "Done. Installer output: $repoRoot\dist\installer"

