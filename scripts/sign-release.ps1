#Requires -Version 7.0
param(
  [string]$AssetsDir = "",
  [string]$KeyId = "",
  [string]$Passphrase = ""
)

$ErrorActionPreference = "Stop"

if (-not $AssetsDir) {
  $AssetsDir = Join-Path $PSScriptRoot "..\dist\release-assets"
}
$AssetsDir = (Resolve-Path $AssetsDir).Path

if (-not (Get-Command gpg -ErrorAction SilentlyContinue)) {
  throw "gpg not found in PATH."
}

if (-not $KeyId) {
  $fingerprints = gpg --batch --list-secret-keys --with-colons | Select-String '^fpr:' | ForEach-Object { $_.ToString().Split(':')[9] }
  $KeyId = $fingerprints | Select-Object -First 1
  if (-not $KeyId) { throw "No secret key found. Import your private key first." }
}

Get-ChildItem -Path $AssetsDir -File | Where-Object { $_.Extension -ne ".asc" } | ForEach-Object {
  $ascPath = "$($_.FullName).asc"
  if (Test-Path $ascPath) { Remove-Item $ascPath -Force }

  gpg --batch --yes --pinentry-mode loopback --passphrase "$Passphrase" --local-user "$KeyId" --armor --detach-sign "$($_.FullName)"
}

Write-Host "Signed release assets under: $AssetsDir"
