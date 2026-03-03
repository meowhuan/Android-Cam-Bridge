#Requires -Version 7.0
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

cmake -S $repoRoot -B (Join-Path $repoRoot "build") -G "Visual Studio 17 2022" -A x64
cmake --build (Join-Path $repoRoot "build") --config Release --target acb-receiver
if ($LASTEXITCODE -ne 0) {
    throw "Receiver build failed."
}

pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "embed-receiver.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "Embed receiver step failed."
}

Set-Location (Join-Path $repoRoot "windows\gui\Acb.Gui")

dotnet publish `
  -c Release `
  -r win-x64 `
  -p:DefineConstants="ACB_EMBED_RECEIVER" `
  -p:SelfContained=true

if ($LASTEXITCODE -ne 0) {
    throw "GUI publish failed."
}

$out = Join-Path $PWD "bin\Release\net10.0-windows10.0.19041.0\win-x64\publish"
Write-Host "Published GUI at: $out"
