#Requires -Version 7.0
$ErrorActionPreference = "Stop"

Write-Host "[bootstrap] checking tools..."
$tools = @("cmake", "adb")
foreach ($tool in $tools) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    Write-Warning "$tool is not found in PATH"
  } else {
    Write-Host "[ok] $tool"
  }
}

Write-Host "[bootstrap] done"
