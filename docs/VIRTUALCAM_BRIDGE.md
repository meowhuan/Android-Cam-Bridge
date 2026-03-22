# VirtualCam Bridge

`acb-virtualcam-bridge` is a Windows-side frame bridge process for system camera ecosystem integration.

## What it does

- Pulls decoded BGRA frames from receiver endpoint:
  - `GET /api/v2/frame.bgra`
- Publishes frames into shared memory:
  - map name: `Local\acb_virtualcam_frame`
- Exposes control commands over named pipe:
  - `\\.\pipe\acb-virtualcam-control`

This allows a virtual camera producer/driver (for example UnityCapture backend via `pyvirtualcam`) to consume frames from a stable local IPC contract.

## Control commands

Send one line command to named pipe:

- `START`
- `STOP`
- `SET_RECEIVER <host:port>`
- `SET_INTERVAL <ms>`
- `STATUS`
- `EXIT`

## Shared memory layout

At map start:
- `SharedFrameHeader`

Then frame slots:
- `slotCount = 3`
- `slotSize = 3840*2160*4`
- payload format: BGRA

Header fields include width/height/frameSize/frameIndex/pts to let consumers read latest frame safely.

## Notes

- This is the bridge side implementation.
- A full system virtual camera exposure requires a consumer side to read shared memory and publish as camera device.

## Scheme 1: Use existing virtual camera driver (recommended quick path)

Prerequisites:
- Install UnityCapture (or another `pyvirtualcam` compatible backend)
- Python 3.10+
- `pip install pyvirtualcam numpy`

Start pipeline:
```powershell
pwsh .\scripts\start-virtualcam.ps1 -Receiver "127.0.0.1:39393" -Fps 30
```

Stop bridge pull:
```powershell
pwsh .\scripts\stop-virtualcam.ps1
```

Manual commands:
```powershell
pwsh .\scripts\send-vcam-command.ps1 -Command "STATUS"
pwsh .\scripts\send-vcam-command.ps1 -Command "SET_RECEIVER 127.0.0.1:39393"
pwsh .\scripts\send-vcam-command.ps1 -Command "START"
```
