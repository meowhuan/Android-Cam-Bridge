#Requires -Version 7.0
param(
  [string]$Version = "0.2.3",
  [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
if (-not $OutDir) {
  $OutDir = Join-Path $PSScriptRoot "..\dist\acb-win-x64"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$outRoot = (Resolve-Path (New-Item -ItemType Directory -Path $OutDir -Force)).Path

Write-Host "Packaging ACB version $Version"

cmake -S $repoRoot -B (Join-Path $repoRoot "build") -G "Visual Studio 17 2022" -A x64
cmake --build (Join-Path $repoRoot "build") --config Release --target acb-receiver
cmake --build (Join-Path $repoRoot "build") --config Release --target acb-obs-plugin

pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "publish-gui.ps1")
if ($LASTEXITCODE -ne 0) {
  throw "GUI publish failed in package step."
}

$receiverSrc = Join-Path $repoRoot "build\windows\receiver\Release\acb-receiver.exe"
$obsDllSrc = Join-Path $repoRoot "build\windows\obs-plugin\Release\acb-obs-plugin.dll"
$obsEnSrc = Join-Path $repoRoot "windows\obs-plugin\data\locale\en-US.ini"
$obsZhSrc = Join-Path $repoRoot "windows\obs-plugin\data\locale\zh-CN.ini"
$guiPublishDir = Join-Path $repoRoot "windows\gui\Acb.Gui\bin\Release\net10.0-windows10.0.19041.0\win-x64\publish"

if (-not (Test-Path $guiPublishDir)) {
  throw "GUI publish directory not found: $guiPublishDir"
}

$receiverOut = Join-Path $outRoot "receiver"
$guiOut = Join-Path $outRoot "gui"
$obsOut = Join-Path $outRoot "obs-plugin"
$obsLocaleOut = Join-Path $obsOut "locale"
$prereqOut = Join-Path $outRoot "prereqs"

New-Item -ItemType Directory -Path $receiverOut,$guiOut,$obsLocaleOut,$prereqOut -Force | Out-Null
Copy-Item $receiverSrc (Join-Path $receiverOut "acb-receiver.exe") -Force
Copy-Item (Join-Path $guiPublishDir "*") $guiOut -Recurse -Force
Copy-Item $obsDllSrc (Join-Path $obsOut "acb-obs-plugin.dll") -Force
Copy-Item $obsEnSrc (Join-Path $obsLocaleOut "en-US.ini") -Force
Copy-Item $obsZhSrc (Join-Path $obsLocaleOut "zh-CN.ini") -Force

$vcRedist = Join-Path $prereqOut "vc_redist.x64.exe"
Invoke-WebRequest -Uri "https://aka.ms/vs/17/release/vc_redist.x64.exe" -OutFile $vcRedist

Copy-Item (Join-Path $PSScriptRoot "install-acb.ps1") (Join-Path $outRoot "install-acb.ps1") -Force
Copy-Item (Join-Path $PSScriptRoot "uninstall-acb.ps1") (Join-Path $outRoot "uninstall-acb.ps1") -Force

$meta = [ordered]@{
  version = $Version
  builtAt = (Get-Date).ToString("s")
  files = @(
    "receiver\acb-receiver.exe",
    "gui\*",
    "obs-plugin\acb-obs-plugin.dll",
    "obs-plugin\locale\en-US.ini",
    "obs-plugin\locale\zh-CN.ini",
    "prereqs\vc_redist.x64.exe",
    "install-acb.ps1",
    "uninstall-acb.ps1"
  )
}
$meta | ConvertTo-Json -Depth 3 | Set-Content -Path (Join-Path $outRoot "package.json") -Encoding UTF8

Write-Host "Package ready: $outRoot"
