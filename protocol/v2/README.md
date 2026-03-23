# ACB Protocol v2

当前仓库中的 v2 已经是主要数据面。它扩展了 v1，但没有移除旧的 v1 兼容接口。

## Control plane

- `POST /api/v2/adb/setup`
- `POST /api/v2/session/start`
- `POST /api/v2/session/stop`
- `GET /api/v2/session/{sessionId}/stats`

### `session/start` request example

```json
{
  "transport": "usb-adb",
  "mode": "receiver_gui",
  "video": {
    "codec": "h264",
    "width": 1280,
    "height": 720,
    "fps": 30,
    "bitrate": 4000000,
    "keyint": 60
  },
  "audio": {
    "codec": "aac",
    "sampleRate": 48000,
    "channels": 1,
    "bitrate": 96000,
    "enabled": true
  }
}
```

### `session/start` response example

```json
{
  "sessionId": "sess_v2_...",
  "wsUrl": "ws://127.0.0.1:39393/ws/v2/media?sessionId=...",
  "authToken": "tok_...",
  "recommendedAspect": "16:9"
}
```

当 `transport` 为 `usb-native` 或 `usb-aoa` 时，响应里还会附带对应的链路状态字段。

## Media plane

### `usb-adb` / `lan`

媒体走：

- `WebSocket /ws/v2/media`

发送二进制 packet，格式见 [`media_frame.md`](media_frame.md)。

### `usb-native`

媒体通过：

- `POST /api/v2/usb-native/handshake`
- `POST /api/v2/usb-native/packet`

其中 `/packet` 请求体中的 `payload` 是 Base64 编码后的完整 v2 packet。

### `usb-aoa`

媒体通过 Android Open Accessory bulk 发送。链路前置控制接口为：

- `POST /api/v2/usb-aoa/connect`
- `POST /api/v2/usb-aoa/disconnect`
- `GET /api/v2/usb-aoa/status`

AOA bulk 内部会先包一层 ACB framing header，再承载完整 v2 packet，详见 [`media_frame.md`](media_frame.md)。

## Receiver decoded frame endpoint

- `GET /api/v2/frame.bgra`

二进制响应布局：

- bytes `[0..3]`：little-endian `width`
- bytes `[4..7]`：little-endian `height`
- bytes `[8..]`：BGRA bytes，长度为 `width * height * 4`

常见返回：

- `404 {"error":"no_frame"}`
- `404 {"error":"stale_frame"}`

这个接口被以下组件消费：

- `acb-virtualcam-bridge`
- OBS 插件的 BGRA 拉帧路径

## Session stats

`GET /api/v2/session/{sessionId}/stats` 当前会返回：

- 通用会话字段：`sessionId`、`transport`
- v1/v2 兼容统计：`frameCount`、`audioBytes`、`audioPackets`
- v2 视频统计：`v2VideoBytes`、`v2VideoFrames`、`v2Keyframes`、`v2DecodedFrames`
- v2 音频统计：`v2AudioBytes`、`v2AudioFrames`
- USB 附加状态：`usbNativeConnected`、`usbLinkActive`、`usbLinkRxPackets`、`usbLinkRxBytes` 等
