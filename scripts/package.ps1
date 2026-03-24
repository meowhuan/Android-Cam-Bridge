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
$stagingDir = Join-Path $outRoot "_staging"
New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null
. (Join-Path $PSScriptRoot "cmake-common.ps1")

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

function Find-BuildFile {
  param(
    [string]$Root,
    [string]$Name
  )
  return Get-ChildItem $Root -Recurse -File -Filter $Name |
    Select-Object -First 1
}

function Find-BuildPluginDll {
  param([string]$Root)
  $exact = Get-ChildItem $Root -Recurse -File -Filter "acb-obs-plugin.dll" | Select-Object -First 1
  if ($exact) { return $exact }

  $fuzzy = Get-ChildItem $Root -Recurse -File -Filter "*obs*plugin*.dll" |
    Where-Object { $_.Name -like "*acb*" } |
    Select-Object -First 1
  return $fuzzy
}

function Require-BuildFile {
  param(
    [string]$Root,
    [string]$Name
  )
  $file = Find-BuildFile -Root $Root -Name $Name
  if (-not $file) {
    throw "$Name not found under build directory: $Root"
  }
  return $file.FullName
}

Write-Host "Packaging ACB version $Version"

Reset-BuildDirIfSourceChanged -Dir $buildDir -ExpectedSource $repoRoot

Invoke-CMakeConfigure `
  -RepoRoot $repoRoot `
  -BuildDir $buildDir `
  -ObsIncludeDir $ObsIncludeDir `
  -ObsGeneratedIncludeDir $ObsGeneratedIncludeDir `
  -ObsLibDir $ObsLibDir
Invoke-CMakeBuild -BuildDir $buildDir -Config "Release" -Targets @("acb-receiver")
Invoke-CMakeBuild -BuildDir $buildDir -Config "Release" -Targets @("acb-obs-plugin")
Invoke-CMakeBuild -BuildDir $buildDir -Config "Release" -Targets @("acb-virtualcam-bridge")
Invoke-CMakeBuild -BuildDir $buildDir -Config "Release" -Targets @("acb-virtualcam")

$obsBuildModeFile = Join-Path $repoRoot "build\windows\obs-plugin\acb_obs_build_mode.txt"
$obsBuildMode = ""
if (Test-Path $obsBuildModeFile) {
  $obsBuildMode = (Get-Content $obsBuildModeFile -Raw).Trim()
}
$hasRealObsPlugin = ($obsBuildMode -eq "real")

$stagedObsDll = ""
if ($hasRealObsPlugin) {
  $obsDllFile = Find-BuildPluginDll -Root $buildDir
  if (-not $obsDllFile) {
    Write-Host "Available DLL files under build directory:"
    Get-ChildItem $buildDir -Recurse -Filter "*.dll" | Select-Object -First 200 -ExpandProperty FullName | ForEach-Object { Write-Host "  $_" }
    throw "OBS plugin build mode is real but acb-obs-plugin.dll was not found under $buildDir"
  }

  $stagedObsDll = Join-Path $stagingDir "acb-obs-plugin.dll"
  Copy-Item $obsDllFile.FullName $stagedObsDll -Force
}

pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "publish-gui.ps1") -Version $Version -ObsIncludeDir $ObsIncludeDir -ObsGeneratedIncludeDir $ObsGeneratedIncludeDir -ObsLibDir $ObsLibDir
if ($LASTEXITCODE -ne 0) {
  throw "GUI publish failed in package step."
}

$receiverFile = Find-BuildFile -Root $buildDir -Name "acb-receiver.exe"
if (-not $receiverFile) {
  throw "acb-receiver.exe not found under build directory: $buildDir"
}
$receiverSrc = $receiverFile.FullName
$virtualcamBridgeSrc = Require-BuildFile -Root $buildDir -Name "acb-virtualcam-bridge.exe"
$virtualcamDriverSrc = Require-BuildFile -Root $buildDir -Name "acb-virtualcam.dll"

$obsEnSrc = Join-Path $repoRoot "windows\obs-plugin\data\locale\en-US.ini"
$obsZhSrc = Join-Path $repoRoot "windows\obs-plugin\data\locale\zh-CN.ini"
$aoaDriverDir = Join-Path $repoRoot "drivers\aoa-winusb"
$guiPublishDir = Join-Path $repoRoot "windows\gui\Acb.Gui\bin\Release\net10.0-windows10.0.19041.0\win-x64\publish"

if (-not (Test-Path $guiPublishDir)) {
  throw "GUI publish directory not found: $guiPublishDir"
}

if (-not $hasRealObsPlugin) {
  Write-Warning "OBS plugin was built as '$obsBuildMode'. A loadable OBS plugin will NOT be packaged. Configure OBS SDK paths for real plugin builds."
  if ($RequireRealObsPlugin) {
    throw "RequireRealObsPlugin=true but build mode is '$obsBuildMode'."
  }
}

$receiverOut = Join-Path $outRoot "receiver"
$guiOut = Join-Path $outRoot "gui"
$prereqOut = Join-Path $outRoot "prereqs"
$virtualcamBridgeOut = Join-Path $outRoot "virtualcam-bridge"
$virtualcamDriverOut = Join-Path $outRoot "virtualcam-driver"
$aoaDriverOut = Join-Path $outRoot "drivers\aoa-winusb"

New-Item -ItemType Directory -Path $receiverOut,$guiOut,$prereqOut,$virtualcamBridgeOut,$virtualcamDriverOut,$aoaDriverOut -Force | Out-Null
Copy-Item $receiverSrc (Join-Path $receiverOut "acb-receiver.exe") -Force
Copy-Item (Join-Path $guiPublishDir "*") $guiOut -Recurse -Force
Copy-Item $virtualcamBridgeSrc (Join-Path $virtualcamBridgeOut "acb-virtualcam-bridge.exe") -Force
Copy-Item $virtualcamDriverSrc (Join-Path $virtualcamDriverOut "acb-virtualcam.dll") -Force
Copy-Item (Join-Path $aoaDriverDir "*") $aoaDriverOut -Recurse -Force

if ($hasRealObsPlugin) {
  if (-not (Test-Path $stagedObsDll)) {
    throw "Staged OBS plugin DLL missing: $stagedObsDll"
  }
  $obsOut = Join-Path $outRoot "obs-plugin"
  $obsLocaleOut = Join-Path $obsOut "locale"
  New-Item -ItemType Directory -Path $obsLocaleOut -Force | Out-Null
  Copy-Item $stagedObsDll (Join-Path $obsOut "acb-obs-plugin.dll") -Force
  Copy-Item $obsEnSrc (Join-Path $obsLocaleOut "en-US.ini") -Force
  Copy-Item $obsZhSrc (Join-Path $obsLocaleOut "zh-CN.ini") -Force
}

$vcRedist = Join-Path $prereqOut "vc_redist.x64.exe"
if (Test-Path $vcRedist) {
  Write-Host "Reusing existing VC++ redistributable: $vcRedist"
} else {
  Invoke-WebRequest -Uri "https://aka.ms/vs/17/release/vc_redist.x64.exe" -OutFile $vcRedist
}

Copy-Item (Join-Path $PSScriptRoot "install-acb.ps1") (Join-Path $outRoot "install-acb.ps1") -Force
Copy-Item (Join-Path $PSScriptRoot "uninstall-acb.ps1") (Join-Path $outRoot "uninstall-acb.ps1") -Force

$files = @(
  "receiver\acb-receiver.exe",
  "gui\*",
  "virtualcam-bridge\acb-virtualcam-bridge.exe",
  "virtualcam-driver\acb-virtualcam.dll",
  "drivers\aoa-winusb\acb-aoa.inf",
  "drivers\aoa-winusb\install-driver.ps1",
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
if (Test-Path (Join-Path $aoaDriverOut "acb-aoa.cat")) {
  $files += "drivers\aoa-winusb\acb-aoa.cat"
}
if (Test-Path (Join-Path $aoaDriverOut "acb-aoa.cer")) {
  $files += "drivers\aoa-winusb\acb-aoa.cer"
}

$meta = [ordered]@{
  version = $Version
  builtAt = (Get-Date).ToString("s")
  obsPluginBuildMode = if ($obsBuildMode) { $obsBuildMode } else { "unknown" }
  files = $files
}
$meta | ConvertTo-Json -Depth 3 | Set-Content -Path (Join-Path $outRoot "package.json") -Encoding UTF8

Write-Host "Package ready: $outRoot"
