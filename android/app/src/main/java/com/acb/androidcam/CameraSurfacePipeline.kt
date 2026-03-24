package com.acb.androidcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
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
    data class CaptureTuning(
        val controlMode: Int,
        val aeMode: Int,
        val aeTargetFpsRange: Range<Int>,
        val noiseReductionMode: Int,
        val edgeMode: Int,
        val colorCorrectionAberrationMode: Int? = null,
        val hotPixelMode: Int? = null,
        val shadingMode: Int? = null,
        val tonemapMode: Int? = null,
        val sceneMode: Int? = null,
        val postRawSensitivityBoost: Int? = null,
    )

    data class CaptureProfile(
        val selectedProfile: SupportedCaptureProfile,
        val captureSize: Size,
        val actualFpsRange: Range<Int>,
        val mode: CaptureModePreset,
        val torchEnabled: Boolean,
        val sessionMode: CaptureDeliveryMode,
        val tuning: CaptureTuning,
    ) {
        val actualLabel: String
            get() = buildString {
                append("${captureSize.width}x${captureSize.height}@")
                if (actualFpsRange.lower == actualFpsRange.upper) {
                    append(actualFpsRange.upper)
                } else {
                    append(actualFpsRange.lower)
                    append('-')
                    append(actualFpsRange.upper)
                }
                if (sessionMode == CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED) {
                    append(" hs")
                }
                append(" (${mode.name.lowercase()})")
            }

        val cameraId: String
            get() = selectedProfile.cameraId
    }

    companion object {
        private const val TAG = "CameraSurfacePipeline"
        private const val NS_PER_SECOND = 1_000_000_000.0

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
                val profiles = mutableListOf<SupportedCaptureProfile>()
                for (requested in exactSizes) {
                    val matchedSize = chooseBestSize(recorderSizes, requested.width, requested.height)
                    if (matchedSize.width != requested.width || matchedSize.height != requested.height) {
                        continue
                    }
                    val supportsRegular30 = supportsRegularTargetFps(map, characteristics, matchedSize, 30)
                    val supportsRegular60 = supportsRegularTargetFps(map, characteristics, matchedSize, 60)
                    val supportsHighSpeed60 = supportsHighSpeedTargetFps(map, matchedSize, 60)
                    if (supportsRegular30) {
                        val id = "${cameraId}_${matchedSize.width}x${matchedSize.height}_30_std"
                        profiles += SupportedCaptureProfile(
                            id = id,
                            cameraId = cameraId,
                            width = matchedSize.width,
                            height = matchedSize.height,
                            targetFps = 30,
                            recommendedBitrate = recommendedBitrate(matchedSize.width, matchedSize.height, 30),
                            supportsTorch = flashAvailable,
                            deliveryMode = CaptureDeliveryMode.STANDARD,
                        )
                    }
                    if (supportsHighSpeed60 || supportsRegular60) {
                        val deliveryMode = if (supportsHighSpeed60) {
                            CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED
                        } else {
                            CaptureDeliveryMode.STANDARD
                        }
                        val id = "${cameraId}_${matchedSize.width}x${matchedSize.height}_60_${deliveryMode.name.lowercase()}"
                        profiles += SupportedCaptureProfile(
                            id = id,
                            cameraId = cameraId,
                            width = matchedSize.width,
                            height = matchedSize.height,
                            targetFps = 60,
                            recommendedBitrate = recommendedBitrate(matchedSize.width, matchedSize.height, 60),
                            supportsTorch = flashAvailable,
                            deliveryMode = deliveryMode,
                        )
                    }
                }
                if (profiles.isEmpty()) {
                    val fallback = chooseBestSize(recorderSizes, 1280, 720)
                    profiles += SupportedCaptureProfile(
                        id = "${cameraId}_${fallback.width}x${fallback.height}_30_std",
                        cameraId = cameraId,
                        width = fallback.width,
                        height = fallback.height,
                        targetFps = 30,
                        recommendedBitrate = recommendedBitrate(fallback.width, fallback.height, 30),
                        supportsTorch = flashAvailable,
                        deliveryMode = CaptureDeliveryMode.STANDARD,
                    )
                }
                profiles.sortedWith(
                    compareBy<SupportedCaptureProfile> { it.width * it.height }
                        .thenBy { it.targetFps }
                        .thenBy { it.deliveryMode.ordinal }
                )
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
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val captureSize = Size(spec.profile.width, spec.profile.height)
            val sessionMode = chooseSessionMode(spec.profile, spec.mode, map, captureSize)
            val actualRange = if (sessionMode == CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED && map != null) {
                chooseHighSpeedRange(map, captureSize, spec.profile.targetFps)
            } else {
                chooseCaptureRange(characteristics, spec.profile.targetFps, spec.mode)
            }
            val tuning = buildCaptureTuning(characteristics, spec.mode, actualRange, sessionMode)
            return CaptureProfile(
                selectedProfile = spec.profile,
                captureSize = captureSize,
                actualFpsRange = actualRange,
                mode = spec.mode,
                torchEnabled = spec.torchEnabled && spec.profile.supportsTorch && sessionMode != CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED,
                sessionMode = sessionMode,
                tuning = tuning,
            )
        }

        private fun supportsRegularTargetFps(
            map: StreamConfigurationMap,
            characteristics: CameraCharacteristics,
            size: Size,
            targetFps: Int,
        ): Boolean {
            val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            if (ranges.none { it.upper >= targetFps }) {
                return false
            }
            if (targetFps <= 30) {
                return true
            }
            val minDurationNs = outputMinFrameDurationNs(map, size) ?: return false
            if (minDurationNs <= 0L) {
                return false
            }
            val maxSupportedFps = NS_PER_SECOND / minDurationNs.toDouble()
            return maxSupportedFps + 0.5 >= targetFps
        }

        private fun outputMinFrameDurationNs(map: StreamConfigurationMap, size: Size): Long? {
            val recorderDuration = runCatching { map.getOutputMinFrameDuration(MediaRecorder::class.java, size) }.getOrNull()
            if (recorderDuration != null && recorderDuration > 0L) {
                return recorderDuration
            }
            val textureDuration = runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, size) }.getOrNull()
            return textureDuration?.takeIf { it > 0L }
        }

        private fun supportsHighSpeedTargetFps(
            map: StreamConfigurationMap,
            size: Size,
            targetFps: Int,
        ): Boolean {
            val supportsSize = runCatching { map.highSpeedVideoSizes.any { it == size } }.getOrDefault(false)
            if (!supportsSize) {
                return false
            }
            val ranges = runCatching { map.getHighSpeedVideoFpsRangesFor(size).toList() }.getOrDefault(emptyList())
            return ranges.any { targetFps in it.lower..it.upper }
        }

        private fun chooseSessionMode(
            profile: SupportedCaptureProfile,
            mode: CaptureModePreset,
            map: StreamConfigurationMap?,
            size: Size,
        ): CaptureDeliveryMode {
            if (mode == CaptureModePreset.LOW_LIGHT || map == null) {
                return CaptureDeliveryMode.STANDARD
            }
            return if (profile.deliveryMode == CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED &&
                supportsHighSpeedTargetFps(map, size, profile.targetFps)
            ) {
                CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED
            } else {
                CaptureDeliveryMode.STANDARD
            }
        }

        private fun chooseHighSpeedRange(
            map: StreamConfigurationMap,
            size: Size,
            targetFps: Int,
        ): Range<Int> {
            val candidates = runCatching { map.getHighSpeedVideoFpsRangesFor(size).toList() }.getOrDefault(emptyList())
                .filter { targetFps in it.lower..it.upper }
            return candidates.minWithOrNull(
                compareBy<Range<Int>> { if (it.lower == it.upper) 0 else 1 }
                    .thenBy { abs(it.upper - targetFps) }
                    .thenBy { abs(it.lower - targetFps) }
            ) ?: Range(targetFps, targetFps)
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
                    val preferred = ranges
                        .filter { it.upper <= capped }
                        .minWithOrNull(compareByDescending<Range<Int>> { it.upper }.thenBy { it.lower })
                    preferred
                        ?: ranges
                            .filter { it.upper >= capped }
                            .minWithOrNull(compareBy<Range<Int>> { abs(it.upper - capped) }.thenBy { it.lower })
                        ?: ranges.minByOrNull { it.lower }!!
                }
            }
        }

        private fun buildCaptureTuning(
            characteristics: CameraCharacteristics,
            mode: CaptureModePreset,
            actualFpsRange: Range<Int>,
            sessionMode: CaptureDeliveryMode,
        ): CaptureTuning {
            if (sessionMode == CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED) {
                return CaptureTuning(
                    controlMode = CaptureRequest.CONTROL_MODE_AUTO,
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                    aeTargetFpsRange = actualFpsRange,
                    noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                    edgeMode = CaptureRequest.EDGE_MODE_FAST,
                )
            }

            val nightSceneSupported = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                ?.contains(CaptureRequest.CONTROL_SCENE_MODE_NIGHT) == true
            val postRawBoost = characteristics.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)
                ?.upper
                ?.takeIf { it > 100 }

            return when (mode) {
                CaptureModePreset.LATENCY -> CaptureTuning(
                    controlMode = CaptureRequest.CONTROL_MODE_AUTO,
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                    aeTargetFpsRange = actualFpsRange,
                    noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                    edgeMode = CaptureRequest.EDGE_MODE_FAST,
                )
                CaptureModePreset.BALANCED -> CaptureTuning(
                    controlMode = CaptureRequest.CONTROL_MODE_AUTO,
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                    aeTargetFpsRange = actualFpsRange,
                    noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                    colorCorrectionAberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                )
                CaptureModePreset.LOW_LIGHT -> CaptureTuning(
                    controlMode = if (nightSceneSupported) {
                        CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                    } else {
                        CaptureRequest.CONTROL_MODE_AUTO
                    },
                    aeMode = CaptureRequest.CONTROL_AE_MODE_ON,
                    aeTargetFpsRange = actualFpsRange,
                    noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                    edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
                    colorCorrectionAberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
                    hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
                    shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
                    tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
                    sceneMode = if (nightSceneSupported) CaptureRequest.CONTROL_SCENE_MODE_NIGHT else null,
                    postRawSensitivityBoost = postRawBoost,
                )
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
            width >= 1920 -> if (fps >= 60) 18_000_000 else 12_000_000
            width >= 1280 -> if (fps >= 60) 12_000_000 else 7_000_000
            else -> if (fps >= 60) 5_000_000 else 3_000_000
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

            if (config.profile.sessionMode == CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED) {
                @Suppress("DEPRECATION")
                device.createConstrainedHighSpeedCaptureSession(
                    surfaces,
                    callback,
                    handler,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(config.encoderSurface)
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                set(CaptureRequest.CONTROL_MODE, config.profile.tuning.controlMode)
                set(CaptureRequest.CONTROL_AE_MODE, config.profile.tuning.aeMode)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.profile.tuning.aeTargetFpsRange)
                set(CaptureRequest.NOISE_REDUCTION_MODE, config.profile.tuning.noiseReductionMode)
                set(CaptureRequest.EDGE_MODE, config.profile.tuning.edgeMode)
                config.profile.tuning.sceneMode?.let { set(CaptureRequest.CONTROL_SCENE_MODE, it) }
                config.profile.tuning.colorCorrectionAberrationMode?.let {
                    set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it)
                }
                config.profile.tuning.hotPixelMode?.let { set(CaptureRequest.HOT_PIXEL_MODE, it) }
                config.profile.tuning.shadingMode?.let { set(CaptureRequest.SHADING_MODE, it) }
                config.profile.tuning.tonemapMode?.let { set(CaptureRequest.TONEMAP_MODE, it) }
                config.profile.tuning.postRawSensitivityBoost?.let {
                    set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it)
                }
                set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                if (config.profile.torchEnabled && characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                if (characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
            }
            val request = requestBuilder.build()
            if (config.profile.sessionMode == CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED) {
                val highSpeedSession = session as? CameraConstrainedHighSpeedCaptureSession
                    ?: error("high-speed session unavailable")
                val burst = highSpeedSession.createHighSpeedRequestList(request)
                highSpeedSession.setRepeatingBurst(burst, null, cameraHandler)
            } else {
                session.setRepeatingRequest(request, null, cameraHandler)
            }
            AppLog.i(
                TAG,
                "capture repeating started actual=${config.profile.actualLabel} torch=${config.profile.torchEnabled} session=${config.profile.sessionMode}",
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
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    ?: CameraCharacteristics.LENS_FACING_BACK
                val displayRotation = textureView.display?.rotation ?: Surface.ROTATION_0
                val relativeRotation = computeRelativeRotation(sensorOrientation, displayRotation, lensFacing)
                val bufferWidth = if (relativeRotation % 180 == 0) {
                    config.profile.captureSize.width.toFloat()
                } else {
                    config.profile.captureSize.height.toFloat()
                }
                val bufferHeight = if (relativeRotation % 180 == 0) {
                    config.profile.captureSize.height.toFloat()
                } else {
                    config.profile.captureSize.width.toFloat()
                }

                val matrix = Matrix()
                val viewRect = RectF(0f, 0f, textureView.width.toFloat(), textureView.height.toFloat())
                val centerX = viewRect.centerX()
                val centerY = viewRect.centerY()
                val bufferRect = RectF(
                    centerX - bufferWidth / 2f,
                    centerY - bufferHeight / 2f,
                    centerX + bufferWidth / 2f,
                    centerY + bufferHeight / 2f,
                )
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = maxOf(
                    viewRect.width() / bufferWidth,
                    viewRect.height() / bufferHeight,
                )
                matrix.postScale(scale, scale, centerX, centerY)
                matrix.postRotate(-relativeRotation.toFloat(), centerX, centerY)
                textureView.setTransform(matrix)
                AppLog.d(
                    TAG,
                    "stream preview transform rotation=$relativeRotation view=${textureView.width}x${textureView.height} buffer=${config.profile.captureSize.width}x${config.profile.captureSize.height} session=${config.profile.sessionMode}",
                )
            } catch (t: Throwable) {
                AppLog.w(TAG, "updatePreviewTransform failed: ${t.message}", t)
            }
        }
    }

    private fun computeRelativeRotation(sensorOrientation: Int, displayRotation: Int, lensFacing: Int): Int {
        val displayDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val sign = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) -1 else 1
        return (sensorOrientation - displayDegrees * sign + 360) % 360
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
