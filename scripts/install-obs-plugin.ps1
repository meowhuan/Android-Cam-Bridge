#Requires -Version 7.0
param(
  [string]$BuildConfig = "Release",
  [string]$ObsPath = ""
)

$ErrorActionPreference = "Stop"

function Resolve-ObsRoots {
  param([string]$UserPath)

  $roots = @()
  if ($UserPath -and (Test-Path $UserPath)) {
    $roots += (Resolve-Path $UserPath).Path
  }

  $steamRoots = @(
    "C:\\Program Files (x86)\\Steam\\steamapps\\common\\OBS Studio",
    "C:\\Program Files\\Steam\\steamapps\\common\\OBS Studio",
    "D:\\SteamLibrary\\steamapps\\common\\OBS Studio",
    "E:\\SteamLibrary\\steamapps\\common\\OBS Studio",
    "F:\\SteamLibrary\\steamapps\\common\\OBS Studio"
  )
  foreach ($r in $steamRoots) {
    if (Test-Path $r) { $roots += $r }
  }

  $roots | Select-Object -Unique
}

$dll = (Resolve-Path (Join-Path $PSScriptRoot "..\\build\\windows\\obs-plugin\\$BuildConfig\\acb-obs-plugin.dll")).Path
$en = (Resolve-Path (Join-Path $PSScriptRoot "..\\windows\\obs-plugin\\data\\locale\\en-US.ini")).Path
$zh = (Resolve-Path (Join-Path $PSScriptRoot "..\\windows\\obs-plugin\\data\\locale\\zh-CN.ini")).Path

$targets = Resolve-ObsRoots -UserPath $ObsPath
if ($targets.Count -eq 0) {
  throw "No OBS installation found. Use -ObsPath to specify your OBS Studio directory."
}

foreach ($root in $targets) {
  $binDir = Join-Path $root "obs-plugins\\64bit"
  $dataLocaleDir = Join-Path $root "data\\obs-plugins\\acb-obs-plugin\\locale"
  New-Item -ItemType Directory -Path $binDir -Force | Out-Null
  New-Item -ItemType Directory -Path $dataLocaleDir -Force | Out-Null

  Copy-Item -Path $dll -Destination (Join-Path $binDir "acb-obs-plugin.dll") -Force
  Copy-Item -Path $en -Destination (Join-Path $dataLocaleDir "en-US.ini") -Force
  Copy-Item -Path $zh -Destination (Join-Path $dataLocaleDir "zh-CN.ini") -Force

  Write-Host "Installed plugin to: $root"
}
