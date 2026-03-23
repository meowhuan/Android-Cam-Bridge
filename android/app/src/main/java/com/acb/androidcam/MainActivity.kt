package com.acb.androidcam

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ResolutionPreset(val label: String, val width: Int, val height: Int) {
    override fun toString(): String = label
}

data class FpsPreset(val label: String, val fps: Int) {
    override fun toString(): String = label
}

class MainActivity : AppCompatActivity() {
    private lateinit var controller: CameraController
    private var usbAccessoryTransport: UsbAccessoryTransport? = null
    private var pendingAccessory: UsbAccessory? = null
    private var isLandscapeLocked = false
    private var keepScreenOnWhileStreaming = true
    private var backgroundStreamingEnabled = false
    private var controlsVisible = true
    private var debugModeEnabled = false
    private var selectedFps = 60
    private var isStreaming = false
    private var statusTextView: TextView? = null
    private var debugOverlayView: View? = null
    private var debugLogScrollView: ScrollView? = null
    private var debugLogTextView: TextView? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val streamingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != StreamingService.ACTION_STATUS) return
            val state = intent.getStringExtra(StreamingService.EXTRA_STATUS_STATE) ?: "unknown"
            val reason = intent.getStringExtra(StreamingService.EXTRA_STATUS_REASON) ?: ""
            val receiver = intent.getStringExtra(StreamingService.EXTRA_RECEIVER) ?: ""
            val reconnect = intent.getIntExtra(StreamingService.EXTRA_STATUS_RECONNECT_COUNT, 0)
            val line = "FGS: $state / reconnect=$reconnect / $receiver / $reason"
            statusTextView?.text = line
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isLandscapeLocked = savedInstanceState?.getBoolean(KEY_LANDSCAPE_LOCKED)
            ?: prefs.getBoolean(KEY_LANDSCAPE_LOCKED, false)
        keepScreenOnWhileStreaming = savedInstanceState?.getBoolean(KEY_KEEP_SCREEN_ON)
            ?: prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        backgroundStreamingEnabled = savedInstanceState?.getBoolean(KEY_BACKGROUND_STREAMING)
            ?: prefs.getBoolean(KEY_BACKGROUND_STREAMING, false)
        controlsVisible = savedInstanceState?.getBoolean(KEY_CONTROLS_VISIBLE)
            ?: prefs.getBoolean(KEY_CONTROLS_VISIBLE, true)
        debugModeEnabled = savedInstanceState?.getBoolean(KEY_DEBUG_MODE)
            ?: prefs.getBoolean(KEY_DEBUG_MODE, false)
        selectedFps = savedInstanceState?.getInt(KEY_FPS)
            ?: prefs.getInt(KEY_FPS, 60)

        applyOrientationLock()

        val status = findViewById<TextView>(R.id.statusText)
        statusTextView = status
        debugOverlayView = findViewById(R.id.debugOverlay)
        debugLogScrollView = findViewById(R.id.debugLogScroll)
        debugLogTextView = findViewById(R.id.debugLogText)
        val previewView = findViewById<PreviewView>(R.id.previewView)
        controller = CameraController(this, this, previewView) { msg ->
            runOnUiThread {
                if (debugModeEnabled) {
                    appendDebugLogLine(msg)
                }
            }
        }
        val start = findViewById<Button>(R.id.startButton)
        val stop = findViewById<Button>(R.id.stopButton)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val transportSpinner = findViewById<Spinner>(R.id.transportSpinner)
        val resolutionSpinner = findViewById<Spinner>(R.id.resolutionSpinner)
        val fpsSpinner = findViewById<Spinner>(R.id.fpsSpinner)
        val micCheckbox = findViewById<CheckBox>(R.id.micCheckbox)
        val sleepProtectionCheckbox = findViewById<CheckBox>(R.id.sleepProtectionCheckbox)
        val backgroundStreamingCheckbox = findViewById<CheckBox>(R.id.backgroundStreamingCheckbox)
        val debugModeCheckbox = findViewById<CheckBox?>(R.id.debugModeCheckbox)
        val orientationButton = findViewById<Button>(R.id.orientationButton)
        val controlsPanel = findViewById<View>(R.id.controlsPanel)
        val toggleUiButton = findViewById<Button>(R.id.toggleUiButton)
        val debugOverlayCloseButton = findViewById<Button?>(R.id.debugOverlayCloseButton)
        val debugOverlayClearButton = findViewById<Button?>(R.id.debugOverlayClearButton)

        val transportOptions = listOf("USB ADB", "USB Native", "USB AOA", "Wi-Fi")
        val resolutions = listOf(
            ResolutionPreset("640x480", 640, 480),
            ResolutionPreset("1280x720", 1280, 720),
            ResolutionPreset("1920x1080", 1920, 1080)
        )
        val fpsOptions = listOf(
            FpsPreset("30 FPS", 30),
            FpsPreset("60 FPS", 60),
        )

        transportSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transportOptions)
        resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        resolutionSpinner.setSelection(1)
        val fpsIndex = fpsOptions.indexOfFirst { it.fps == selectedFps }.let { if (it < 0) 1 else it }
        fpsSpinner.setSelection(fpsIndex)
        micCheckbox.isChecked = true
        sleepProtectionCheckbox.isChecked = keepScreenOnWhileStreaming
        backgroundStreamingCheckbox.isChecked = backgroundStreamingEnabled
        debugModeCheckbox?.isChecked = debugModeEnabled
        updateOrientationButtonText(orientationButton)
        applyControlsVisibility(controlsPanel, toggleUiButton)
        applyDebugModeUi()

        ensurePermissions()

        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED)

        // Register receiver for ADB-triggered AOA mode entry
        registerReceiver(aoaEntryReceiver, IntentFilter(ACTION_ENTER_AOA),
            Context.RECEIVER_EXPORTED)

        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            handleUsbAccessoryIntent(intent)
        }

        sleepProtectionCheckbox.setOnCheckedChangeListener { _, checked ->
            keepScreenOnWhileStreaming = checked
            persistFlags()
            applyKeepScreenOnFlag()
        }

        backgroundStreamingCheckbox.setOnCheckedChangeListener { _, checked ->
            backgroundStreamingEnabled = checked
            persistFlags()
        }
        debugModeCheckbox?.setOnCheckedChangeListener { _, checked ->
            debugModeEnabled = checked
            persistFlags()
            applyDebugModeUi()
            if (checked) {
                appendDebugLogLine("debug mode enabled")
            }
        }
        debugOverlayCloseButton?.setOnClickListener {
            debugModeEnabled = false
            debugModeCheckbox?.isChecked = false
            persistFlags()
            applyDebugModeUi()
        }
        debugOverlayClearButton?.setOnClickListener {
            debugLogTextView?.text = ""
        }

        orientationButton.setOnClickListener {
            isLandscapeLocked = !isLandscapeLocked
            persistFlags()
            applyOrientationLock()
            updateOrientationButtonText(orientationButton)
        }

        toggleUiButton.setOnClickListener {
            controlsVisible = !controlsVisible
            persistFlags()
            applyControlsVisibility(controlsPanel, toggleUiButton)
        }

        start.setOnClickListener {
            ensurePermissions()

            val mode = when (transportSpinner.selectedItemPosition) {
                0 -> CameraController.TransportMode.USB_ADB
                1 -> CameraController.TransportMode.USB_NATIVE
                2 -> CameraController.TransportMode.USB_AOA
                else -> CameraController.TransportMode.LAN
            }

            val preset = resolutionSpinner.selectedItem as ResolutionPreset
            val fpsPreset = fpsSpinner.selectedItem as FpsPreset
            selectedFps = fpsPreset.fps
            persistFlags()
            val receiver = hostInput.text.toString().trim().ifBlank {
                when (mode) {
                    CameraController.TransportMode.USB_ADB -> "127.0.0.1:39393"
                    CameraController.TransportMode.USB_NATIVE -> ""
                    CameraController.TransportMode.USB_AOA -> ""
                    CameraController.TransportMode.LAN -> "192.168.1.100:39393"
                }
            }
            val receiverLabel = if (mode == CameraController.TransportMode.USB_NATIVE && receiver.isBlank()) {
                "auto-detect"
            } else {
                receiver
            }

            if (backgroundStreamingEnabled) {
                controller.stop()
                startBackgroundStreaming(
                    transport = mode,
                    receiver = receiver,
                    width = preset.width,
                    height = preset.height,
                    pushMic = micCheckbox.isChecked,
                )
            } else {
                stopBackgroundStreaming()
                controller.start(
                    transport = mode,
                    receiverAddress = receiver,
                    width = preset.width,
                    height = preset.height,
                    fps = selectedFps,
                    bitrate = computeBitrate(preset.width, selectedFps),
                    pushMic = micCheckbox.isChecked,
                )
            }
            isStreaming = true
            applyKeepScreenOnFlag()
            status.text = if (backgroundStreamingEnabled) {
                getString(R.string.status_streaming_bg, preset.label, receiverLabel)
            } else {
                getString(R.string.status_streaming, preset.label, receiverLabel)
            }
        }

        stop.setOnClickListener {
            controller.stop()
            stopBackgroundStreaming()
            isStreaming = false
            applyKeepScreenOnFlag()
            status.text = getString(R.string.status_stopped)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            handleUsbAccessoryIntent(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        usbAccessoryTransport?.close()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(aoaEntryReceiver) } catch (_: Exception) {}
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(StreamingService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamingStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(streamingStatusReceiver, filter)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(streamingStatusReceiver)
        } catch (_: Throwable) {
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LANDSCAPE_LOCKED, isLandscapeLocked)
        outState.putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOnWhileStreaming)
        outState.putBoolean(KEY_BACKGROUND_STREAMING, backgroundStreamingEnabled)
        outState.putBoolean(KEY_CONTROLS_VISIBLE, controlsVisible)
        outState.putBoolean(KEY_DEBUG_MODE, debugModeEnabled)
        outState.putInt(KEY_FPS, selectedFps)
        super.onSaveInstanceState(outState)
    }

    private fun applyOrientationLock() {
        requestedOrientation = if (isLandscapeLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun applyKeepScreenOnFlag() {
        if (isStreaming && keepScreenOnWhileStreaming) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun persistFlags() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LANDSCAPE_LOCKED, isLandscapeLocked)
            .putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOnWhileStreaming)
            .putBoolean(KEY_BACKGROUND_STREAMING, backgroundStreamingEnabled)
            .putBoolean(KEY_CONTROLS_VISIBLE, controlsVisible)
            .putBoolean(KEY_DEBUG_MODE, debugModeEnabled)
            .putInt(KEY_FPS, selectedFps)
            .apply()
    }

    private fun startBackgroundStreaming(
        transport: CameraController.TransportMode,
        receiver: String,
        width: Int,
        height: Int,
        pushMic: Boolean,
    ) {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(
                StreamingService.EXTRA_TRANSPORT,
                when (transport) {
                    CameraController.TransportMode.LAN -> "lan"
                    CameraController.TransportMode.USB_NATIVE -> "usb-native"
                    CameraController.TransportMode.USB_ADB -> "usb-adb"
                    CameraController.TransportMode.USB_AOA -> "usb-aoa"
                },
            )
            putExtra(StreamingService.EXTRA_RECEIVER, receiver)
            putExtra(StreamingService.EXTRA_WIDTH, width)
            putExtra(StreamingService.EXTRA_HEIGHT, height)
            putExtra(StreamingService.EXTRA_FPS, selectedFps)
            putExtra(StreamingService.EXTRA_BITRATE, computeBitrate(width, selectedFps))
            putExtra(StreamingService.EXTRA_PUSH_MIC, pushMic)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopBackgroundStreaming() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun applyControlsVisibility(panel: View, toggleButton: Button) {
        panel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        toggleButton.text = if (controlsVisible) {
            getString(R.string.btn_hide_controls)
        } else {
            getString(R.string.btn_show_controls)
        }
    }

    private fun updateOrientationButtonText(button: Button) {
        button.text = if (isLandscapeLocked) {
            getString(R.string.btn_orientation_landscape_on)
        } else {
            getString(R.string.btn_orientation_landscape_off)
        }
    }

    private fun appendDebugLogLine(message: String) {
        val tv = debugLogTextView ?: return
        val line = "[${timeFmt.format(Date())}] $message"
        val prev = tv.text?.toString().orEmpty()
        tv.text = if (prev.isBlank()) line else "$prev\n$line"
        debugLogScrollView?.post {
            debugLogScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyDebugModeUi() {
        debugOverlayView?.visibility = if (debugModeEnabled) View.VISIBLE else View.GONE
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val accessory: UsbAccessory? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && accessory != null) {
                    openUsbAccessory(accessory)
                } else {
                    Log.w("MainActivity", "USB accessory permission denied")
                }
            }
        }
    }

    // Receiver for ADB-triggered AOA mode: Windows sends this broadcast via
    // "adb shell am broadcast" when it can't open the device directly (driver lock).
    // We check if a USB accessory is already available and open it.
    private val aoaEntryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_ENTER_AOA) {
                Log.i("MainActivity", "Received ACTION_ENTER_AOA from ADB")
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val accessories = usbManager.accessoryList
                if (accessories != null && accessories.isNotEmpty()) {
                    handleUsbAccessoryIntent(android.content.Intent().apply {
                        putExtra(UsbManager.EXTRA_ACCESSORY, accessories[0])
                    })
                } else {
                    Log.w("MainActivity", "No USB accessory available for AOA entry")
                }
            }
        }
    }

    private fun handleUsbAccessoryIntent(intent: Intent) {
        val accessory: UsbAccessory? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
        if (accessory == null) return
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(accessory)) {
            openUsbAccessory(accessory)
        } else {
            pendingAccessory = accessory
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(accessory, pi)
        }
    }

    private fun openUsbAccessory(accessory: UsbAccessory) {
        usbAccessoryTransport?.close()
        val transport = UsbAccessoryTransport(this) { msg ->
            runOnUiThread {
                appendDebugLogLine(msg)
            }
        }
        if (transport.open(accessory)) {
            usbAccessoryTransport = transport
            controller.attachUsbAccessoryTransport(transport)
            Log.i("MainActivity", "USB AOA accessory opened successfully")
        } else {
            Log.e("MainActivity", "Failed to open USB accessory")
        }
    }

    private fun ensurePermissions() {
        val needCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val needAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED

        if (needCamera || needAudio) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun computeBitrate(width: Int, fps: Int): Int = when {
        width >= 1920 -> if (fps >= 60) 15_000_000 else 10_000_000
        width >= 1280 -> if (fps >= 60)  8_000_000 else  5_000_000
        else          -> if (fps >= 60)  3_000_000 else  2_000_000
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.acb.androidcam.USB_PERMISSION"
        const val ACTION_ENTER_AOA = "com.acb.androidcam.ACTION_ENTER_AOA"
        private const val PREFS_NAME = "acb_main"
        private const val KEY_LANDSCAPE_LOCKED = "landscape_locked"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_BACKGROUND_STREAMING = "background_streaming"
        private const val KEY_CONTROLS_VISIBLE = "controls_visible"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_FPS = "fps"
    }
}
