#Requires -Version 7.0
Set-Location "$PSScriptRoot\.."

cmake --build build --config Release --target acb-receiver
if ($LASTEXITCODE -ne 0) {
    throw "Receiver build failed."
}

pwsh -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot\embed-receiver.ps1"
if ($LASTEXITCODE -ne 0) {
    throw "Embed receiver step failed."
}

Set-Location "$PSScriptRoot\..\windows\gui\Acb.Gui"

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
