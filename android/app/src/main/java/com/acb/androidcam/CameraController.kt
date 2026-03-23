package com.acb.androidcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView?,
    private val onDebugEvent: ((String) -> Unit)? = null,
) {
    enum class TransportMode { LAN, USB_ADB, USB_NATIVE, USB_AOA }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val sessionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val micRunning = AtomicBoolean(false)
    private val sessionLoopRunning = AtomicBoolean(false)

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
        targetWidth = width
        targetHeight = height

        val startLine = "start transport=$transport receiver=$currentReceiver ${width}x$height@$fps bitrate=$bitrate mic=$pushMic"
        Log.i("ACB", startLine)
        onDebugEvent?.invoke(startLine)
        lastStartAtMs = System.currentTimeMillis()

        val transportName = when (transport) {
            TransportMode.LAN -> "lan"
            TransportMode.USB_NATIVE -> "usb-native"
            TransportMode.USB_ADB -> "usb-adb"
            TransportMode.USB_AOA -> "usb-aoa"
        }
        startSessionLoop(
            transportName = transportName,
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
        )

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()

            @Suppress("DEPRECATION")
            val analysisBuilder = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(width, height))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            @Suppress("UnsafeOptInUsageError")
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(fps, fps)
                )

            val analysis = analysisBuilder.build()

            val rotation = previewView?.display?.rotation ?: android.view.Surface.ROTATION_0
            analysis.targetRotation = rotation

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!running.get()) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                try {
                    if (shouldPushLegacyHttp()) {
                        val cropRect = normalizedCropRect(imageProxy.cropRect, imageProxy.width, imageProxy.height)
                        val jpeg = imageProxyToJpeg(imageProxy, cropRect)
                        val w = cropRect.width()
                        val h = cropRect.height()
                        uploadExecutor.execute {
                            postFrame(jpeg, w, h)
                        }
                    }
                    videoEncoder?.encode(imageProxy) { avc, isKey ->
                        v2Client.sendVideoFrame(avc, isKey)
                    }
                } catch (t: Throwable) {
                    Log.e("ACB", "frame encode failed", t)
                } finally {
                    imageProxy.close()
                }
            }

            provider.unbindAll()
            if (previewView != null) {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                    it.targetRotation = rotation
                }
                val viewPort = ViewPort.Builder(android.util.Rational(width, height), rotation)
                    .setScaleType(ViewPort.FILL_CENTER)
                    .build()
                val group = UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .build()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
            } else {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            }
        }, ContextCompat.getMainExecutor(context))

        if (pushMic) {
            startMicStream()
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        micRunning.set(false)

        // Stop camera FIRST so no more frames arrive at the encoder
        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        } catch (_: Throwable) {
        }
        // Brief wait for in-flight analyzer callbacks to finish
        try { Thread.sleep(100) } catch (_: Throwable) {}

        videoEncoder?.stop()
        videoEncoder = null
        audioEncoder?.stop()
        audioEncoder = null
        v2Client.close()

        Log.i("ACB", "stop stream")
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
            Log.w("ACB", "audio fx setup failed: ${t.message}")
        }

        Log.i(
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
                Log.w("ACB", "AudioRecord min buffer invalid")
                micRunning.set(false)
                return@execute
            }

            if (
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("ACB", "RECORD_AUDIO permission not granted, skip mic stream")
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
                Log.w("ACB", "mic stream failed: ${t.message}")
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

    private fun imageProxyToJpeg(image: androidx.camera.core.ImageProxy, cropRect: Rect): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yRowStride = image.planes[0].rowStride
        var yPos = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            yBuffer.position(rowStart)
            yBuffer.get(nv21, yPos, width)
            yPos += width
        }

        val chromaRowStride = image.planes[1].rowStride
        val chromaPixelStride = image.planes[1].pixelStride
        var offset = ySize
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vuPos = row * chromaRowStride + col * chromaPixelStride
                nv21[offset++] = vBuffer.get(vuPos.coerceAtMost(vBuffer.limit() - 1))
                nv21[offset++] = uBuffer.get(vuPos.coerceAtMost(uBuffer.limit() - 1))
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(cropRect, 90, stream)
        return stream.toByteArray()
    }

    private fun normalizedCropRect(src: Rect?, srcW: Int, srcH: Int): Rect {
        val full = Rect(0, 0, srcW, srcH)
        if (src == null || src.isEmpty) return full

        var left = src.left.coerceIn(0, srcW - 1)
        var top = src.top.coerceIn(0, srcH - 1)
        var right = src.right.coerceIn(left + 1, srcW)
        var bottom = src.bottom.coerceIn(top + 1, srcH)

        // YUV420 requires even alignment.
        left = left and 0xFFFFFFFE.toInt()
        top = top and 0xFFFFFFFE.toInt()
        right = right and 0xFFFFFFFE.toInt()
        bottom = bottom and 0xFFFFFFFE.toInt()

        if (right <= left + 1 || bottom <= top + 1) return full
        return Rect(left, top, right, bottom)
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
                Log.w("ACB", "frame upload http=$code endpoint=$endpoint")
            }
        } catch (t: Throwable) {
            Log.w("ACB", "frame upload failed: ${t.message}")
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

    private fun shouldPushLegacyHttp(): Boolean {
        // Keep legacy path only as bootstrap fallback.
        // Once v2 is up, disable v1 to avoid dual-path contention and latency spikes.
        return currentMode != TransportMode.USB_NATIVE && currentMode != TransportMode.USB_AOA && !v2Client.isConnected()
    }

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
                    if (videoEncoder == null) {
                        videoEncoder = VideoAvcEncoder(width, height, bitrate, fps)
                    }
                    if (audioEncoder == null) {
                        audioEncoder = AudioAacEncoder(sampleRate = 48_000, channels = 1, bitrate = 96_000)
                    }
                    v2Client.connect(v2Session.wsUrl)
                    val line = "v2 session connect attempt ok transport=$transportName receiver=$currentReceiver"
                    Log.i("ACB", line)
                    onDebugEvent?.invoke(line)
                    attempt = 0
                    // Give websocket/usb-native handshake a short window to settle.
                    sleepQuietly(1200)
                } else {
                    attempt += 1
                    val backoffMs = min(3000, 500 + attempt * 250)
                    if (attempt == 1 || attempt % 6 == 0) {
                        val line = "v2 session retry #$attempt transport=$transportName receiver=$currentReceiver"
                        Log.w("ACB", line)
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
