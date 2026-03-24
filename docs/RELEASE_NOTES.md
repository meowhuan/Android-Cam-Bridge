# Release Notes

## Unreleased

- No unreleased notes yet on top of `v1.2.0-beta.4`.

## v1.2.0-beta.4 (2026-03-24)

- Reworked Android capture around a dedicated Camera2 surface pipeline with profile-aware resolution/FPS selection.
- Added separate idle preview and live stream preview flows on Android, with optional background streaming and richer runtime status text.
- Added Android capture mode presets for latency, balanced, and low-light streaming, plus improved actual-profile reporting.
- Improved Android USB AOA attach/reconnect behavior and expanded app-side runtime logging.
- Added live virtual camera controls to the Windows GUI, including bridge start/stop, receiver override, polling interval, and status refresh.
- Reworked the Windows GUI shell with a streamlined navigation layout, structured logs view, localized summary cards, and build identity display.
- Fixed Windows GUI shutdown flow so managed receiver and virtual camera helper processes are stopped more reliably on exit.
- Improved local Windows packaging by auto-detecting the active CMake Visual Studio generator instead of hard-coding VS 2022.
- Passed release version metadata into the published GUI so the app version/channel display matches packaged beta/preview builds.
- Reused an existing VC++ redistributable payload during local packaging instead of forcing a re-download every time.
- Fixed BOM-prefixed commands in the virtual camera bridge control pipe so `START/STOP/STATUS/EXIT` commands are parsed reliably.

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
