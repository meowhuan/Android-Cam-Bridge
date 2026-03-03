#Requires -Version 7.0
$ErrorActionPreference = "Stop"

$receiver = Start-Process -FilePath "$PSScriptRoot\..\build\windows\receiver\Release\acb-receiver.exe" -PassThru
Start-Sleep -Milliseconds 700

try {
  $start = Invoke-RestMethod -Uri "http://127.0.0.1:39393/api/v2/session/start" `
    -Method Post `
    -ContentType "application/json" `
    -Body '{"transport":"lan","mode":"obs_direct","video":{"codec":"h264"},"audio":{"codec":"aac","enabled":true}}'
  $sid = $start.sessionId

  $ws = [System.Net.WebSockets.ClientWebSocket]::new()
  $ws.ConnectAsync([Uri]$start.wsUrl, [Threading.CancellationToken]::None).GetAwaiter().GetResult()

  # Build one v2 media frame: 24-byte header + payload
  $payload = [byte[]](1..32)
  $buf = New-Object byte[] (24 + $payload.Length)
  $buf[0] = 1  # version
  $buf[1] = 1  # streamType video
  $buf[2] = 1  # codec h264
  $buf[3] = 1  # keyframe
  [BitConverter]::GetBytes([uint32]$payload.Length).CopyTo($buf, 20)
  [Array]::Copy($payload, 0, $buf, 24, $payload.Length)

  $seg = [ArraySegment[byte]]::new($buf)
  $ws.SendAsync($seg, [System.Net.WebSockets.WebSocketMessageType]::Binary, $true, [Threading.CancellationToken]::None).GetAwaiter().GetResult()
  try {
    $ws.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "ok", [Threading.CancellationToken]::None).GetAwaiter().GetResult()
  } catch {
    # Receiver may close socket directly in current scaffold implementation.
  }

  Start-Sleep -Milliseconds 200
  $stats = Invoke-RestMethod -Uri "http://127.0.0.1:39393/api/v2/session/$sid/stats"
  "session=$sid v2VideoFrames=$($stats.v2VideoFrames) v2VideoBytes=$($stats.v2VideoBytes)"
}
finally {
  Stop-Process -Id $receiver.Id -Force -ErrorAction SilentlyContinue
}
