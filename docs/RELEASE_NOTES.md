# Release Notes

## Unreleased

- Added `usb-aoa` transport with WinUSB-based Android Open Accessory bulk transfer.
- Added bundled WinUSB driver files under `drivers/aoa-winusb/`.
- Added `acb-virtualcam-bridge` shared-memory bridge for `GET /api/v2/frame.bgra`.
- Added `acb-virtualcam.dll` DirectShow virtual camera driver.
- Expanded GUI with `usb-native` and `usb-aoa` status/control panels.
- Updated CI and release packaging to validate and ship AOA/VirtualCam artifacts.

## v0.1.0-scaffold (2026-03-03)

- Initialized monorepo structure for Android + Windows + protocol + installer.
- Added receiver local API skeleton on `localhost:39393`.
- Added OBS plugin source contract scaffold (`android_cam_source`).
- Added virtual camera bridge contract scaffold for v0.2.
- Added setup and troubleshooting docs in English and Simplified Chinese.
