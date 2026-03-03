package com.acb.androidcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.graphics.Rect
import androidx.camera.core.ImageProxy
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
            val inBuf = codec.getInputBuffer(input) ?: return
            val i420 = imageToI420(image)
            inBuf.clear()
            if (inBuf.capacity() >= i420.size) {
                inBuf.put(i420)
                val ptsUs = (++frameIndex) * 1_000_000L / 30L
                codec.queueInputBuffer(input, 0, i420.size, ptsUs, 0)
            } else {
                codec.queueInputBuffer(input, 0, 0, 0, 0)
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
                val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                onEncoded(bytes, isKey)
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
