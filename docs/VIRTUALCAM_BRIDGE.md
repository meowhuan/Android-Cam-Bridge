# VirtualCam Bridge

`acb-virtualcam-bridge` is a Windows-side frame bridge process for system camera ecosystem integration.

## What it does

- Pulls decoded BGRA frames from receiver endpoint:
  - `GET /api/v2/frame.bgra`
- Publishes frames into shared memory:
  - map name: `Local\acb_virtualcam_frame`
- Exposes control commands over named pipe:
  - `\\.\pipe\acb-virtualcam-control`

This allows a future virtual camera producer/driver to consume frames from a stable local IPC contract.

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
- A full system virtual camera exposure still requires a consumer side (DirectShow/MediaFoundation virtual camera provider) to read this shared memory and publish as camera device.
