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

class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
) {
    enum class TransportMode { LAN, USB_ADB, USB_NATIVE }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val audioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val micRunning = AtomicBoolean(false)

    private var currentMode: TransportMode = TransportMode.USB_ADB
    private var currentReceiver = "127.0.0.1:39393"
    private var targetWidth = 1280
    private var targetHeight = 720
    private val v2Client = V2MediaClient()
    private var videoEncoder: VideoAvcEncoder? = null
    private var audioEncoder: AudioAacEncoder? = null

    fun start(
        transport: TransportMode,
        receiverAddress: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        pushMic: Boolean,
    ) {
        if (running.get()) return
        running.set(true)

        currentMode = transport
        currentReceiver = receiverAddress.ifBlank { "127.0.0.1:39393" }
        targetWidth = width
        targetHeight = height

        Log.i("ACB", "start transport=$transport receiver=$currentReceiver ${width}x$height@$fps bitrate=$bitrate mic=$pushMic")

        val transportName = if (transport == TransportMode.LAN) "lan" else "usb-adb"
        val v2Session = v2Client.startSession(currentReceiver, transportName)
        if (v2Session != null) {
            v2Client.connect(v2Session.wsUrl)
            videoEncoder = VideoAvcEncoder(width, height, bitrate, fps)
            audioEncoder = AudioAacEncoder(sampleRate = 48_000, channels = 1, bitrate = 96_000)
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            @Suppress("DEPRECATION")
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(width, height))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val rotation = previewView.display?.rotation ?: android.view.Surface.ROTATION_0
            preview.targetRotation = rotation
            analysis.targetRotation = rotation

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (!running.get()) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                try {
                    val cropRect = normalizedCropRect(imageProxy.cropRect, imageProxy.width, imageProxy.height)
                    val jpeg = imageProxyToJpeg(imageProxy, cropRect)
                    val w = cropRect.width()
                    val h = cropRect.height()
                    uploadExecutor.execute {
                        postFrame(jpeg, w, h)
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
            val viewPort = ViewPort.Builder(android.util.Rational(width, height), rotation)
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .addUseCase(analysis)
                .build()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        }, ContextCompat.getMainExecutor(context))

        if (pushMic) {
            startMicStream()
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        micRunning.set(false)
        videoEncoder?.stop()
        videoEncoder = null
        audioEncoder?.stop()
        audioEncoder = null
        v2Client.close()

        try {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
        } catch (_: Throwable) {
        }
        Log.i("ACB", "stop stream")
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
                        postAudio(buffer, read, sampleRate, 1)
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
}
