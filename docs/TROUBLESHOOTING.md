# Troubleshooting

## Receiver cannot start on 39393

- Ensure no process is using `127.0.0.1:39393`.
- Run `netstat -ano | findstr 39393` and stop the conflicting PID.

## `usb-adb` cannot connect

- Confirm `adb devices` lists the phone.
- Re-run `adb reverse tcp:39393 tcp:39393`.
- Reconnect the cable and allow the USB debugging prompt.

## `usb-native` cannot start

- Check `GET /api/v2/usb-native/devices`.
- Verify the phone can actually reach the Receiver over USB networking.
- If Android receiver address is blank, remember that auto-detection only scans common `192.168.x.x` ranges.

## `usb-aoa` reports unsigned driver or signature errors

- Generate a signed catalog with `pwsh .\scripts\sign-aoa-driver.ps1`.
- Import `acb-aoa.cer` into `Root` and `TrustedPublisher`.
- Re-run `pnputil /add-driver ... /install`.
- Only use `TESTSIGNING` as a fallback if the target machine still refuses the package.

## `failed to open AOA device after 8 attempts`

- The device likely re-enumerated but is not bound to WinUSB yet.
- Reinstall the AOA driver and replug the device.
- Check that `VID_18D1&PID_2D00` or `VID_18D1&PID_2D01` is using WinUSB.
- For local development, Zadig can be used to bind the AOA device to WinUSB manually.

## OBS source not visible

- Confirm plugin files exist in the OBS plugin folder.
- Ensure plugin and OBS are built with compatible toolchain/runtime.
- If build output says `OBS SDK not configured` or `stub-no-obs-sdk`, the produced DLL is a placeholder and will not load in OBS.
- Check the OBS log (`Help -> Log Files -> View Current Log`) for `MODULE_MISSING_EXPORTS` or `Failed to load module`.

## Virtual camera is missing

- Confirm `acb-virtualcam.dll` has been registered with `regsvr32`.
- Confirm `acb-virtualcam-bridge` is running.
- Check whether `GET /api/v2/frame.bgra` returns frames instead of `{"error":"no_frame"}` or `{"error":"stale_frame"}`.

## High latency or frame drops

- Lower bitrate/fps, for example `720p30` at `3-4 Mbps`.
- Prefer `usb-adb` when Wi-Fi is unstable.
- Prefer `usb-aoa` only after the driver path is stable.
- Verify host CPU load stays within budget.
