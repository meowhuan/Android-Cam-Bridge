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
cmake --build $buildDir --config $Config
