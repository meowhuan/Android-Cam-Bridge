# Release Notes

## Unreleased

- No unreleased notes yet on top of `v1.2.0-beta.1`.

## v1.2.0-beta.1 (2026-03-23)

- Added `usb-aoa` transport with WinUSB-based Android Open Accessory bulk transfer.
- Added bundled AOA WinUSB driver assets under `drivers/aoa-winusb/`.
- Added `scripts/sign-aoa-driver.ps1` for catalog generation, test signing, and `.cer/.pfx` export.
- Added optional AOA test certificate packaging for CI and release workflows.
- Added installer tasks to import AOA test certificate and install the WinUSB driver.
- Added `acb-virtualcam-bridge` shared-memory bridge for `GET /api/v2/frame.bgra`.
- Added `acb-virtualcam.dll` DirectShow virtual camera driver and installer registration flow.
- Expanded GUI with `usb-native` and `usb-aoa` status/control panels.
- Fixed Android H.264 encoder packet layout issue that could cause green/pink artifacts.
- Fixed Receiver NV12-to-BGRA color conversion with BT.709 and stride handling.

## v0.1.0-scaffold (2026-03-03)

- Initialized Android + Windows + protocol + installer monorepo structure.
- Added initial Receiver local API skeleton on `localhost:39393`.
- Added OBS plugin and virtual camera scaffolding.
- Added setup and troubleshooting documentation in English and Simplified Chinese.
