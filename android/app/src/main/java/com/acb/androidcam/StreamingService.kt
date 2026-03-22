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
    private var reconnectCount = 0
    private var monitorThread: Thread? = null
    private var stopMonitor = false
    private var lastReconnectAttemptAtMs = 0L

    private var cfgTransport: String = "usb-adb"
    private var cfgReceiver: String = "127.0.0.1:39393"
    private var cfgWidth: Int = 1280
    private var cfgHeight: Int = 720
    private var cfgFps: Int = 30
    private var cfgBitrate: Int = 5_000_000
    private var cfgPushMic: Boolean = true

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                cfgTransport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "usb-adb"
                cfgReceiver = intent.getStringExtra(EXTRA_RECEIVER) ?: "127.0.0.1:39393"
                cfgWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
                cfgHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
                cfgFps = intent.getIntExtra(EXTRA_FPS, 30)
                cfgBitrate = intent.getIntExtra(EXTRA_BITRATE, 5_000_000)
                cfgPushMic = intent.getBooleanExtra(EXTRA_PUSH_MIC, true)
                reconnectCount = 0
                startStreaming("started")
            }

            ACTION_STOP -> {
                stopStreaming("stopped")
                stopSelf()
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        stopStreaming("destroyed")
        stopMonitor = true
        monitorThread?.join(500)
        super.onDestroy()
    }

    private fun startStreaming(reason: String) {
        val notification = buildNotification(
            receiver = cfgReceiver,
            state = "running",
            reconnectCount = reconnectCount,
        )
        startForeground(NOTIFICATION_ID, notification)

        if (controller == null) {
            controller = CameraController(this, this, null)
        }
        if (running) {
            controller?.stop()
        }
        controller?.start(
            transport = when (cfgTransport) {
                "lan" -> CameraController.TransportMode.LAN
                "usb-native" -> CameraController.TransportMode.USB_NATIVE
                else -> CameraController.TransportMode.USB_ADB
            },
            receiverAddress = cfgReceiver,
            width = cfgWidth,
            height = cfgHeight,
            fps = cfgFps,
            bitrate = cfgBitrate,
            pushMic = cfgPushMic,
        )
        running = true
        broadcastStatus("running", reason)
    }

    private fun stopStreaming(reason: String) {
        if (!running) return
        controller?.stop()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastStatus("stopped", reason)
    }

    private fun startMonitor() {
        stopMonitor = false
        monitorThread = Thread {
            while (!stopMonitor) {
                try {
                    if (running) {
                        val now = System.currentTimeMillis()
                        val healthy = controller?.isHealthy() ?: false
                        if (!healthy) {
                            val backoffSec = minOf(30, 5 + reconnectCount * 2)
                            if (now - lastReconnectAttemptAtMs >= backoffSec * 1000L) {
                                lastReconnectAttemptAtMs = now
                                reconnectCount += 1
                                broadcastStatus("reconnecting", "unhealthy_stream")
                                startStreaming("auto_reconnect")
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                Thread.sleep(2000)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun broadcastStatus(state: String, reason: String) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_STATE, state)
            putExtra(EXTRA_STATUS_REASON, reason)
            putExtra(EXTRA_STATUS_RUNNING, running)
            putExtra(EXTRA_STATUS_RECONNECT_COUNT, reconnectCount)
            putExtra(EXTRA_RECEIVER, cfgReceiver)
        }
        sendBroadcast(intent)

        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null && running) {
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(cfgReceiver, state, reconnectCount),
            )
        }
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

    private fun buildNotification(receiver: String, state: String, reconnectCount: Int): Notification {
        val status = "$state / reconnect=$reconnectCount"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.bg_service_title))
            .setContentText(getString(R.string.bg_service_text, receiver) + " ($status)")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.acb.androidcam.action.START_STREAMING"
        const val ACTION_STOP = "com.acb.androidcam.action.STOP_STREAMING"
        const val ACTION_STATUS = "com.acb.androidcam.action.STREAMING_STATUS"

        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_RECEIVER = "receiver"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_PUSH_MIC = "push_mic"

        const val EXTRA_STATUS_STATE = "status_state"
        const val EXTRA_STATUS_REASON = "status_reason"
        const val EXTRA_STATUS_RUNNING = "status_running"
        const val EXTRA_STATUS_RECONNECT_COUNT = "status_reconnect_count"

        private const val CHANNEL_ID = "acb_streaming"
        private const val NOTIFICATION_ID = 1001
    }
}
