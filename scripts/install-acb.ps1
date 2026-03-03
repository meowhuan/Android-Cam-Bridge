#Requires -Version 7.0
param(
  [string]$SourceRoot = "",
  [string]$InstallRoot = "C:\Program Files\ACB",
  [switch]$InstallReceiver = $true,
  [switch]$InstallGui = $true,
  [switch]$InstallObsPlugin = $true,
  [string]$ObsPath = ""
)

$ErrorActionPreference = "Stop"

if (-not $SourceRoot) {
  $SourceRoot = Join-Path $PSScriptRoot "..\dist\acb-win-x64"
}
$SourceRoot = (Resolve-Path $SourceRoot).Path

function Copy-WithManifest {
  param(
    [string]$From,
    [string]$To,
    [System.Collections.Generic.List[string]]$Manifest
  )
  New-Item -ItemType Directory -Path (Split-Path -Parent $To) -Force | Out-Null
  Copy-Item -Path $From -Destination $To -Force
  $Manifest.Add($To)
}

function Copy-DirWithManifest {
  param(
    [string]$FromDir,
    [string]$ToDir,
    [System.Collections.Generic.List[string]]$Manifest
  )
  New-Item -ItemType Directory -Path $ToDir -Force | Out-Null
  Copy-Item -Path (Join-Path $FromDir "*") -Destination $ToDir -Recurse -Force
  $files = Get-ChildItem -Path $ToDir -Recurse -File | Select-Object -ExpandProperty FullName
  foreach ($f in $files) { $Manifest.Add($f) }
}

function Resolve-ObsRoots {
  param([string]$UserPath)
  $roots = @()
  if ($UserPath -and (Test-Path $UserPath)) { $roots += (Resolve-Path $UserPath).Path }
  $defaults = @(
    "C:\Program Files (x86)\Steam\steamapps\common\OBS Studio",
    "C:\Program Files\Steam\steamapps\common\OBS Studio",
    "D:\SteamLibrary\steamapps\common\OBS Studio",
    "E:\SteamLibrary\steamapps\common\OBS Studio",
    "F:\SteamLibrary\steamapps\common\OBS Studio"
  )
  foreach ($d in $defaults) {
    if (Test-Path $d) { $roots += $d }
  }
  $roots | Select-Object -Unique
}

$manifest = [System.Collections.Generic.List[string]]::new()
New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null

$selfUninstallSrc = Join-Path $SourceRoot "uninstall-acb.ps1"
if (Test-Path $selfUninstallSrc) {
  $selfUninstallDst = Join-Path $InstallRoot "uninstall-acb.ps1"
  Copy-WithManifest -From $selfUninstallSrc -To $selfUninstallDst -Manifest $manifest
}

if ($InstallReceiver) {
  $src = Join-Path $SourceRoot "receiver\acb-receiver.exe"
  $dst = Join-Path $InstallRoot "receiver\acb-receiver.exe"
  Copy-WithManifest -From $src -To $dst -Manifest $manifest
}

if ($InstallGui) {
  $srcDir = Join-Path $SourceRoot "gui"
  $dstDir = Join-Path $InstallRoot "gui"
  Copy-DirWithManifest -FromDir $srcDir -ToDir $dstDir -Manifest $manifest
}

if ($InstallObsPlugin) {
  $pluginDll = Join-Path $SourceRoot "obs-plugin\acb-obs-plugin.dll"
  $en = Join-Path $SourceRoot "obs-plugin\locale\en-US.ini"
  $zh = Join-Path $SourceRoot "obs-plugin\locale\zh-CN.ini"
  $obsRoots = Resolve-ObsRoots -UserPath $ObsPath
  foreach ($root in $obsRoots) {
    $bin = Join-Path $root "obs-plugins\64bit\acb-obs-plugin.dll"
    $enDst = Join-Path $root "data\obs-plugins\acb-obs-plugin\locale\en-US.ini"
    $zhDst = Join-Path $root "data\obs-plugins\acb-obs-plugin\locale\zh-CN.ini"
    Copy-WithManifest -From $pluginDll -To $bin -Manifest $manifest
    Copy-WithManifest -From $en -To $enDst -Manifest $manifest
    Copy-WithManifest -From $zh -To $zhDst -Manifest $manifest
  }
}

$manifestPath = Join-Path $InstallRoot "install-manifest.json"
$meta = [ordered]@{
  installedAt = (Get-Date).ToString("s")
  sourceRoot = $SourceRoot
  files = $manifest
}
$meta | ConvertTo-Json -Depth 4 | Set-Content -Path $manifestPath -Encoding UTF8
Write-Host "Installed ACB to: $InstallRoot"
Write-Host "Manifest: $manifestPath"
