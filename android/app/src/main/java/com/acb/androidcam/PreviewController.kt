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

        val profile = spec.profile
        val selector = buildCameraSelector(profile.cameraId)
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        emitState(PreviewUiState.STARTING, "Binding preview ${profile.width}x${profile.height}")

        try {
            val previewBuilder = Preview.Builder()
                .setTargetResolution(android.util.Size(profile.width, profile.height))
                .setTargetRotation(rotation)
            @Suppress("UnsafeOptInUsageError")
            Camera2Interop.Extender(previewBuilder).apply {
                when (spec.mode) {
                    CaptureModePreset.LATENCY -> {
                        setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE, android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                        setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE, android.hardware.camera2.CaptureRequest.EDGE_MODE_FAST)
                    }
                    CaptureModePreset.BALANCED -> {
                        setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE, android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                        setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE, android.hardware.camera2.CaptureRequest.EDGE_MODE_FAST)
                    }
                    CaptureModePreset.LOW_LIGHT -> {
                        setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE, android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                        setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE, android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    }
                }
            }
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val viewPort = ViewPort.Builder(
                Rational(profile.width, profile.height),
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
            camera.cameraControl.enableTorch(spec.torchEnabled && profile.supportsTorch)
            val detail =
                "Preview Ready ${profile.width}x${profile.height}@${profile.targetFps} camera=${boundCameraId ?: "default"}"
            emitState(PreviewUiState.READY, detail)
            emitDebug("preview bound reason=$reason requested=${spec.displayLabel} resolved=${profile.width}x${profile.height} camera=${boundCameraId ?: "default"} torch=${spec.torchEnabled && profile.supportsTorch}")
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
