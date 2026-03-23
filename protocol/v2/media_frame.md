# Media Frame Format (v2)

当前 Receiver 解析的 v2 媒体头是固定 24-byte little-endian header：

1. `version` (`uint8`)
2. `streamType` (`uint8`)
   - `1` = video
   - `2` = audio
   - `3` = meta / keepalive
3. `codec` (`uint8`)
   - `1` = H.264
   - `2` = AAC
   - `0` = meta / reserved
4. `flags` (`uint8`)
   - bit0 = keyframe
5. `ptsUs` (`uint64`)
6. `dtsUs` (`uint64`)
7. `payloadSize` (`uint32`)

Payload:

- Video: H.264 bitstream
- Audio: AAC frame data
- Meta: empty payload is allowed

## Raw v2 packet layout

```text
+----------------------+-------------------+
| 24-byte v2 header    | payload bytes     |
+----------------------+-------------------+
```

当前 Receiver 支持：

- 直接 Annex-B NALU
- AVCC NALU packet，在 Receiver 侧转换为 Annex-B
- 忽略 AVC Decoder Configuration Record

## AOA framing envelope

`usb-aoa` 并不是直接把 v2 packet 裸写进 USB，而是在外面再包一层 ACB framing：

### Envelope layout

```text
+----------------------+----------------------+-------------------+
| magic "ACB\x01" (4)  | frameLength LE (4)   | v2 packet bytes   |
+----------------------+----------------------+-------------------+
```

字段说明：

- `magic` = `0x41 0x43 0x42 0x01`
- `frameLength` = 后续完整 v2 packet 长度，little-endian `uint32`

Android 侧由 `UsbAccessoryTransport` 负责封包，Windows 侧由 `UsbAoaTransport` 负责解帧。

## Keepalive

`usb-aoa` 空闲时会发送 `streamType=3`、`codec=0`、`payloadSize=0` 的 header-only keepalive packet，用于维持链路活跃。

## BGRA decoded frame output

当 Receiver 成功解码 H.264 后，会把最新 BGRA 帧缓存下来，并通过：

- `GET /api/v2/frame.bgra`

以：

```text
[4 bytes width LE][4 bytes height LE][BGRA pixel bytes]
```

的形式暴露给 virtual camera bridge 和其他本地消费者。
