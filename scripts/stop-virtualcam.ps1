#Requires -Version 7.0
$ErrorActionPreference = "SilentlyContinue"

pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "send-vcam-command.ps1") -Command "STOP" | Out-Null

Write-Host "Sent STOP to acb-virtualcam-bridge."
Write-Host "If needed, stop consumer process manually (python virtualcam_consumer.py)."
