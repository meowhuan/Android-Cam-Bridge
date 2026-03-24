package com.acb.androidcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

class StreamingService : LifecycleService() {
    private var controller: CameraController? = null
    private var usbAccessoryTransport: UsbAccessoryTransport? = null
    private var pendingAccessory: UsbAccessory? = null
    @Volatile private var running = false
    private var reconnectCount = 0
    private var monitorThread: Thread? = null
    @Volatile private var stopMonitor = false
    private var lastReconnectAttemptAtMs = 0L

    private var cfgTransport: String = "usb-adb"
    private var cfgReceiver: String = "127.0.0.1:39393"
    private var cfgWidth: Int = 1280
    private var cfgHeight: Int = 720
    private var cfgFps: Int = 60
    private var cfgBitrate: Int = 5_000_000
    private var cfgPushMic: Boolean = true
    private var cfgCaptureMode: CaptureModePreset = CaptureModePreset.BALANCED
    private var cfgTorchEnabled: Boolean = false
    private var cfgCameraId: String = ""

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("StreamingService", "onCreate logDir=${AppLog.getLogDirPath()}")
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED)
        ensureChannel()
        startMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                cfgTransport = intent.getStringExtra(EXTRA_TRANSPORT) ?: "usb-adb"
                cfgReceiver = intent.getStringExtra(EXTRA_RECEIVER) ?: "127.0.0.1:39393"
                cfgWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
                cfgHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
                cfgFps = intent.getIntExtra(EXTRA_FPS, 60)
                cfgBitrate = intent.getIntExtra(EXTRA_BITRATE, 5_000_000)
                cfgPushMic = intent.getBooleanExtra(EXTRA_PUSH_MIC, true)
                cfgCaptureMode = intent.getStringExtra(EXTRA_CAPTURE_MODE)
                    ?.let { runCatching { CaptureModePreset.valueOf(it) }.getOrNull() }
                    ?: CaptureModePreset.BALANCED
                cfgTorchEnabled = intent.getBooleanExtra(EXTRA_TORCH_ENABLED, false)
                cfgCameraId = intent.getStringExtra(EXTRA_CAMERA_ID).orEmpty()
                AppLog.i(
                    "StreamingService",
                    "ACTION_START transport=$cfgTransport receiver=$cfgReceiver ${cfgWidth}x${cfgHeight}@$cfgFps bitrate=$cfgBitrate mic=$cfgPushMic mode=$cfgCaptureMode torch=$cfgTorchEnabled camera=$cfgCameraId",
                )
                reconnectCount = 0
                startStreaming("started")
            }

            ACTION_STOP -> {
                AppLog.i("StreamingService", "ACTION_STOP")
                stopStreaming("stopped")
                stopSelf()
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        AppLog.i("StreamingService", "onDestroy")
        stopStreaming("destroyed")
        releaseUsbAccessoryTransport("service_destroy")
        stopMonitor = true
        monitorThread?.join(500)
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Throwable) {}
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
            controller = CameraController(
                this,
                onStreamStateChanged = { state, detail ->
                    AppLog.i("StreamingService", "stream_state=$state detail=$detail")
                },
            )
        }
        if (running) {
            controller?.stop()
            releaseUsbAccessoryTransport("restart")
        }
        AppLog.i("StreamingService", "startStreaming reason=$reason reconnect=$reconnectCount")
        if (cfgTransport == "usb-aoa") {
            ensureUsbAccessoryReady("startStreaming")
        }
        val selectedProfile =
            CameraSurfacePipeline.chooseProfileForRequest(this, cfgWidth, cfgHeight, cfgFps, cfgCameraId)
            ?: SupportedCaptureProfile(
                id = "fallback_${cfgWidth}x${cfgHeight}_$cfgFps",
                cameraId = cfgCameraId.ifBlank { "0" },
                width = cfgWidth,
                height = cfgHeight,
                targetFps = cfgFps,
                recommendedBitrate = cfgBitrate,
                supportsTorch = false,
            )
        controller?.start(
            transport = when (cfgTransport) {
                "lan" -> CameraController.TransportMode.LAN
                "usb-native" -> CameraController.TransportMode.USB_NATIVE
                "usb-aoa" -> CameraController.TransportMode.USB_AOA
                else -> CameraController.TransportMode.USB_ADB
            },
            receiverAddress = cfgReceiver,
            captureSpec = CaptureSpec(
                profile = selectedProfile,
                mode = cfgCaptureMode,
                torchEnabled = cfgTorchEnabled && selectedProfile.supportsTorch,
            ),
            streamPreviewView = null,
            pushMic = cfgPushMic,
        )
        running = true
        broadcastStatus("running", reason)
    }

    private fun stopStreaming(reason: String) {
        if (!running) return
        AppLog.i("StreamingService", "stopStreaming reason=$reason")
        controller?.stop()
        releaseUsbAccessoryTransport("stopStreaming_$reason")
        running = false
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
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
                                AppLog.w(
                                    "StreamingService",
                                    "stream unhealthy; scheduling reconnect in ${backoffSec}s (count=${reconnectCount + 1})",
                                )
                                lastReconnectAttemptAtMs = now
                                reconnectCount += 1
                                broadcastStatus("reconnecting", "unhealthy_stream")
                                startStreaming("auto_reconnect")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    AppLog.w("StreamingService", "monitor loop failed: ${t.message}", t)
                }
                Thread.sleep(2000)
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && accessory != null) {
                AppLog.i("StreamingService", "USB accessory permission granted ${accessorySummary(accessory)}")
                openUsbAccessory(accessory, "permission_granted")
            } else {
                AppLog.w("StreamingService", "USB accessory permission denied")
            }
        }
    }

    private fun ensureUsbAccessoryReady(reason: String): Boolean {
        val existing = usbAccessoryTransport
        if (existing?.isConnected() == true) {
            controller?.attachUsbAccessoryTransport(existing)
            AppLog.i("StreamingService", "Reusing USB AOA accessory connection reason=$reason")
            return true
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val accessory = usbManager.accessoryList?.firstOrNull()
        if (accessory == null) {
            AppLog.w("StreamingService", "No USB accessory present reason=$reason")
            return false
        }

        if (usbManager.hasPermission(accessory)) {
            AppLog.i("StreamingService", "USB accessory available reason=$reason ${accessorySummary(accessory)}")
            return openUsbAccessory(accessory, reason)
        }

        pendingAccessory = accessory
        AppLog.i("StreamingService", "Requesting USB accessory permission reason=$reason ${accessorySummary(accessory)}")
        val pi = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(accessory, pi)
        return false
    }

    private fun openUsbAccessory(accessory: UsbAccessory, reason: String): Boolean {
        val existing = usbAccessoryTransport
        if (existing?.isConnected() == true) {
            controller?.attachUsbAccessoryTransport(existing)
            AppLog.i("StreamingService", "AOA already open reason=$reason ${accessorySummary(accessory)}")
            return true
        }

        releaseUsbAccessoryTransport("reopen_for_$reason")
        val transport = UsbAccessoryTransport(this) { msg ->
            AppLog.i("StreamingService", "AOA: $msg")
        }
        if (!transport.open(accessory)) {
            AppLog.e("StreamingService", "Failed to open USB accessory reason=$reason ${accessorySummary(accessory)}")
            return false
        }

        usbAccessoryTransport = transport
        controller?.attachUsbAccessoryTransport(transport)
        AppLog.i("StreamingService", "USB accessory opened reason=$reason ${accessorySummary(accessory)}")
        return true
    }

    private fun releaseUsbAccessoryTransport(reason: String) {
        controller?.attachUsbAccessoryTransport(null)
        pendingAccessory = null
        val transport = usbAccessoryTransport ?: return
        usbAccessoryTransport = null
        try {
            transport.close()
        } catch (_: Throwable) {
        }
        AppLog.i("StreamingService", "Released USB accessory transport reason=$reason")
    }

    private fun accessorySummary(accessory: UsbAccessory): String {
        val manufacturer = accessory.manufacturer?.takeIf { it.isNotBlank() } ?: "unknown"
        val model = accessory.model?.takeIf { it.isNotBlank() } ?: "unknown"
        val version = accessory.version?.takeIf { it.isNotBlank() } ?: "unknown"
        return "manufacturer=$manufacturer model=$model version=$version"
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
        private const val ACTION_USB_PERMISSION = "com.acb.androidcam.USB_PERMISSION_SERVICE"
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
        const val EXTRA_CAPTURE_MODE = "capture_mode"
        const val EXTRA_TORCH_ENABLED = "torch_enabled"
        const val EXTRA_CAMERA_ID = "camera_id"

        const val EXTRA_STATUS_STATE = "status_state"
        const val EXTRA_STATUS_REASON = "status_reason"
        const val EXTRA_STATUS_RUNNING = "status_running"
        const val EXTRA_STATUS_RECONNECT_COUNT = "status_reconnect_count"

        private const val CHANNEL_ID = "acb_streaming"
        private const val NOTIFICATION_ID = 1001
    }
}
