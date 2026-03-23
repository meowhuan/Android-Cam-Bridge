# Protocol Overview

当前仓库同时保留：

- v1 兼容控制面与 JPEG 帧接口
- v2 控制面与媒体面
- `usb-native` / `usb-aoa` 两条额外的 v2 传输路径

## Files

- `protocol/proto/signaling.proto`
  - 历史 signaling schema 占位
- `protocol/v2/README.md`
  - 当前仓库已实现的 v2 控制面、媒体面、USB 路径
- `protocol/v2/media_frame.md`
  - 当前 v2 二进制媒体头和 AOA 封包格式

## v1 compatibility surface

Receiver 仍保留以下兼容接口：

- `POST /api/v1/session/offer`
- `POST /api/v1/session/answer`
- `POST /api/v1/session/stop`
- `POST /api/v1/frame`
- `POST /api/v1/audio`
- `GET /api/v1/frame.jpg`
- `GET /api/v1/stats`
- `GET /api/v1/devices`

## v2 surface

当前主路径是 v2：

- `POST /api/v2/adb/setup`
- `POST /api/v2/session/start`
- `POST /api/v2/session/stop`
- `GET /api/v2/session/{sessionId}/stats`
- `GET /api/v2/frame.bgra`
- `WS /ws/v2/media`

附加的 USB 调试/控制接口：

- `GET /api/v2/usb-native/devices`
- `GET /api/v2/usb-native/status`
- `GET /api/v2/usb-native/link`
- `POST /api/v2/usb-native/handshake`
- `POST /api/v2/usb-native/packet`
- `POST /api/v2/usb-aoa/connect`
- `POST /api/v2/usb-aoa/disconnect`
- `GET /api/v2/usb-aoa/status`

## Current media path

- `usb-adb` / `lan`：Android 通过 `WS /ws/v2/media` 发送二进制 packet
- `usb-native`：Android 通过 `/api/v2/usb-native/packet` 发送 Base64 封装 packet
- `usb-aoa`：Android 通过 AOA bulk 发送 framed packet，Receiver 解帧后复用同一套 v2 packet 解析逻辑

无论入口路径如何，Receiver 最终都会：

1. 解析 24-byte v2 头
2. 提取 H.264 / AAC payload
3. 使用 Media Foundation 解码 H.264
4. 将最新 BGRA 帧暴露在 `GET /api/v2/frame.bgra`
