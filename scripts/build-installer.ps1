#Requires -Version 7.0
param(
  [string]$Version = "0.2.4",
  [string]$ObsIncludeDir = "",
  [string]$ObsGeneratedIncludeDir = "",
  [string]$ObsLibDir = "",
  [bool]$RequireRealObsPlugin = $false
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$payloadOut = Join-Path $repoRoot "dist\acb-win-x64"

function Resolve-Iscc {
  $cmd = Get-Command iscc -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Path }

  $candidates = @(
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe"
  )
  foreach ($c in $candidates) {
    if (Test-Path $c) { return $c }
  }
  return $null
}

Write-Host "Step 1/2: Build package payload"
$packageArgs = @(
  "-NoProfile",
  "-ExecutionPolicy", "Bypass",
  "-File", (Join-Path $PSScriptRoot "package.ps1"),
  "-Version", $Version,
  "-OutDir", $payloadOut,
  "-RequireRealObsPlugin", $RequireRealObsPlugin
)
if ($ObsIncludeDir) {
  $packageArgs += @("-ObsIncludeDir", $ObsIncludeDir)
}
if ($ObsGeneratedIncludeDir) {
  $packageArgs += @("-ObsGeneratedIncludeDir", $ObsGeneratedIncludeDir)
}
if ($ObsLibDir) {
  $packageArgs += @("-ObsLibDir", $ObsLibDir)
}
pwsh @packageArgs
if ($LASTEXITCODE -ne 0) {
  throw "Package step failed."
}

$required = @(
  (Join-Path $payloadOut "receiver\acb-receiver.exe"),
  (Join-Path $payloadOut "gui"),
  (Join-Path $payloadOut "prereqs\vc_redist.x64.exe")
)
foreach ($item in $required) {
  if (-not (Test-Path $item)) {
    Write-Host "Payload directory snapshot:"
    if (Test-Path $payloadOut) {
      Get-ChildItem $payloadOut -Recurse | Select-Object -First 120 FullName | ForEach-Object { Write-Host "  $($_.FullName)" }
    } else {
      Write-Host "  <missing> $payloadOut"
    }
    throw "Required payload item missing: $item"
  }
}

$iscc = Resolve-Iscc
if (-not $iscc) {
  throw "Inno Setup (ISCC.exe) not found. Install Inno Setup 6 and retry."
}

Write-Host "Step 2/2: Compile Inno installer"
$iss = Join-Path $repoRoot "installer\inno\acb.iss"
& $iscc "/DAppVersion=$Version" $iss
if ($LASTEXITCODE -ne 0) {
  throw "Installer build failed."
}

Write-Host "Installer created under: $repoRoot\dist\installer"
