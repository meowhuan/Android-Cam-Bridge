#Requires -Version 7.0
param(
    [string]$Version = "",
    [string]$ObsIncludeDir = "",
    [string]$ObsGeneratedIncludeDir = "",
    [string]$ObsLibDir = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildDir = Join-Path $repoRoot "build"
. (Join-Path $PSScriptRoot "cmake-common.ps1")
Set-Location $repoRoot

function Reset-BuildDirIfSourceChanged {
  param([string]$Dir, [string]$ExpectedSource)
  function Normalize-SourcePath([string]$PathValue) {
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    $p = $PathValue.Trim()
    $p = $p -replace '\\', '/'
    $p = $p.TrimEnd('/')
    return $p.ToLowerInvariant()
  }

  function Remove-DirRobust([string]$TargetDir) {
    $attempts = 0
    while ($attempts -lt 3) {
      try {
        Remove-Item $TargetDir -Recurse -Force -ErrorAction Stop
        return
      } catch {
        $attempts++
        if ($attempts -eq 1) {
          Write-Warning "Build directory is locked. Attempting to stop ACB/OBS processes and retry..."
          Get-Process -Name "acb-virtualcam-bridge","acb-receiver","obs64","obs32" -ErrorAction SilentlyContinue |
            Stop-Process -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 500
        if ($attempts -ge 3) {
          throw
        }
      }
    }
  }

  $cache = Join-Path $Dir "CMakeCache.txt"
  if (-not (Test-Path $cache)) { return }
  $line = Select-String -Path $cache -Pattern '^CMAKE_HOME_DIRECTORY:INTERNAL=' -SimpleMatch:$false | Select-Object -First 1
  if (-not $line) { return }
  $actual = ($line.Line -split '=', 2)[1].Trim()
  $actualNorm = Normalize-SourcePath $actual
  $expectedNorm = Normalize-SourcePath $ExpectedSource
  if ($actualNorm -ne $expectedNorm) {
    Write-Warning "CMake source changed ($actual -> $ExpectedSource). Recreating $Dir"
    Remove-DirRobust $Dir
  }
}

Reset-BuildDirIfSourceChanged -Dir $buildDir -ExpectedSource $repoRoot

Invoke-CMakeConfigure `
  -RepoRoot $repoRoot `
  -BuildDir $buildDir `
  -ObsIncludeDir $ObsIncludeDir `
  -ObsGeneratedIncludeDir $ObsGeneratedIncludeDir `
  -ObsLibDir $ObsLibDir
Invoke-CMakeBuild -BuildDir $buildDir -Config "Release" -Targets @("acb-receiver")

pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "embed-receiver.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "Embed receiver step failed."
}

Set-Location (Join-Path $repoRoot "windows\gui\Acb.Gui")

$publishArgs = @(
  "publish",
  "-c", "Release",
  "-r", "win-x64",
  "-p:DefineConstants=ACB_EMBED_RECEIVER",
  "-p:SelfContained=true"
)
if ($Version) {
  $publishArgs += "-p:ACB_VERSION_NAME=$Version"
}

dotnet @publishArgs

if ($LASTEXITCODE -ne 0) {
    throw "GUI publish failed."
}

$out = Join-Path $PWD "bin\Release\net10.0-windows10.0.19041.0\win-x64\publish"
Write-Host "Published GUI at: $out"
