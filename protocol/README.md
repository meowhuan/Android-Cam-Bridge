# Protocol Overview

Primary schema: `proto/signaling.proto`.

Envelope format:
```json
{
  "version": 1,
  "type": "SessionOffer",
  "payload": "base64-protobuf"
}
```

JSON fallback examples:

`SessionOffer`
```json
{
  "sessionId": "sess_001",
  "transport": "usb-adb",
  "offerSdp": "v=0...",
  "deviceId": "android-serial",
  "video": {"width": 1920, "height": 1080, "fps": 30, "bitrate": 5000000},
  "audio": {"enabled": true}
}
```
