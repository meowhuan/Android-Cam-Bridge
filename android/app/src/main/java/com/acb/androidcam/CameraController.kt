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
    companion object {
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 1
        private const val AUDIO_BITRATE = 96_000
        private const val VIDEO_KEYINT_SECONDS = 1
    }

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
            actualProfile = captureProfile,
            actualBitrate = effectiveBitrate,
            audioEnabled = pushMic,
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
            val sampleRate = AUDIO_SAMPLE_RATE
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
        actualProfile: CameraSurfacePipeline.CaptureProfile,
        actualBitrate: Int,
        audioEnabled: Boolean,
    ) {
        if (!sessionLoopRunning.compareAndSet(false, true)) return
        sessionExecutor.execute {
            var attempt = 0
            while (running.get()) {
                if (v2Client.isConnected()) {
                    sleepQuietly(1000)
                    continue
                }

                val v2Session = v2Client.startSession(
                    receiverAddress = currentReceiver,
                    transport = transportName,
                    videoWidth = actualProfile.captureSize.width,
                    videoHeight = actualProfile.captureSize.height,
                    videoFps = actualProfile.actualFpsRange.upper,
                    videoBitrate = actualBitrate,
                    videoKeyInt = maxOf(1, actualProfile.actualFpsRange.upper * VIDEO_KEYINT_SECONDS),
                    audioEnabled = audioEnabled,
                    audioSampleRate = AUDIO_SAMPLE_RATE,
                    audioChannels = AUDIO_CHANNELS,
                    audioBitrate = AUDIO_BITRATE,
                )
                if (!running.get()) break

                if (v2Session != null) {
                    try {
                        if (audioEnabled && audioEncoder == null) {
                            audioEncoder = AudioAacEncoder(
                                sampleRate = AUDIO_SAMPLE_RATE,
                                channels = AUDIO_CHANNELS,
                                bitrate = AUDIO_BITRATE,
                            )
                            AppLog.i(
                                "ACB",
                                "audio encoder ready sampleRate=$AUDIO_SAMPLE_RATE channels=$AUDIO_CHANNELS bitrate=$AUDIO_BITRATE",
                            )
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
                    emitStreamState(StreamUiState.CONNECTING, "Connecting ${actualProfile.actualLabel}")
                    v2Client.connect(v2Session.wsUrl)
                    val line =
                        "v2 session connect attempt ok transport=$transportName receiver=$currentReceiver actual=${actualProfile.actualLabel} bitrate=$actualBitrate audio=$audioEnabled"
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
            width >= 1920 -> if (fps >= 60) 18_000_000 else 12_000_000
            width >= 1280 -> if (fps >= 60) 12_000_000 else 7_000_000
            else -> if (fps >= 60) 5_000_000 else 3_000_000
        }
        return when {
            preferredBitrate <= 0 -> recommended
            else -> maxOf(preferredBitrate, recommended)
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: Throwable) {
        }
    }
}
