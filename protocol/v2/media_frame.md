# Media Frame Header (v2 draft)

Fixed-size 24-byte little-endian header:

1. `version` (uint8)
2. `streamType` (uint8): 1=video, 2=audio, 3=meta
3. `codec` (uint8): 1=h264, 2=aac
4. `flags` (uint8): bit0=keyframe
5. `ptsUs` (uint64)
6. `dtsUs` (uint64)
7. `payloadSize` (uint32)

Payload:
- Video: Annex-B NALU sequence
- Audio: AAC ADTS frame sequence
