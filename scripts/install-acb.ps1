#Requires -Version 7.0
param(
  [string]$SourceRoot = "",
  [string]$InstallRoot = "C:\Program Files\ACB",
  [switch]$InstallReceiver = $true,
  [switch]$InstallGui = $true,
  [switch]$InstallObsPlugin = $true,
  [switch]$InstallVirtualCamBridge = $true,
  [switch]$InstallVirtualCam = $true,
  [switch]$RegisterVirtualCam = $true,
  [switch]$InstallAoaDriverFiles = $true,
  [switch]$InstallAoaDriver = $true,
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
  if (-not (Test-Path $From)) {
    Write-Warning "Source file not found, skipping: $From"
    return $false
  }
  New-Item -ItemType Directory -Path (Split-Path -Parent $To) -Force | Out-Null
  Copy-Item -Path $From -Destination $To -Force
  $Manifest.Add($To)
  return $true
}

function Copy-DirWithManifest {
  param(
    [string]$FromDir,
    [string]$ToDir,
    [System.Collections.Generic.List[string]]$Manifest
  )
  if (-not (Test-Path $FromDir)) {
    Write-Warning "Source directory not found, skipping: $FromDir"
    return $false
  }
  New-Item -ItemType Directory -Path $ToDir -Force | Out-Null
  Copy-Item -Path (Join-Path $FromDir "*") -Destination $ToDir -Recurse -Force
  $files = Get-ChildItem -Path $ToDir -Recurse -File | Select-Object -ExpandProperty FullName
  foreach ($f in $files) { $Manifest.Add($f) }
  return $true
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

function Invoke-CheckedProcess {
  param(
    [string]$FilePath,
    [string[]]$ArgumentList,
    [string]$ErrorMessage
  )
  $proc = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -Wait -PassThru -NoNewWindow
  if ($proc.ExitCode -ne 0) {
    throw "$ErrorMessage (exit code $($proc.ExitCode))"
  }
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
  [void](Copy-WithManifest -From $src -To $dst -Manifest $manifest)
}

if ($InstallGui) {
  $srcDir = Join-Path $SourceRoot "gui"
  $dstDir = Join-Path $InstallRoot "gui"
  [void](Copy-DirWithManifest -FromDir $srcDir -ToDir $dstDir -Manifest $manifest)
}

if ($InstallObsPlugin) {
  $pluginDll = Join-Path $SourceRoot "obs-plugin\acb-obs-plugin.dll"
  $en = Join-Path $SourceRoot "obs-plugin\locale\en-US.ini"
  $zh = Join-Path $SourceRoot "obs-plugin\locale\zh-CN.ini"
  $obsRoots = Resolve-ObsRoots -UserPath $ObsPath
  if (-not (Test-Path $pluginDll)) {
    Write-Warning "OBS plugin payload not found, skipping OBS plugin install."
  }
  foreach ($root in $obsRoots) {
    if (-not (Test-Path $pluginDll)) { break }
    $bin = Join-Path $root "obs-plugins\64bit\acb-obs-plugin.dll"
    $enDst = Join-Path $root "data\obs-plugins\acb-obs-plugin\locale\en-US.ini"
    $zhDst = Join-Path $root "data\obs-plugins\acb-obs-plugin\locale\zh-CN.ini"
    [void](Copy-WithManifest -From $pluginDll -To $bin -Manifest $manifest)
    [void](Copy-WithManifest -From $en -To $enDst -Manifest $manifest)
    [void](Copy-WithManifest -From $zh -To $zhDst -Manifest $manifest)
  }
}

$virtualCamDllDst = $null
if ($InstallVirtualCamBridge) {
  $src = Join-Path $SourceRoot "virtualcam-bridge\acb-virtualcam-bridge.exe"
  $dst = Join-Path $InstallRoot "virtualcam-bridge\acb-virtualcam-bridge.exe"
  [void](Copy-WithManifest -From $src -To $dst -Manifest $manifest)
}

if ($InstallVirtualCam) {
  $src = Join-Path $SourceRoot "virtualcam-driver\acb-virtualcam.dll"
  $dst = Join-Path $InstallRoot "virtualcam-driver\acb-virtualcam.dll"
  if (Copy-WithManifest -From $src -To $dst -Manifest $manifest) {
    $virtualCamDllDst = $dst
  }
}

if ($InstallAoaDriverFiles) {
  $srcDir = Join-Path $SourceRoot "drivers\aoa-winusb"
  $dstDir = Join-Path $InstallRoot "drivers\aoa-winusb"
  [void](Copy-DirWithManifest -FromDir $srcDir -ToDir $dstDir -Manifest $manifest)
}

if ($RegisterVirtualCam -and $virtualCamDllDst -and (Test-Path $virtualCamDllDst)) {
  Invoke-CheckedProcess -FilePath "regsvr32.exe" -ArgumentList @("/s", $virtualCamDllDst) -ErrorMessage "Virtual camera registration failed"
}

if ($InstallAoaDriver) {
  $driverScript = Join-Path $InstallRoot "drivers\aoa-winusb\install-driver.ps1"
  if (Test-Path $driverScript) {
    Invoke-CheckedProcess -FilePath "pwsh.exe" -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $driverScript) -ErrorMessage "AOA WinUSB driver installation failed"
  } else {
    Write-Warning "AOA driver install script not found, skipping driver installation."
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
