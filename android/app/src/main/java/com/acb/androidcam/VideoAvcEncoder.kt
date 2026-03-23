package com.acb.androidcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import kotlin.math.min
import java.nio.ByteBuffer

class VideoAvcEncoder(
    private val width: Int,
    private val height: Int,
    bitrate: Int,
    fps: Int,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var frameIndex: Long = 0
    private val fpsValue: Int = if (fps > 0) fps else 30
    private var codecConfig: ByteArray? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(image: ImageProxy, onEncoded: (ByteArray, Boolean) -> Unit) {
        val input = codec.dequeueInputBuffer(0)
        if (input >= 0) {
            val codecImage = try { codec.getInputImage(input) } catch (_: Throwable) { null }
            if (codecImage != null) {
                fillCodecImage(image, codecImage)
                val ptsUs = (++frameIndex) * 1_000_000L / fpsValue.toLong()
                codec.queueInputBuffer(input, 0, width * height * 3 / 2, ptsUs, 0)
            } else {
                val inBuf = codec.getInputBuffer(input)
                if (inBuf != null) {
                    val i420 = imageToI420(image)
                    inBuf.clear()
                    if (inBuf.capacity() >= i420.size) {
                        inBuf.put(i420)
                        val ptsUs = (++frameIndex) * 1_000_000L / fpsValue.toLong()
                        codec.queueInputBuffer(input, 0, i420.size, ptsUs, 0)
                    } else {
                        codec.queueInputBuffer(input, 0, 0, 0, 0)
                    }
                } else {
                    codec.queueInputBuffer(input, 0, 0, 0, 0)
                }
            }
        }

        while (true) {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outIndex < 0) break
            val outBuf = codec.getOutputBuffer(outIndex)
            if (outBuf != null && bufferInfo.size > 0) {
                val bytes = ByteArray(bufferInfo.size)
                outBuf.position(bufferInfo.offset)
                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                outBuf.get(bytes)
                val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (isCodecConfig) {
                    codecConfig = bytes
                } else {
                    val output = if (isKey && codecConfig != null && shouldPrependCodecConfig(codecConfig!!)) {
                        val cfg = codecConfig!!
                        val merged = ByteArray(cfg.size + bytes.size)
                        System.arraycopy(cfg, 0, merged, 0, cfg.size)
                        System.arraycopy(bytes, 0, merged, cfg.size, bytes.size)
                        merged
                    } else {
                        bytes
                    }
                    onEncoded(output, isKey)
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    fun stop() {
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
    }

    private fun shouldPrependCodecConfig(cfg: ByteArray): Boolean {
        if (cfg.size < 4) return false
        // Annex-B start code
        for (i in 0 until min(cfg.size - 3, 32)) {
            if (cfg[i] == 0.toByte() && cfg[i + 1] == 0.toByte() &&
                (cfg[i + 2] == 1.toByte() || (cfg[i + 2] == 0.toByte() && cfg[i + 3] == 1.toByte()))
            ) {
                return true
            }
        }
        // AVCC length-prefixed NAL (single chunk heuristic)
        val naluLen = ((cfg[0].toInt() and 0xFF) shl 24) or
            ((cfg[1].toInt() and 0xFF) shl 16) or
            ((cfg[2].toInt() and 0xFF) shl 8) or
            (cfg[3].toInt() and 0xFF)
        return naluLen in 1 until cfg.size
    }

    /**
     * Copy YUV planes from camera ImageProxy into the codec's input Image,
     * respecting the destination plane layout (NV12/I420/etc.) automatically.
     */
    private fun fillCodecImage(src: ImageProxy, dst: android.media.Image) {
        val crop = src.cropRect
        val cropLeft = crop.left.coerceAtLeast(0)
        val cropTop = crop.top.coerceAtLeast(0)

        // Y plane
        val srcYPlane = src.planes[0]
        val dstYPlane = dst.planes[0]
        val srcYBuf = srcYPlane.buffer
        val dstYBuf = dstYPlane.buffer
        for (row in 0 until height) {
            val srcRowBase = (cropTop + row) * srcYPlane.rowStride
            val dstRowBase = row * dstYPlane.rowStride
            for (col in 0 until width) {
                val srcPos = srcRowBase + (cropLeft + col) * srcYPlane.pixelStride
                val dstPos = dstRowBase + col * dstYPlane.pixelStride
                dstYBuf.put(dstPos, srcYBuf.get(srcPos.coerceIn(0, srcYBuf.limit() - 1)))
            }
        }

        // U and V planes — destination pixelStride handles NV12 vs I420 automatically
        val halfW = width / 2
        val halfH = height / 2
        val uvLeft = cropLeft / 2
        val uvTop = cropTop / 2

        for (planeIdx in 1..2) {
            val srcPlane = src.planes[planeIdx]
            val dstPlane = dst.planes[planeIdx]
            val srcBuf = srcPlane.buffer
            val dstBuf = dstPlane.buffer
            for (row in 0 until halfH) {
                val srcRowBase = (uvTop + row) * srcPlane.rowStride
                val dstRowBase = row * dstPlane.rowStride
                for (col in 0 until halfW) {
                    val srcPos = srcRowBase + (uvLeft + col) * srcPlane.pixelStride
                    val dstPos = dstRowBase + col * dstPlane.pixelStride
                    dstBuf.put(dstPos, srcBuf.get(srcPos.coerceIn(0, srcBuf.limit() - 1)))
                }
            }
        }
    }

    private fun imageToI420(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val crop: Rect = image.cropRect
        val cropLeft = crop.left.coerceAtLeast(0)
        val cropTop = crop.top.coerceAtLeast(0)

        val out = ByteArray(width * height * 3 / 2)
        var offset = 0

        val yBuffer = yPlane.buffer
        for (row in 0 until height) {
            val srcRow = cropTop + row
            val rowBase = srcRow * yPlane.rowStride
            for (col in 0 until width) {
                val srcCol = cropLeft + col
                val pos = rowBase + srcCol * yPlane.pixelStride
                out[offset++] = yBuffer.get(pos.coerceIn(0, yBuffer.limit() - 1))
            }
        }

        val halfW = width / 2
        val halfH = height / 2
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvLeft = cropLeft / 2
        val uvTop = cropTop / 2

        for (row in 0 until halfH) {
            val srcRow = uvTop + row
            val rowBase = srcRow * uPlane.rowStride
            for (col in 0 until halfW) {
                val srcCol = uvLeft + col
                val pos = rowBase + srcCol * uPlane.pixelStride
                out[offset++] = uBuffer.get(pos.coerceAtMost(uBuffer.limit() - 1))
            }
        }
        for (row in 0 until halfH) {
            val srcRow = uvTop + row
            val rowBase = srcRow * vPlane.rowStride
            for (col in 0 until halfW) {
                val srcCol = uvLeft + col
                val pos = rowBase + srcCol * vPlane.pixelStride
                out[offset++] = vBuffer.get(pos.coerceAtMost(vBuffer.limit() - 1))
            }
        }

        return out
    }
}
