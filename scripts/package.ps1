#Requires -Version 7.0
param(
  [string]$Version = "0.2.3",
  [string]$OutDir = "",
  [string]$ObsIncludeDir = "",
  [string]$ObsGeneratedIncludeDir = "",
  [string]$ObsLibDir = "",
  [bool]$RequireRealObsPlugin = $false
)

$ErrorActionPreference = "Stop"
if (-not $OutDir) {
  $OutDir = Join-Path $PSScriptRoot "..\dist\acb-win-x64"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildDir = Join-Path $repoRoot "build"
$outRoot = (Resolve-Path (New-Item -ItemType Directory -Path $OutDir -Force)).Path

function Reset-BuildDirIfSourceChanged {
  param([string]$Dir, [string]$ExpectedSource)
  $cache = Join-Path $Dir "CMakeCache.txt"
  if (-not (Test-Path $cache)) { return }
  $line = Select-String -Path $cache -Pattern '^CMAKE_HOME_DIRECTORY:INTERNAL=' -SimpleMatch:$false | Select-Object -First 1
  if (-not $line) { return }
  $actual = ($line.Line -split '=', 2)[1].Trim()
  if ($actual -ne $ExpectedSource) {
    Write-Warning "CMake source changed ($actual -> $ExpectedSource). Recreating $Dir"
    Remove-Item $Dir -Recurse -Force
  }
}

Write-Host "Packaging ACB version $Version"

Reset-BuildDirIfSourceChanged -Dir $buildDir -ExpectedSource $repoRoot

$cmakeArgs = @("-S", $repoRoot, "-B", $buildDir, "-G", "Visual Studio 17 2022", "-A", "x64")
if ($ObsIncludeDir) {
  $cmakeArgs += "-DOBS_INCLUDE_DIR=$ObsIncludeDir"
}
if ($ObsGeneratedIncludeDir) {
  $cmakeArgs += "-DOBS_GENERATED_INCLUDE_DIR=$ObsGeneratedIncludeDir"
}
if ($ObsLibDir) {
  $cmakeArgs += "-DOBS_LIB_DIR=$ObsLibDir"
}
cmake @cmakeArgs
cmake --build $buildDir --config Release --target acb-receiver
cmake --build $buildDir --config Release --target acb-obs-plugin

pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "publish-gui.ps1") -ObsIncludeDir $ObsIncludeDir -ObsGeneratedIncludeDir $ObsGeneratedIncludeDir -ObsLibDir $ObsLibDir
if ($LASTEXITCODE -ne 0) {
  throw "GUI publish failed in package step."
}

$receiverSrc = Join-Path $repoRoot "build\windows\receiver\Release\acb-receiver.exe"
$obsDllSrc = Join-Path $repoRoot "build\windows\obs-plugin\Release\acb-obs-plugin.dll"
$obsEnSrc = Join-Path $repoRoot "windows\obs-plugin\data\locale\en-US.ini"
$obsZhSrc = Join-Path $repoRoot "windows\obs-plugin\data\locale\zh-CN.ini"
$obsBuildModeFile = Join-Path $repoRoot "build\windows\obs-plugin\acb_obs_build_mode.txt"
$guiPublishDir = Join-Path $repoRoot "windows\gui\Acb.Gui\bin\Release\net10.0-windows10.0.19041.0\win-x64\publish"

if (-not (Test-Path $guiPublishDir)) {
  throw "GUI publish directory not found: $guiPublishDir"
}

$obsBuildMode = ""
if (Test-Path $obsBuildModeFile) {
  $obsBuildMode = (Get-Content $obsBuildModeFile -Raw).Trim()
}
$hasRealObsPlugin = ($obsBuildMode -eq "real")
if (-not $hasRealObsPlugin) {
  Write-Warning "OBS plugin was built as '$obsBuildMode'. A loadable OBS plugin will NOT be packaged. Configure OBS SDK paths for real plugin builds."
  if ($RequireRealObsPlugin) {
    throw "RequireRealObsPlugin=true but build mode is '$obsBuildMode'."
  }
}

$receiverOut = Join-Path $outRoot "receiver"
$guiOut = Join-Path $outRoot "gui"
$prereqOut = Join-Path $outRoot "prereqs"

New-Item -ItemType Directory -Path $receiverOut,$guiOut,$prereqOut -Force | Out-Null
Copy-Item $receiverSrc (Join-Path $receiverOut "acb-receiver.exe") -Force
Copy-Item (Join-Path $guiPublishDir "*") $guiOut -Recurse -Force

if ($hasRealObsPlugin) {
  $obsOut = Join-Path $outRoot "obs-plugin"
  $obsLocaleOut = Join-Path $obsOut "locale"
  New-Item -ItemType Directory -Path $obsLocaleOut -Force | Out-Null
  Copy-Item $obsDllSrc (Join-Path $obsOut "acb-obs-plugin.dll") -Force
  Copy-Item $obsEnSrc (Join-Path $obsLocaleOut "en-US.ini") -Force
  Copy-Item $obsZhSrc (Join-Path $obsLocaleOut "zh-CN.ini") -Force
}

$vcRedist = Join-Path $prereqOut "vc_redist.x64.exe"
Invoke-WebRequest -Uri "https://aka.ms/vs/17/release/vc_redist.x64.exe" -OutFile $vcRedist

Copy-Item (Join-Path $PSScriptRoot "install-acb.ps1") (Join-Path $outRoot "install-acb.ps1") -Force
Copy-Item (Join-Path $PSScriptRoot "uninstall-acb.ps1") (Join-Path $outRoot "uninstall-acb.ps1") -Force

$files = @(
  "receiver\acb-receiver.exe",
  "gui\*",
  "prereqs\vc_redist.x64.exe",
  "install-acb.ps1",
  "uninstall-acb.ps1"
)
if ($hasRealObsPlugin) {
  $files += @(
    "obs-plugin\acb-obs-plugin.dll",
    "obs-plugin\locale\en-US.ini",
    "obs-plugin\locale\zh-CN.ini"
  )
}

$meta = [ordered]@{
  version = $Version
  builtAt = (Get-Date).ToString("s")
  obsPluginBuildMode = if ($obsBuildMode) { $obsBuildMode } else { "unknown" }
  files = $files
}
$meta | ConvertTo-Json -Depth 3 | Set-Content -Path (Join-Path $outRoot "package.json") -Encoding UTF8

Write-Host "Package ready: $outRoot"

