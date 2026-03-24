#Requires -Version 7.0
param(
  [ValidateSet("Debug", "Release")]
  [string]$Config = "Release",
  [string]$ObsIncludeDir = "",
  [string]$ObsGeneratedIncludeDir = "",
  [string]$ObsLibDir = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildDir = Join-Path $repoRoot "build"
. (Join-Path $PSScriptRoot "cmake-common.ps1")

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

Reset-BuildDirIfSourceChanged -Dir $buildDir -ExpectedSource $repoRoot

Invoke-CMakeConfigure `
  -RepoRoot $repoRoot `
  -BuildDir $buildDir `
  -ObsIncludeDir $ObsIncludeDir `
  -ObsGeneratedIncludeDir $ObsGeneratedIncludeDir `
  -ObsLibDir $ObsLibDir
Invoke-CMakeBuild -BuildDir $buildDir -Config $Config
