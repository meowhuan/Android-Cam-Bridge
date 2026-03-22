# USB Native (Experimental)

## Goal

`usb-native` is intended as a direct USB transport mode that does not rely on ADB reverse/forward.

Current repository state (phase 1):
- Session start uses `transport=usb-native`.
- GUI skips ADB setup in this mode.
- Android sender uses v2 media path only in this mode (no v1 JPEG/audio HTTP fallback).
- Data path still assumes phone can reach receiver via an IP endpoint over USB networking.

Current repository state (phase 2 skeleton):
- Receiver can enumerate USB interface devices and mark Android-like candidates.
- Added debug APIs:
  - `GET /api/v2/usb-native/devices`
  - `GET /api/v2/usb-native/status`
- `POST /api/v2/session/start` with `transport=usb-native` now performs a USB candidate check.
  - If no Android candidate is found, receiver returns `503 usb_native_device_not_found`.

This means it is **ADB-free**, but not yet a custom raw USB driver transport.

## How to test phase 1

1. Connect phone to PC over USB.
2. Ensure a USB networking path exists (for example USB tethering/RNDIS).
3. Start receiver on PC (`acb-receiver.exe`).
4. In GUI or Android app, select `USB Native`.
5. Use receiver address reachable from phone over USB networking, then start v2 session.

If Android address field is empty, app defaults to `192.168.42.129:39393` for this mode.

## Planned next phase (true raw USB)

To make this truly "raw USB native", these pieces are still needed:

1. Windows side USB device discovery + endpoint management (WinUSB/libusb).
2. Android side USB host/accessory bulk transfer path.
3. Packet framing with sequence/retransmission and flow control.
4. Clock sync strategy for A/V sync under variable USB scheduling.
5. Unified fallback strategy between raw USB and v2 WebSocket mode.

Until then, treat current `usb-native` as **experimental USB-network native mode**.
