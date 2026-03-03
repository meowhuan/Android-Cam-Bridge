# Troubleshooting

## Receiver cannot start on 39393
- Ensure no process is using `127.0.0.1:39393`.
- Run `netstat -ano | findstr 39393` and stop conflicting PID.

## Android cannot connect over USB
- Confirm `adb devices` lists your phone.
- Re-run `adb reverse tcp:39393 tcp:39393`.
- Reconnect cable and allow USB debugging prompt.

## OBS source not visible
- Confirm plugin binary exists in OBS plugin folder.
- Ensure plugin and OBS are built with compatible toolchain/runtime.
- If build output says `OBS SDK not configured` then produced DLL is a fallback stub and will not load in OBS.
- Check OBS log (`Help -> Log Files -> View Current Log`) for `MODULE_MISSING_EXPORTS` or `Failed to load module`.

## High latency or frame drops
- Lower bitrate/fps (e.g. 720p30, 3 Mbps).
- Prefer USB ADB mode on unstable Wi-Fi.
- Verify CPU load is below target.
