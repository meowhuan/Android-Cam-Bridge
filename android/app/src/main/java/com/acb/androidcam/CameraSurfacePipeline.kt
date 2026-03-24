package com.acb.androidcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
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
    private val previewView: TextureView?,
) {
    companion object {
        private const val TAG = "CameraSurfacePipeline"

        fun resolveCaptureSize(context: Context, width: Int, height: Int): Size {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = findBackCameraId(manager) ?: manager.cameraIdList.firstOrNull()
                if (cameraId == null) {
                    Size(width, height)
                } else {
                    val characteristics = manager.getCameraCharacteristics(cameraId)
                    chooseBestSize(
                        characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP),
                        width,
                        height,
                    )
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "resolveCaptureSize failed: ${t.message}", t)
                Size(width, height)
            }
        }

        private fun findBackCameraId(manager: CameraManager): String? {
            return manager.cameraIdList.firstOrNull { id ->
                val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            }
        }

        private fun chooseBestSize(map: StreamConfigurationMap?, width: Int, height: Int): Size {
            val requestedLong = maxOf(width, height)
            val requestedShort = minOf(width, height)
            val candidates = map?.getOutputSizes(MediaRecorder::class.java)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)
                ?: emptyArray()
            if (candidates.isEmpty()) {
                return Size(width, height)
            }

            return candidates.minWithOrNull(
                compareBy<Size> { abs(maxOf(it.width, it.height) - requestedLong) + abs(minOf(it.width, it.height) - requestedShort) }
                    .thenBy { abs((it.width * it.height) - (width * height)) }
            ) ?: Size(width, height)
        }
    }

    private data class Config(
        val width: Int,
        val height: Int,
        val fps: Int,
        val encoderSurface: Surface,
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

    fun start(width: Int, height: Int, fps: Int, encoderSurface: Surface) {
        stop()
        running = true
        activeConfig = Config(width = width, height = height, fps = fps, encoderSurface = encoderSurface)
        ensureCameraThread()
        ensurePreviewListener()
        openCameraWhenReady("start")
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

        previewView?.let {
            if (surfaceListenerInstalled) {
                it.surfaceTextureListener = null
                surfaceListenerInstalled = false
            }
        }
    }

    private fun ensureCameraThread() {
        if (cameraThread != null && cameraHandler != null) return
        val thread = HandlerThread("AcbCamera2")
        thread.start()
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private fun ensurePreviewListener() {
        val textureView = previewView ?: return
        if (surfaceListenerInstalled) return
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                AppLog.i(TAG, "preview surface available ${width}x${height}")
                val handler = cameraHandler
                val device = cameraDevice
                if (handler != null && device != null && running) {
                    handler.post { createCaptureSession(device) }
                } else {
                    openCameraWhenReady("preview_available")
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                val handler = cameraHandler
                handler?.post { releasePreviewSurfaceLocked() }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }
        surfaceListenerInstalled = true
    }

    private fun openCameraWhenReady(reason: String) {
        val config = activeConfig ?: return
        if (!running) return
        val textureView = previewView
        if (textureView != null && !textureView.isAvailable) {
            AppLog.i(TAG, "waiting for preview surface reason=$reason")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w(TAG, "CAMERA permission missing reason=$reason")
            return
        }

        val handler = cameraHandler ?: return
        handler.post {
            if (!running || pendingOpen || cameraDevice != null) return@post
            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = findBackCameraId(manager) ?: manager.cameraIdList.firstOrNull()
                if (cameraId.isNullOrBlank()) {
                    AppLog.e(TAG, "No camera available")
                    return@post
                }
                pendingOpen = true
                activeCameraId = cameraId
                AppLog.i(TAG, "opening camera id=$cameraId reason=$reason capture=${config.width}x${config.height}@${config.fps}")
                manager.openCamera(cameraId, buildStateCallback(cameraId), handler)
            } catch (t: Throwable) {
                pendingOpen = false
                AppLog.e(TAG, "openCamera failed: ${t.message}", t)
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
                closeSessionLocked()
                closeDeviceLocked()
            }

            override fun onError(device: CameraDevice, error: Int) {
                pendingOpen = false
                AppLog.e(TAG, "camera error id=$cameraId error=$error")
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
            val preview = buildPreviewSurface(config.width, config.height)
            if (preview != null) {
                previewSurface = preview
                surfaces += preview
            }
            surfaces += config.encoderSurface

            device.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running) {
                            session.close()
                            return
                        }
                        captureSession = session
                        startRepeating(session, device, config)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        AppLog.e(TAG, "capture session configure failed")
                        session.close()
                    }
                },
                handler,
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "createCaptureSession failed: ${t.message}", t)
        }
    }

    private fun startRepeating(session: CameraCaptureSession, device: CameraDevice, config: Config) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(activeCameraId ?: return)
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(config.encoderSurface)
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                chooseFpsRange(characteristics, config.fps)?.let {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                }
                if (characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                        ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
            }.build()
            session.setRepeatingRequest(request, null, cameraHandler)
            AppLog.i(TAG, "capture repeating started ${config.width}x${config.height}@${config.fps}")
        } catch (t: Throwable) {
            AppLog.e(TAG, "startRepeating failed: ${t.message}", t)
        }
    }

    private fun buildPreviewSurface(width: Int, height: Int): Surface? {
        val textureView = previewView ?: return null
        val texture = textureView.surfaceTexture ?: return null
        texture.setDefaultBufferSize(width, height)
        return Surface(texture)
    }

    private fun chooseFpsRange(characteristics: CameraCharacteristics, targetFps: Int): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        if (ranges.isEmpty()) return null
        return ranges.minWithOrNull(
            compareBy<Range<Int>> { abs(it.upper - targetFps) + abs(it.lower - targetFps) }
                .thenBy { abs(it.upper - it.lower) }
        )
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
