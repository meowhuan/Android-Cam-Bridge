# ACB Protocol v2

This version extends v1 without removing any v1 endpoint.

## Control Plane

- `POST /api/v2/session/start`
- `POST /api/v2/session/stop`
- `GET /api/v2/session/{sessionId}/stats`
- `POST /api/v2/adb/setup`

### session/start request example
```json
{
  "transport": "usb-adb",
  "mode": "obs_direct",
  "video": {"codec": "h264", "width": 1280, "height": 720, "fps": 30, "bitrate": 4000000, "keyint": 60},
  "audio": {"codec": "aac", "sampleRate": 48000, "channels": 1, "bitrate": 96000, "enabled": true}
}
```

### session/start response example
```json
{
  "sessionId": "sess_v2_...",
  "wsUrl": "ws://127.0.0.1:39393/ws/v2/media?sessionId=...",
  "authToken": "tok_...",
  "recommendedAspect": "16:9"
}
```

## Media Plane

Media transport target is `WebSocket /ws/v2/media` with binary frames.

Current repository state:
- v2 control plane is implemented.
- v2 media WebSocket endpoint accepts binary frames and updates v2 media stats.
- Receiver decodes v2 H.264 and exposes decoded frames via `GET /api/v2/frame.bgra`.
- OBS plugin prefers `/api/v2/frame.bgra`; when unavailable it falls back to v1 JPEG endpoint.
- v1 JPEG endpoints remain active as compatibility fallback.

## Receiver Decoded Frame Endpoint

- `GET /api/v2/frame.bgra`
  - Binary response layout:
    - bytes `[0..3]`: little-endian `width`
    - bytes `[4..7]`: little-endian `height`
    - bytes `[8..]`: BGRA bytes (`width * height * 4`)
