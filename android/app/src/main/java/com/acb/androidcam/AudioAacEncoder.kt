package com.acb.androidcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.util.concurrent.atomic.AtomicBoolean

class AudioAacEncoder(
    sampleRate: Int,
    channels: Int,
    bitrate: Int,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val running = AtomicBoolean(true)
    private var ptsUs = 0L
    private val bytesPerSample = 2

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encodePcm(pcm: ByteArray, length: Int, sampleRate: Int, channels: Int, onEncoded: (ByteArray) -> Unit) {
        if (!running.get()) return
        try {
            val input = codec.dequeueInputBuffer(0)
            if (input >= 0) {
                val inBuf = codec.getInputBuffer(input) ?: return
                inBuf.clear()
                val writeLen = minOf(length, inBuf.capacity())
                inBuf.put(pcm, 0, writeLen)
                val samples = writeLen / (channels * bytesPerSample)
                ptsUs += samples * 1_000_000L / sampleRate
                codec.queueInputBuffer(input, 0, writeLen, ptsUs, 0)
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
                    onEncoded(bytes)
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        } catch (_: IllegalStateException) {
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
    }
}
