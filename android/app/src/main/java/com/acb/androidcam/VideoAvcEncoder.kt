package com.acb.androidcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class VideoAvcEncoder(
    private val width: Int,
    private val height: Int,
    bitrate: Int,
    fps: Int,
    private val onEncoded: (ByteArray, Boolean) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val bufferInfo = MediaCodec.BufferInfo()
    private val running = AtomicBoolean(true)
    private var codecConfig: ByteArray? = null
    private var outputFrames = 0L
    private var drainThread: Thread? = null
    val inputSurface: Surface

    init {
        val safeFps = if (fps > 0) fps else 30
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0)
            }
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        AppLog.i("VideoAvcEncoder", "configured surface encoder ${width}x${height} fps=$safeFps bitrate=$bitrate")
        startDrainLoop()
    }

    private fun startDrainLoop() {
        drainThread = Thread({ drainLoop() }, "AcbVideoDrain").also { it.start() }
    }

    private fun drainLoop() {
        while (running.get()) {
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (t: Throwable) {
                if (running.get()) {
                    AppLog.e("VideoAvcEncoder", "dequeueOutputBuffer failed: ${t.message}", t)
                }
                break
            }

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                AppLog.i("VideoAvcEncoder", "output format changed: ${codec.outputFormat}")
                continue
            }
            if (outIndex < 0) {
                continue
            }

            try {
                val outBuf = codec.getOutputBuffer(outIndex)
                if (outBuf != null && bufferInfo.size > 0) {
                    val bytes = ByteArray(bufferInfo.size)
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    outBuf.get(bytes)
                    val isCodecConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    if (isCodecConfig) {
                        codecConfig = normalizeCodecConfig(bytes)
                    } else {
                        val normalized = normalizeVideoPayload(bytes)
                        val output = if (isKey && codecConfig != null) {
                            merge(codecConfig!!, normalized)
                        } else {
                            normalized
                        }
                        outputFrames += 1
                        if (outputFrames == 1L || outputFrames % 120L == 0L) {
                            AppLog.i(
                                "VideoAvcEncoder",
                                "encoded frames=$outputFrames size=${output.size} key=$isKey",
                            )
                        }
                        onEncoded(output, isKey)
                    }
                }
            } catch (t: Throwable) {
                if (running.get()) {
                    AppLog.e("VideoAvcEncoder", "drain output failed: ${t.message}", t)
                }
            } finally {
                try {
                    codec.releaseOutputBuffer(outIndex, false)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            codec.signalEndOfInputStream()
        } catch (_: Throwable) {
        }
        try {
            drainThread?.join(500)
        } catch (_: InterruptedException) {
        }
        drainThread = null
        try {
            inputSurface.release()
        } catch (_: Throwable) {
        }
        try {
            codec.stop()
        } catch (_: Throwable) {
        }
        try {
            codec.release()
        } catch (_: Throwable) {
        }
    }

    private fun normalizeVideoPayload(data: ByteArray): ByteArray {
        if (hasAnnexBStartCode(data)) return data
        return convertLengthPrefixedNalToAnnexB(data) ?: data
    }

    private fun normalizeCodecConfig(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        if (hasAnnexBStartCode(data)) return data
        return convertLengthPrefixedNalToAnnexB(data)
            ?: convertAvcConfigRecordToAnnexB(data)
    }

    private fun merge(first: ByteArray, second: ByteArray): ByteArray {
        val merged = ByteArray(first.size + second.size)
        System.arraycopy(first, 0, merged, 0, first.size)
        System.arraycopy(second, 0, merged, first.size, second.size)
        return merged
    }

    private fun hasAnnexBStartCode(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val scanLimit = min(data.size - 3, 31)
        for (i in 0..scanLimit) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                (data[i + 2] == 1.toByte() || (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))
            ) {
                return true
            }
        }
        return false
    }

    private fun convertLengthPrefixedNalToAnnexB(data: ByteArray): ByteArray? {
        if (data.size < 4) return null
        val out = ByteArrayOutputStream(data.size + 64)
        var offset = 0
        while (offset + 4 <= data.size) {
            val nalSize = readUint32(data, offset)
            offset += 4
            if (nalSize == 0) {
                continue
            }
            if (nalSize < 0 || offset + nalSize > data.size) {
                return null
            }
            out.write(0)
            out.write(0)
            out.write(0)
            out.write(1)
            out.write(data, offset, nalSize)
            offset += nalSize
        }
        if (offset != data.size || out.size() == 0) return null
        return out.toByteArray()
    }

    private fun convertAvcConfigRecordToAnnexB(data: ByteArray): ByteArray? {
        if (data.size < 7 || data[0] != 0x01.toByte()) return null
        var offset = 5
        val spsCount = data[offset].toInt() and 0x1F
        offset += 1
        val out = ByteArrayOutputStream(data.size + 32)

        repeat(spsCount) {
            if (!appendAnnexBNalFromLengthField(data, out, offset)) return null
            val nalSize = readUint16(data, offset)
            offset += 2 + nalSize
        }

        if (offset >= data.size) return null
        val ppsCount = data[offset].toInt() and 0xFF
        offset += 1
        repeat(ppsCount) {
            if (!appendAnnexBNalFromLengthField(data, out, offset)) return null
            val nalSize = readUint16(data, offset)
            offset += 2 + nalSize
        }

        return if (out.size() > 0) out.toByteArray() else null
    }

    private fun appendAnnexBNalFromLengthField(
        data: ByteArray,
        out: ByteArrayOutputStream,
        offset: Int,
    ): Boolean {
        if (offset + 2 > data.size) return false
        val nalSize = readUint16(data, offset)
        val nalStart = offset + 2
        if (nalSize <= 0 || nalStart + nalSize > data.size) return false
        out.write(0)
        out.write(0)
        out.write(0)
        out.write(1)
        out.write(data, nalStart, nalSize)
        return true
    }

    private fun readUint16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
    }

    private fun readUint32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }
}
