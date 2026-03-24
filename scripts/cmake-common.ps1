#Requires -Version 7.0

function Get-CMakeCacheValue {
  param(
    [string]$BuildDir,
    [string]$Name
  )

  $cache = Join-Path $BuildDir "CMakeCache.txt"
  if (-not (Test-Path $cache)) {
    return ""
  }

  $escapedName = [Regex]::Escape($Name)
  $line = Select-String -Path $cache -Pattern "^${escapedName}(:[^=]+)?=" -SimpleMatch:$false | Select-Object -First 1
  if (-not $line) {
    return ""
  }

  return (($line.Line -split '=', 2)[1]).Trim()
}

function Get-AvailableVisualStudioGenerators {
  $helpOutput = & cmake --help
  if ($LASTEXITCODE -ne 0) {
    throw "Unable to query CMake generators."
  }

  $generators = @()
  foreach ($line in $helpOutput) {
    if ($line -match '^\*?\s*(Visual Studio \d+ \d{4})\s*=') {
      $generators += $Matches[1]
    }
  }

  return $generators
}

function Resolve-CMakeGenerator {
  param([string]$BuildDir)

  $cachedGenerator = Get-CMakeCacheValue -BuildDir $BuildDir -Name "CMAKE_GENERATOR"
  if (-not [string]::IsNullOrWhiteSpace($cachedGenerator)) {
    return [ordered]@{
      Generator = $cachedGenerator
      Platform  = Get-CMakeCacheValue -BuildDir $BuildDir -Name "CMAKE_GENERATOR_PLATFORM"
      Toolset   = Get-CMakeCacheValue -BuildDir $BuildDir -Name "CMAKE_GENERATOR_TOOLSET"
      Source    = "cache"
    }
  }

  $available = Get-AvailableVisualStudioGenerators
  if ($available.Count -eq 0) {
    throw "No Visual Studio CMake generator is available on this machine."
  }

  return [ordered]@{
    Generator = $available[0]
    Platform  = "x64"
    Toolset   = ""
    Source    = "cmake-default"
  }
}

function Get-CMakeConfigureArgs {
  param(
    [string]$RepoRoot,
    [string]$BuildDir,
    [string]$ObsIncludeDir = "",
    [string]$ObsGeneratedIncludeDir = "",
    [string]$ObsLibDir = ""
  )

  $generatorInfo = Resolve-CMakeGenerator -BuildDir $BuildDir
  Write-Host "Using CMake generator: $($generatorInfo.Generator) [$($generatorInfo.Source)]"

  $args = @("-S", $RepoRoot, "-B", $BuildDir, "-G", $generatorInfo.Generator)
  if (-not [string]::IsNullOrWhiteSpace($generatorInfo.Platform)) {
    $args += @("-A", $generatorInfo.Platform)
  }
  if (-not [string]::IsNullOrWhiteSpace($generatorInfo.Toolset)) {
    $args += @("-T", $generatorInfo.Toolset)
  }
  if ($ObsIncludeDir) {
    $args += "-DOBS_INCLUDE_DIR=$ObsIncludeDir"
  }
  if ($ObsGeneratedIncludeDir) {
    $args += "-DOBS_GENERATED_INCLUDE_DIR=$ObsGeneratedIncludeDir"
  }
  if ($ObsLibDir) {
    $args += "-DOBS_LIB_DIR=$ObsLibDir"
  }

  return $args
}

function Invoke-CMakeConfigure {
  param(
    [string]$RepoRoot,
    [string]$BuildDir,
    [string]$ObsIncludeDir = "",
    [string]$ObsGeneratedIncludeDir = "",
    [string]$ObsLibDir = ""
  )

  $cmakeArgs = Get-CMakeConfigureArgs `
    -RepoRoot $RepoRoot `
    -BuildDir $BuildDir `
    -ObsIncludeDir $ObsIncludeDir `
    -ObsGeneratedIncludeDir $ObsGeneratedIncludeDir `
    -ObsLibDir $ObsLibDir

  & cmake @cmakeArgs
  if ($LASTEXITCODE -ne 0) {
    throw "CMake configure failed."
  }
}

function Invoke-CMakeBuild {
  param(
    [string]$BuildDir,
    [string]$Config,
    [string[]]$Targets = @()
  )

  $args = @("--build", $BuildDir, "--config", $Config)
  if ($Targets.Count -gt 0) {
    $args += @("--target")
    $args += $Targets
  }

  & cmake @args
  if ($LASTEXITCODE -ne 0) {
    $targetText = if ($Targets.Count -gt 0) { ($Targets -join ", ") } else { "<default>" }
    throw "CMake build failed for target(s): $targetText."
  }
}
