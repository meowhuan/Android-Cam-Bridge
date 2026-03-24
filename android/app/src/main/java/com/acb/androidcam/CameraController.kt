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
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import android.view.TextureView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: TextureView?,
    private val onDebugEvent: ((String) -> Unit)? = null,
) {
    enum class TransportMode { LAN, USB_ADB, USB_NATIVE, USB_AOA }

    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val micRunning = AtomicBoolean(false)
    private val sessionLoopRunning = AtomicBoolean(false)
    private val cameraPipeline = CameraSurfacePipeline(context, previewView)

    private var currentMode: TransportMode = TransportMode.USB_ADB
    private var currentReceiver = "127.0.0.1:39393"
    private var targetWidth = 1280
    private var targetHeight = 720
    private val v2Client = V2MediaClient { onDebugEvent?.invoke(it) }
    @Volatile private var videoEncoder: VideoAvcEncoder? = null
    @Volatile private var audioEncoder: AudioAacEncoder? = null
    private var lastStartAtMs = 0L

    fun start(
        transport: TransportMode,
        receiverAddress: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
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
        val captureSize = CameraSurfacePipeline.resolveCaptureSize(context, width, height)
        targetWidth = captureSize.width
        targetHeight = captureSize.height

        val startLine = "start transport=$transport receiver=$currentReceiver ${targetWidth}x${targetHeight}@$fps bitrate=$bitrate mic=$pushMic"
        AppLog.i("ACB", startLine)
        onDebugEvent?.invoke(startLine)
        lastStartAtMs = System.currentTimeMillis()

        try {
            if (videoEncoder == null) {
                videoEncoder = VideoAvcEncoder(targetWidth, targetHeight, bitrate, fps) { avc, isKey ->
                    v2Client.sendVideoFrame(avc, isKey)
                }
                AppLog.i("ACB", "video encoder ready ${targetWidth}x${targetHeight}@$fps bitrate=$bitrate")
            }
            cameraPipeline.start(
                width = targetWidth,
                height = targetHeight,
                fps = fps,
                encoderSurface = videoEncoder!!.inputSurface,
            )
        } catch (t: Throwable) {
            val line = "video pipeline init failed: ${t.message}"
            AppLog.e("ACB", line, t)
            onDebugEvent?.invoke(line)
        }

        val transportName = when (transport) {
            TransportMode.LAN -> "lan"
            TransportMode.USB_NATIVE -> "usb-native"
            TransportMode.USB_ADB -> "usb-adb"
            TransportMode.USB_AOA -> "usb-aoa"
        }
        startSessionLoop(
            transportName = transportName,
            width = targetWidth,
            height = targetHeight,
            fps = fps,
            bitrate = bitrate,
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

        AppLog.i("ACB", "stop stream")
    }

    fun isRunning(): Boolean = running.get()

    fun isHealthy(): Boolean {
        if (!running.get()) return false
        val now = System.currentTimeMillis()
        if (now - lastStartAtMs < 6000) {
            // Warm-up window for encoder/websocket startup.
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

            if (
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
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
                        if (shouldPushLegacyHttp()) {
                            postAudio(buffer, read, sampleRate, 1)
                        }
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

    private fun postFrame(jpeg: ByteArray, width: Int, height: Int) {
        val endpoint = "http://$currentReceiver/api/v1/frame"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.setRequestProperty("X-Width", width.toString())
            conn.setRequestProperty("X-Height", height.toString())
            conn.setRequestProperty("X-Transport", currentMode.name)
            conn.outputStream.use { os: OutputStream ->
                os.write(jpeg)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                AppLog.w("ACB", "frame upload http=$code endpoint=$endpoint")
            }
        } catch (t: Throwable) {
            AppLog.w("ACB", "frame upload failed: ${t.message}", t)
        } finally {
            conn?.disconnect()
        }
    }

    private fun postAudio(pcm: ByteArray, length: Int, sampleRate: Int, channels: Int) {
        val endpoint = "http://$currentReceiver/api/v1/audio"
        var conn: HttpURLConnection? = null
        try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("X-Sample-Rate", sampleRate.toString())
            conn.setRequestProperty("X-Channels", channels.toString())
            conn.outputStream.use { os: OutputStream ->
                os.write(pcm, 0, length)
            }
            conn.responseCode
        } catch (_: Throwable) {
        } finally {
            conn?.disconnect()
        }
    }

    private fun shouldPushLegacyHttp(): Boolean = false

    private fun startSessionLoop(
        transportName: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
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
                        attempt += 1
                        sleepQuietly(1000)
                        continue
                    }
                    v2Client.connect(v2Session.wsUrl)
                    val line = "v2 session connect attempt ok transport=$transportName receiver=$currentReceiver"
                    AppLog.i("ACB", line)
                    onDebugEvent?.invoke(line)
                    attempt = 0
                    // Give websocket/usb-native handshake a short window to settle.
                    sleepQuietly(1200)
                } else {
                    attempt += 1
                    val backoffMs = min(3000, 500 + attempt * 250)
                    if (attempt == 1 || attempt % 6 == 0) {
                        val line = "v2 session retry #$attempt transport=$transportName receiver=$currentReceiver"
                        AppLog.w("ACB", line)
                        onDebugEvent?.invoke(line)
                    }
                    sleepQuietly(backoffMs.toLong())
                }
            }
            sessionLoopRunning.set(false)
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: Throwable) {
        }
    }
}
