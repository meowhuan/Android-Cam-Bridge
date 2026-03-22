#Requires -Version 7.0
param(
  [string]$Receiver = "127.0.0.1:39393",
  [int]$IntervalMs = 20,
  [string]$Python = "python",
  [string]$Device = "",
  [int]$Fps = 30,
  [switch]$Foreground
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$bridgeExe = Join-Path $repoRoot "build\windows\virtualcam-bridge\Release\acb-virtualcam-bridge.exe"
$consumerPy = Join-Path $repoRoot "scripts\virtualcam_consumer.py"

if (-not (Test-Path $bridgeExe)) {
  Write-Host "Bridge executable not found, building target acb-virtualcam-bridge..."
  cmake --build (Join-Path $repoRoot "build") --config Release --target acb-virtualcam-bridge
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $bridgeExe)) {
    throw "Bridge executable not found after build: $bridgeExe"
  }
}
if (-not (Test-Path $consumerPy)) {
  throw "Consumer script not found: $consumerPy"
}

$bridgeProc = Get-Process -Name "acb-virtualcam-bridge" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $bridgeProc) {
  Start-Process -FilePath $bridgeExe | Out-Null
  Start-Sleep -Milliseconds 600
}

pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "send-vcam-command.ps1") -Command "SET_RECEIVER $Receiver" | Out-Null
pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "send-vcam-command.ps1") -Command "SET_INTERVAL $IntervalMs" | Out-Null
pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "send-vcam-command.ps1") -Command "START" | Out-Null

$args = @($consumerPy, "--fps", "$Fps")
if ($Device) {
  $args += @("--device", $Device)
}

function Quote-ProcessArg {
  param([string]$Value)
  if ($null -eq $Value) { return '""' }
  if ($Value -notmatch '[\s"]') { return $Value }
  $escaped = $Value -replace '(\\*)"', '$1$1\"'
  $escaped = $escaped -replace '(\\+)$', '$1$1'
  return '"' + $escaped + '"'
}

$logDir = Join-Path $repoRoot "dist\logs"
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$outLog = Join-Path $logDir "virtualcam-consumer.out.log"
$errLog = Join-Path $logDir "virtualcam-consumer.err.log"

if ($Foreground) {
  & $Python @args
  exit $LASTEXITCODE
}

$argLine = ($args | ForEach-Object { Quote-ProcessArg $_ }) -join " "
$proc = Start-Process -FilePath $Python -ArgumentList $argLine -PassThru -RedirectStandardOutput $outLog -RedirectStandardError $errLog
Start-Sleep -Milliseconds 1200
if ($proc.HasExited) {
  $err = if (Test-Path $errLog) { Get-Content $errLog -Raw } else { "" }
  $out = if (Test-Path $outLog) { Get-Content $outLog -Raw } else { "" }
  throw "virtualcam consumer exited immediately. stdout: $out`nstderr: $err"
}

Write-Host "ACB virtual camera pipeline started."
Write-Host "Receiver=$Receiver IntervalMs=$IntervalMs Fps=$Fps"
Write-Host "Consumer logs: $outLog / $errLog"
