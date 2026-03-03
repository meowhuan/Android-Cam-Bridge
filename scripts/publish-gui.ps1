#Requires -Version 7.0
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$buildDir = Join-Path $repoRoot "build"
Set-Location $repoRoot

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

cmake -S $repoRoot -B $buildDir -G "Visual Studio 17 2022" -A x64
cmake --build $buildDir --config Release --target acb-receiver
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
