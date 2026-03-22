#Requires -Version 7.0
param(
  [Parameter(Mandatory = $true)]
  [string]$Command
)

$ErrorActionPreference = "Stop"

$pipe = [System.IO.Pipes.NamedPipeClientStream]::new(".", "acb-virtualcam-control", [System.IO.Pipes.PipeDirection]::InOut)
try {
  $pipe.Connect(3000)
  $writer = [System.IO.StreamWriter]::new($pipe)
  $writer.AutoFlush = $true
  $reader = [System.IO.StreamReader]::new($pipe)

  $writer.WriteLine($Command)
  $resp = $reader.ReadLine()
  if ($null -eq $resp) {
    throw "No response from acb-virtualcam-bridge."
  }
  Write-Host $resp
}
finally {
  $pipe.Dispose()
}
