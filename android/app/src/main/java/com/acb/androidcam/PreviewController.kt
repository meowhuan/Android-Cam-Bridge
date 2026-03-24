package com.acb.androidcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Rational
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

class PreviewController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onStateChanged: ((PreviewUiState, String) -> Unit)? = null,
    private val onDebugEvent: ((String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "PreviewController"
    }

    private val providerFuture = ProcessCameraProvider.getInstance(context)
    private var provider: ProcessCameraProvider? = null
    private var activeSpec: CaptureSpec? = null
    private var activePreview: Preview? = null
    private var activeCamera: Camera? = null
    private var started = false
    private var paused = false

    init {
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        providerFuture.addListener(
            {
                try {
                    provider = providerFuture.get()
                    emitState(PreviewUiState.STARTING, "Preview provider ready")
                    if (started && !paused) {
                        bindPreview("provider_ready")
                    }
                } catch (t: Throwable) {
                    emitState(PreviewUiState.ERROR, "Preview provider failed: ${t.message}")
                    AppLog.e(TAG, "provider init failed: ${t.message}", t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun updateSpec(spec: CaptureSpec, reason: String) {
        if (activeSpec == spec && activePreview != null && started && !paused) {
            return
        }
        activeSpec = spec
        if (started && !paused) {
            bindPreview(reason)
        }
    }

    fun start(reason: String) {
        started = true
        paused = false
        bindPreview(reason)
    }

    fun pause(reason: String) {
        paused = true
        unbindPreview()
        emitState(PreviewUiState.PAUSED, "Preview paused: $reason")
    }

    fun stop(reason: String) {
        started = false
        paused = false
        unbindPreview()
        emitState(PreviewUiState.IDLE, "Preview idle: $reason")
    }

    fun rebindForRotation(reason: String) {
        if (started && !paused) {
            bindPreview(reason)
        }
    }

    private fun bindPreview(reason: String) {
        val spec = activeSpec ?: run {
            emitState(PreviewUiState.IDLE, "Preview waiting for capture settings")
            return
        }
        if (!started || paused) return
        if (!hasCameraPermission()) {
            emitState(PreviewUiState.ERROR, "Camera permission required")
            return
        }

        val provider = provider
        if (provider == null) {
            emitState(PreviewUiState.STARTING, "Preview is preparing")
            return
        }

        val actualProfile = CameraSurfacePipeline.prepareCaptureProfile(
            context = context,
            spec = spec,
            allowHighSpeed = false,
        )
        val profile = actualProfile.selectedProfile
        val selector = buildCameraSelector(profile.cameraId)
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        emitState(PreviewUiState.STARTING, "Binding preview ${actualProfile.actualLabel}")

        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val characteristics = manager.getCameraCharacteristics(profile.cameraId)
            val previewBuilder = Preview.Builder()
                .setTargetResolution(android.util.Size(actualProfile.captureSize.width, actualProfile.captureSize.height))
                .setTargetRotation(rotation)
            @Suppress("UnsafeOptInUsageError")
            Camera2Interop.Extender(previewBuilder).apply {
                setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_MODE, actualProfile.tuning.controlMode)
                setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE, actualProfile.tuning.aeMode)
                setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, actualProfile.tuning.aeTargetFpsRange)
                setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO)
                setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
                setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE, actualProfile.tuning.noiseReductionMode)
                setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE, actualProfile.tuning.edgeMode)
                actualProfile.tuning.sceneMode?.let {
                    setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE, it)
                }
                actualProfile.tuning.colorCorrectionAberrationMode?.let {
                    setCaptureRequestOption(android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, it)
                }
                actualProfile.tuning.hotPixelMode?.let {
                    setCaptureRequestOption(android.hardware.camera2.CaptureRequest.HOT_PIXEL_MODE, it)
                }
                actualProfile.tuning.shadingMode?.let {
                    setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SHADING_MODE, it)
                }
                actualProfile.tuning.tonemapMode?.let {
                    setCaptureRequestOption(android.hardware.camera2.CaptureRequest.TONEMAP_MODE, it)
                }
                actualProfile.tuning.postRawSensitivityBoost?.let {
                    setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it)
                }
                if (characteristics.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?.contains(android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                    setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        android.hardware.camera2.CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                    )
                }
            }
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val viewPort = ViewPort.Builder(
                Rational(actualProfile.captureSize.width, actualProfile.captureSize.height),
                rotation,
            )
                .setScaleType(ViewPort.FILL_CENTER)
                .build()
            val group = UseCaseGroup.Builder()
                .setViewPort(viewPort)
                .addUseCase(preview)
                .build()

            provider.unbindAll()
            val camera = provider.bindToLifecycle(lifecycleOwner, selector, group)
            activePreview = preview
            activeCamera = camera
            val boundCameraId = runCatching { Camera2CameraInfo.from(camera.cameraInfo).cameraId }.getOrNull()
            camera.cameraControl.enableTorch(actualProfile.torchEnabled)
            val detail =
                "Preview Ready ${actualProfile.actualLabel} camera=${boundCameraId ?: "default"}"
            emitState(PreviewUiState.READY, detail)
            emitDebug(
                "preview bound reason=$reason requested=${spec.displayLabel} actual=${actualProfile.actualLabel} " +
                    "camera=${boundCameraId ?: "default"} torch=${actualProfile.torchEnabled} session=${actualProfile.sessionMode}",
            )
        } catch (t: Throwable) {
            activePreview = null
            activeCamera = null
            emitState(PreviewUiState.ERROR, "Preview failed: ${t.message}")
            AppLog.e(TAG, "bindPreview failed: ${t.message}", t)
        }
    }

    private fun unbindPreview() {
        activePreview = null
        activeCamera = null
        runCatching { provider?.unbindAll() }
    }

    private fun buildCameraSelector(preferredCameraId: String?): CameraSelector {
        val builder = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        if (!preferredCameraId.isNullOrBlank()) {
            builder.addCameraFilter { cameraInfos ->
                val matches = cameraInfos.filter {
                    runCatching { Camera2CameraInfo.from(it).cameraId == preferredCameraId }.getOrDefault(false)
                }
                if (matches.isNotEmpty()) matches else cameraInfos
            }
        }
        return builder.build()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun emitState(state: PreviewUiState, detail: String) {
        onStateChanged?.invoke(state, detail)
        AppLog.i(TAG, "state=$state detail=$detail")
    }

    private fun emitDebug(message: String) {
        onDebugEvent?.invoke(message)
        AppLog.i(TAG, message)
    }
}
