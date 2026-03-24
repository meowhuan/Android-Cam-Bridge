package com.acb.androidcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import kotlin.math.abs

class CameraSurfacePipeline(
    private val context: Context,
    private val onError: ((String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "CameraSurfacePipeline"

        data class CaptureProfile(
            val selectedProfile: SupportedCaptureProfile,
            val captureSize: Size,
            val actualFpsRange: Range<Int>,
            val mode: CaptureModePreset,
            val torchEnabled: Boolean,
        ) {
            val cameraId: String
                get() = selectedProfile.cameraId

            val actualLabel: String
                get() = "${captureSize.width}x${captureSize.height}@${actualFpsRange.upper} (${mode.name.lowercase()})"
        }

        fun enumerateSupportedProfiles(context: Context): List<SupportedCaptureProfile> {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = findBestBackCameraId(manager) ?: return emptyList()
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
                val flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val recorderSizes = map.getOutputSizes(MediaRecorder::class.java)
                    ?: map.getOutputSizes(SurfaceTexture::class.java)
                    ?: return emptyList()
                val exactSizes = listOf(
                    Size(640, 480),
                    Size(1280, 720),
                    Size(1920, 1080),
                )
                val supportedFps = chooseSupportedFpsTargets(characteristics)
                val profiles = mutableListOf<SupportedCaptureProfile>()
                for (requested in exactSizes) {
                    val matchedSize = chooseBestSize(recorderSizes, requested.width, requested.height)
                    if (matchedSize.width != requested.width || matchedSize.height != requested.height) {
                        continue
                    }
                    for (fps in supportedFps) {
                        val id = "${cameraId}_${matchedSize.width}x${matchedSize.height}_${fps}"
                        profiles += SupportedCaptureProfile(
                            id = id,
                            cameraId = cameraId,
                            width = matchedSize.width,
                            height = matchedSize.height,
                            targetFps = fps,
                            recommendedBitrate = recommendedBitrate(matchedSize.width, matchedSize.height, fps),
                            supportsTorch = flashAvailable,
                        )
                    }
                }
                if (profiles.isEmpty()) {
                    val fallback = chooseBestSize(recorderSizes, 1280, 720)
                    profiles += SupportedCaptureProfile(
                        id = "${cameraId}_${fallback.width}x${fallback.height}_30",
                        cameraId = cameraId,
                        width = fallback.width,
                        height = fallback.height,
                        targetFps = minOf(30, supportedFps.maxOrNull() ?: 30),
                        recommendedBitrate = recommendedBitrate(fallback.width, fallback.height, minOf(30, supportedFps.maxOrNull() ?: 30)),
                        supportsTorch = flashAvailable,
                    )
                }
                profiles
            } catch (t: Throwable) {
                AppLog.w(TAG, "enumerateSupportedProfiles failed: ${t.message}", t)
                emptyList()
            }
        }

        fun chooseProfileForRequest(
            context: Context,
            width: Int,
            height: Int,
            fps: Int,
            preferredCameraId: String? = null,
        ): SupportedCaptureProfile? {
            val profiles = enumerateSupportedProfiles(context)
            if (profiles.isEmpty()) return null
            val scoped = if (!preferredCameraId.isNullOrBlank()) {
                profiles.filter { it.cameraId == preferredCameraId }.ifEmpty { profiles }
            } else {
                profiles
            }
            return scoped.minWithOrNull(
                compareBy<SupportedCaptureProfile> {
                    abs(it.width - width) + abs(it.height - height) + abs(it.targetFps - fps)
                }.thenBy { if (it.width == width && it.height == height) 0 else 1 }
                    .thenBy { if (it.targetFps == fps) 0 else 1 }
            ) ?: scoped.first()
        }

        fun prepareCaptureProfile(context: Context, spec: CaptureSpec): CaptureProfile {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(spec.profile.cameraId)
            val actualRange = chooseCaptureRange(characteristics, spec.profile.targetFps, spec.mode)
            return CaptureProfile(
                selectedProfile = spec.profile,
                captureSize = Size(spec.profile.width, spec.profile.height),
                actualFpsRange = actualRange,
                mode = spec.mode,
                torchEnabled = spec.torchEnabled && spec.profile.supportsTorch,
            )
        }

        private fun chooseSupportedFpsTargets(characteristics: CameraCharacteristics): List<Int> {
            val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            val out = mutableListOf<Int>()
            if (ranges.any { it.upper >= 30 }) out += 30
            if (ranges.any { it.upper >= 60 }) out += 60
            return if (out.isEmpty()) listOf(30) else out
        }

        private fun chooseCaptureRange(
            characteristics: CameraCharacteristics,
            targetFps: Int,
            mode: CaptureModePreset,
        ): Range<Int> {
            val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            if (ranges.isEmpty()) return Range(15, maxOf(15, targetFps))
            return when (mode) {
                CaptureModePreset.LATENCY -> ranges
                    .filter { it.upper >= targetFps }
                    .minWithOrNull(compareBy<Range<Int>> { abs(it.upper - targetFps) }.thenBy { abs(it.upper - it.lower) })
                    ?: ranges.maxByOrNull { it.upper }!!
                CaptureModePreset.BALANCED -> ranges
                    .filter { it.upper >= targetFps }
                    .minWithOrNull(compareBy<Range<Int>> { abs(it.upper - targetFps) }.thenByDescending { it.lower })
                    ?: ranges.maxByOrNull { it.upper }!!
                CaptureModePreset.LOW_LIGHT -> {
                    val capped = minOf(targetFps, 30)
                    ranges
                        .filter { it.upper >= capped }
                        .minWithOrNull(compareBy<Range<Int>> { abs(it.upper - capped) }.thenBy { it.lower })
                        ?: ranges.minByOrNull { it.lower }!!
                }
            }
        }

        private fun findBestBackCameraId(manager: CameraManager): String? {
            data class Candidate(
                val id: String,
                val maxArea: Long,
                val hasContinuousAf: Boolean,
                val focalLength: Float,
            )

            val candidates = manager.cameraIdList.mapNotNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) {
                    return@mapNotNull null
                }
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@mapNotNull null
                val sizes = map.getOutputSizes(MediaRecorder::class.java)
                    ?: map.getOutputSizes(SurfaceTexture::class.java)
                    ?: return@mapNotNull null
                val maxArea = sizes.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
                val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val hasContinuousAf = afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                Candidate(id, maxArea, hasContinuousAf, focalLength)
            }

            return candidates.maxWithOrNull(
                compareBy<Candidate> { it.maxArea }
                    .thenBy { if (it.hasContinuousAf) 1 else 0 }
                    .thenByDescending { it.focalLength }
            )?.id
        }

        private fun chooseBestSize(candidates: Array<Size>, width: Int, height: Int): Size {
            val requestedLong = maxOf(width, height)
            val requestedShort = minOf(width, height)
            return candidates.minWithOrNull(
                compareBy<Size> { abs(maxOf(it.width, it.height) - requestedLong) + abs(minOf(it.width, it.height) - requestedShort) }
                    .thenBy { abs((it.width * it.height) - (width * height)) }
            ) ?: candidates.first()
        }

        private fun recommendedBitrate(width: Int, height: Int, fps: Int): Int = when {
            width >= 1920 -> if (fps >= 60) 12_000_000 else 8_000_000
            width >= 1280 -> if (fps >= 60) 7_000_000 else 4_500_000
            else -> if (fps >= 60) 3_500_000 else 2_000_000
        }
    }

    private data class Config(
        val profile: CaptureProfile,
        val encoderSurface: Surface,
        val previewView: TextureView?,
    )

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var surfaceListenerInstalled = false
    private var pendingOpen = false
    private var running = false
    private var activeCameraId: String? = null
    private var activeConfig: Config? = null

    fun start(profile: CaptureProfile, encoderSurface: Surface, previewView: TextureView?) {
        stop()
        running = true
        activeConfig = Config(profile = profile, encoderSurface = encoderSurface, previewView = previewView)
        ensureCameraThread()
        ensurePreviewListener(previewView)
        openCamera("start")
    }

    fun stop() {
        running = false
        pendingOpen = false
        activeConfig = null

        val handler = cameraHandler
        if (handler != null) {
            handler.post {
                closeSessionLocked()
                closeDeviceLocked()
                releasePreviewSurfaceLocked()
            }
        }

        cameraThread?.quitSafely()
        try {
            cameraThread?.join(500)
        } catch (_: InterruptedException) {
        }
        cameraThread = null
        cameraHandler = null
        activeCameraId = null
    }

    private fun ensureCameraThread() {
        if (cameraThread != null && cameraHandler != null) return
        val thread = HandlerThread("AcbCamera2")
        thread.start()
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun ensurePreviewListener(previewView: TextureView?) {
        val textureView = previewView ?: return
        if (surfaceListenerInstalled) return
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                AppLog.i(TAG, "stream preview available ${width}x${height}")
                updatePreviewTransform()
                val handler = cameraHandler
                val device = cameraDevice
                if (handler != null && device != null && running) {
                    handler.post { createCaptureSession(device) }
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updatePreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                cameraHandler?.post { releasePreviewSurfaceLocked() }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }
        surfaceListenerInstalled = true
    }

    private fun openCamera(reason: String) {
        val config = activeConfig ?: return
        if (!running) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w(TAG, "CAMERA permission missing reason=$reason")
            return
        }

        val handler = cameraHandler ?: return
        handler.post {
            if (!running || pendingOpen || cameraDevice != null) return@post
            try {
                pendingOpen = true
                activeCameraId = config.profile.cameraId
                AppLog.i(
                    TAG,
                    "opening camera id=${config.profile.cameraId} reason=$reason actual=${config.profile.actualLabel} torch=${config.profile.torchEnabled}",
                )
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                manager.openCamera(config.profile.cameraId, buildStateCallback(config.profile.cameraId), handler)
            } catch (t: Throwable) {
                pendingOpen = false
                AppLog.e(TAG, "openCamera failed: ${t.message}", t)
                onError?.invoke("openCamera failed: ${t.message}")
            }
        }
    }

    private fun buildStateCallback(cameraId: String): CameraDevice.StateCallback {
        return object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                pendingOpen = false
                if (!running) {
                    device.close()
                    return
                }
                cameraDevice = device
                AppLog.i(TAG, "camera opened id=$cameraId")
                createCaptureSession(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                pendingOpen = false
                AppLog.w(TAG, "camera disconnected id=$cameraId")
                onError?.invoke("camera disconnected")
                closeSessionLocked()
                closeDeviceLocked()
            }

            override fun onError(device: CameraDevice, error: Int) {
                pendingOpen = false
                AppLog.e(TAG, "camera error id=$cameraId error=$error")
                onError?.invoke("camera error=$error")
                closeSessionLocked()
                closeDeviceLocked()
            }
        }
    }

    private fun createCaptureSession(device: CameraDevice) {
        val config = activeConfig ?: return
        val handler = cameraHandler ?: return
        try {
            closeSessionLocked()
            releasePreviewSurfaceLocked()
            val surfaces = mutableListOf<Surface>()
            val preview = buildPreviewSurface(config.profile.captureSize, config.previewView)
            if (preview != null) {
                previewSurface = preview
                surfaces += preview
            }
            surfaces += config.encoderSurface

            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!running) {
                        session.close()
                        return
                    }
                    captureSession = session
                    startRepeating(session, device, config)
                    updatePreviewTransform()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    AppLog.e(TAG, "capture session configure failed")
                    session.close()
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputs = surfaces.map { OutputConfiguration(it) }
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    context.mainExecutor,
                    callback,
                )
                device.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(
                    surfaces,
                    callback,
                    handler,
                )
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "createCaptureSession failed: ${t.message}", t)
            onError?.invoke("capture session failed: ${t.message}")
        }
    }

    private fun startRepeating(session: CameraCaptureSession, device: CameraDevice, config: Config) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(config.profile.cameraId)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(config.encoderSurface)
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.actualFpsRange)
                when (config.profile.mode) {
                    CaptureModePreset.LATENCY -> {
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    }
                    CaptureModePreset.BALANCED -> {
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                    }
                    CaptureModePreset.LOW_LIGHT -> {
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                        set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    }
                }
                if (config.profile.torchEnabled && characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                if (characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
            }.build()
            session.setRepeatingRequest(request, null, cameraHandler)
            AppLog.i(
                TAG,
                "capture repeating started actual=${config.profile.actualLabel} torch=${config.profile.torchEnabled}",
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "startRepeating failed: ${t.message}", t)
            onError?.invoke("capture repeat failed: ${t.message}")
        }
    }

    private fun buildPreviewSurface(size: Size, previewView: TextureView?): Surface? {
        val textureView = previewView ?: return null
        val texture = textureView.surfaceTexture ?: return null
        texture.setDefaultBufferSize(size.width, size.height)
        return Surface(texture)
    }

    private fun updatePreviewTransform() {
        val config = activeConfig ?: return
        val textureView = config.previewView ?: return
        val cameraId = activeCameraId ?: return
        if (textureView.width == 0 || textureView.height == 0) return

        textureView.post {
            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val displayRotation = textureView.display?.rotation ?: Surface.ROTATION_0
                val relativeRotation = computeRelativeRotation(sensorOrientation, displayRotation)

                val matrix = Matrix()
                val viewRect = RectF(0f, 0f, textureView.width.toFloat(), textureView.height.toFloat())
                val bufferRect = RectF(0f, 0f, config.profile.captureSize.width.toFloat(), config.profile.captureSize.height.toFloat())

                matrix.postTranslate(-bufferRect.centerX(), -bufferRect.centerY())
                matrix.postRotate(relativeRotation.toFloat())

                val rotatedRect = RectF(bufferRect)
                matrix.mapRect(rotatedRect)
                val scale = maxOf(
                    viewRect.width() / rotatedRect.width(),
                    viewRect.height() / rotatedRect.height(),
                )
                matrix.postScale(scale, scale)
                matrix.postTranslate(viewRect.centerX(), viewRect.centerY())
                textureView.setTransform(matrix)
                AppLog.d(
                    TAG,
                    "stream preview transform rotation=$relativeRotation view=${textureView.width}x${textureView.height} buffer=${config.profile.captureSize.width}x${config.profile.captureSize.height}",
                )
            } catch (t: Throwable) {
                AppLog.w(TAG, "updatePreviewTransform failed: ${t.message}", t)
            }
        }
    }

    private fun computeRelativeRotation(sensorOrientation: Int, displayRotation: Int): Int {
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation - displayDegrees + 360) % 360
    }

    private fun closeSessionLocked() {
        try {
            captureSession?.stopRepeating()
        } catch (_: Throwable) {
        }
        try {
            captureSession?.abortCaptures()
        } catch (_: Throwable) {
        }
        try {
            captureSession?.close()
        } catch (_: Throwable) {
        }
        captureSession = null
    }

    private fun closeDeviceLocked() {
        try {
            cameraDevice?.close()
        } catch (_: Throwable) {
        }
        cameraDevice = null
    }

    private fun releasePreviewSurfaceLocked() {
        try {
            previewSurface?.release()
        } catch (_: Throwable) {
        }
        previewSurface = null
    }
}
