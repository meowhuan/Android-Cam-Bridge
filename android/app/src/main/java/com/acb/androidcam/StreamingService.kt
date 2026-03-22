package com.acb.androidcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

class StreamingService : LifecycleService() {
    private var controller: CameraController? = null
    private var running = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val transport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "usb-adb"
                val receiver = intent.getStringExtra(EXTRA_RECEIVER) ?: "127.0.0.1:39393"
                val width = intent.getIntExtra(EXTRA_WIDTH, 1280)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 720)
                val fps = intent.getIntExtra(EXTRA_FPS, 30)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 5_000_000)
                val pushMic = intent.getBooleanExtra(EXTRA_PUSH_MIC, true)
                startStreaming(transport, receiver, width, height, fps, bitrate, pushMic)
            }

            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun startStreaming(
        transport: String,
        receiver: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        pushMic: Boolean,
    ) {
        val notification = buildNotification(receiver)
        startForeground(NOTIFICATION_ID, notification)

        if (controller == null) {
            controller = CameraController(this, this, null)
        }
        if (running) {
            controller?.stop()
        }
        controller?.start(
            transport = when (transport) {
                "lan" -> CameraController.TransportMode.LAN
                "usb-native" -> CameraController.TransportMode.USB_NATIVE
                else -> CameraController.TransportMode.USB_ADB
            },
            receiverAddress = receiver,
            width = width,
            height = height,
            fps = fps,
            bitrate = bitrate,
            pushMic = pushMic,
        )
        running = true
    }

    private fun stopStreaming() {
        if (!running) return
        controller?.stop()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ACB Streaming",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(receiver: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.bg_service_title))
            .setContentText(getString(R.string.bg_service_text, receiver))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.acb.androidcam.action.START_STREAMING"
        const val ACTION_STOP = "com.acb.androidcam.action.STOP_STREAMING"

        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_PUSH_MIC = "push_mic"

        private const val CHANNEL_ID = "acb_streaming"
        private const val NOTIFICATION_ID = 1001
    }
}
