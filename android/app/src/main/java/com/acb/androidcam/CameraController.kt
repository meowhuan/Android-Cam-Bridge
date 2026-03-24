package com.acb.androidcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.view.TextureView
import androidx.core.app.ActivityCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class CameraController(
    private val context: Context,
    private val onDebugEvent: ((String) -> Unit)? = null,
    private val onStreamStateChanged: ((StreamUiState, String) -> Unit)? = null,
) {
    enum class TransportMode { LAN, USB_ADB, USB_NATIVE, USB_AOA }

    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val micRunning = AtomicBoolean(false)
    private val sessionLoopRunning = AtomicBoolean(false)
    private val cameraPipeline = CameraSurfacePipeline(context) { detail ->
        AppLog.w("ACB", detail)
        onDebugEvent?.invoke(detail)
        emitStreamState(StreamUiState.ERROR, detail)
    }

    private var currentMode: TransportMode = TransportMode.USB_ADB
    private var currentReceiver = "127.0.0.1:39393"
    private var targetWidth = 1280
    private var targetHeight = 720
    private val v2Client = V2MediaClient(
        onDebugEvent = { onDebugEvent?.invoke(it) },
        onStreamStateChanged = { state, detail -> emitStreamState(state, detail) },
    )
    @Volatile private var videoEncoder: VideoAvcEncoder? = null
    @Volatile private var audioEncoder: AudioAacEncoder? = null
    private var lastStartAtMs = 0L

    fun start(
        transport: TransportMode,
        receiverAddress: String,
        captureSpec: CaptureSpec,
        streamPreviewView: TextureView?,
        pushMic: Boolean,
    ) {
        if (!running.compareAndSet(false, true)) return

        currentMode = transport
        val normalizedReceiver = receiverAddress.trim()
        currentReceiver = when {
            normalizedReceiver.isNotBlank() -> normalizedReceiver
            transport == TransportMode.USB_NATIVE -> ""
            transport == TransportMode.USB_AOA -> ""
            else -> "127.0.0.1:39393"
        }

        val captureProfile = CameraSurfacePipeline.prepareCaptureProfile(context, captureSpec)
        targetWidth = captureProfile.captureSize.width
        targetHeight = captureProfile.captureSize.height
        val effectiveBitrate = chooseEffectiveBitrate(
            targetWidth,
            targetHeight,
            captureProfile.actualFpsRange.upper,
            captureSpec.preferredBitrate,
        )

        val startLine =
            "start transport=$transport receiver=$currentReceiver request=${captureSpec.displayLabel} actual=${captureProfile.actualLabel} bitrate=$effectiveBitrate torch=${captureProfile.torchEnabled} mic=$pushMic"
        AppLog.i("ACB", startLine)
        onDebugEvent?.invoke(startLine)
        emitStreamState(StreamUiState.CONNECTING, "Preparing stream")
        lastStartAtMs = System.currentTimeMillis()

        try {
            if (videoEncoder == null) {
                videoEncoder = VideoAvcEncoder(targetWidth, targetHeight, effectiveBitrate, captureProfile.actualFpsRange.upper) { avc, isKey ->
                    v2Client.sendVideoFrame(avc, isKey)
                }
                AppLog.i("ACB", "video encoder ready ${targetWidth}x${targetHeight}@${captureProfile.actualFpsRange.upper} bitrate=$effectiveBitrate")
            }
            cameraPipeline.start(captureProfile, videoEncoder!!.inputSurface, streamPreviewView)
        } catch (t: Throwable) {
            val line = "video pipeline init failed: ${t.message}"
            AppLog.e("ACB", line, t)
            onDebugEvent?.invoke(line)
            emitStreamState(StreamUiState.ERROR, line)
        }

        val transportName = when (transport) {
            TransportMode.LAN -> "lan"
            TransportMode.USB_NATIVE -> "usb-native"
            TransportMode.USB_ADB -> "usb-adb"
            TransportMode.USB_AOA -> "usb-aoa"
        }
        startSessionLoop(
            transportName = transportName,
            captureSpec = captureSpec,
        )

        if (pushMic) {
            startMicStream()
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        micRunning.set(false)
        cameraPipeline.stop()

        videoEncoder?.stop()
        videoEncoder = null
        audioEncoder?.stop()
        audioEncoder = null
        v2Client.close()

        emitStreamState(StreamUiState.STOPPED, "Stream stopped")
        AppLog.i("ACB", "stop stream")
    }

    fun isRunning(): Boolean = running.get()

    fun isHealthy(): Boolean {
        if (!running.get()) return false
        val now = System.currentTimeMillis()
        if (now - lastStartAtMs < 6000) {
            return true
        }
        return v2Client.isConnected()
    }

    fun attachUsbAccessoryTransport(transport: UsbAccessoryTransport?) {
        v2Client.setUsbAccessoryTransport(transport)
    }

    private data class AudioFxBundle(
        val ns: NoiseSuppressor?,
        val agc: AutomaticGainControl?,
        val aec: AcousticEchoCanceler?,
    ) {
        fun releaseAll() {
            try {
                ns?.release()
            } catch (_: Throwable) {
            }
            try {
                agc?.release()
            } catch (_: Throwable) {
            }
            try {
                aec?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun tryEnableAudioFx(sessionId: Int): AudioFxBundle {
        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        var aec: AcousticEchoCanceler? = null

        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
            }
        } catch (t: Throwable) {
            AppLog.w("ACB", "audio fx setup failed: ${t.message}", t)
        }

        AppLog.i(
            "ACB",
            "audio fx ns=${ns?.enabled == true} agc=${agc?.enabled == true} aec=${aec?.enabled == true}",
        )
        return AudioFxBundle(ns, agc, aec)
    }

    private fun startMicStream() {
        if (micRunning.get()) return
        micRunning.set(true)

        audioExecutor.execute {
            val sampleRate = 48_000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            if (minBuffer <= 0) {
                AppLog.w("ACB", "AudioRecord min buffer invalid")
                micRunning.set(false)
                return@execute
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("ACB", "RECORD_AUDIO permission not granted, skip mic stream")
                micRunning.set(false)
                return@execute
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                encoding,
                minBuffer * 2,
            )

            var audioFx: AudioFxBundle? = null
            try {
                audioFx = tryEnableAudioFx(recorder.audioSessionId)
                recorder.startRecording()
                val buffer = ByteArray(minBuffer)

                while (running.get() && micRunning.get()) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        audioEncoder?.encodePcm(buffer, read, sampleRate, 1) { aac ->
                            v2Client.sendAudioFrame(aac, aac.size)
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLog.w("ACB", "mic stream failed: ${t.message}", t)
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                recorder.release()
                audioFx?.releaseAll()
                micRunning.set(false)
            }
        }
    }

    private fun startSessionLoop(
        transportName: String,
        captureSpec: CaptureSpec,
    ) {
        if (!sessionLoopRunning.compareAndSet(false, true)) return
        sessionExecutor.execute {
            var attempt = 0
            while (running.get()) {
                if (v2Client.isConnected()) {
                    sleepQuietly(1000)
                    continue
                }

                val v2Session = v2Client.startSession(currentReceiver, transportName)
                if (!running.get()) break

                if (v2Session != null) {
                    try {
                        if (audioEncoder == null) {
                            audioEncoder = AudioAacEncoder(sampleRate = 48_000, channels = 1, bitrate = 96_000)
                            AppLog.i("ACB", "audio encoder ready sampleRate=48000 channels=1 bitrate=96000")
                        }
                    } catch (t: Throwable) {
                        val line = "encoder init failed: ${t.message}"
                        AppLog.e("ACB", line, t)
                        onDebugEvent?.invoke(line)
                        emitStreamState(StreamUiState.ERROR, line)
                        attempt += 1
                        sleepQuietly(1000)
                        continue
                    }
                    emitStreamState(StreamUiState.CONNECTING, "Connecting ${captureSpec.displayLabel}")
                    v2Client.connect(v2Session.wsUrl)
                    val line = "v2 session connect attempt ok transport=$transportName receiver=$currentReceiver"
                    AppLog.i("ACB", line)
                    onDebugEvent?.invoke(line)
                    attempt = 0
                    sleepQuietly(1200)
                } else {
                    attempt += 1
                    val backoffMs = min(3000, 500 + attempt * 250)
                    val line = "v2 session retry #$attempt transport=$transportName receiver=$currentReceiver"
                    if (attempt == 1 || attempt % 6 == 0) {
                        AppLog.w("ACB", line)
                        onDebugEvent?.invoke(line)
                    }
                    emitStreamState(StreamUiState.CONNECTING, "Waiting for receiver ($attempt)")
                    sleepQuietly(backoffMs.toLong())
                }
            }
            sessionLoopRunning.set(false)
        }
    }

    private fun emitStreamState(state: StreamUiState, detail: String) {
        onStreamStateChanged?.invoke(state, detail)
    }

    private fun chooseEffectiveBitrate(width: Int, height: Int, fps: Int, preferredBitrate: Int): Int {
        val recommended = when {
            width >= 1920 -> if (fps >= 60) 12_000_000 else 8_000_000
            width >= 1280 -> if (fps >= 60) 7_000_000 else 4_500_000
            else -> if (fps >= 60) 3_500_000 else 2_000_000
        }
        return when {
            preferredBitrate <= 0 -> recommended
            preferredBitrate > recommended -> recommended
            else -> preferredBitrate
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: Throwable) {
        }
    }
}
