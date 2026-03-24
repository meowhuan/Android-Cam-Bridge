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
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
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

data class CaptureModeOption(val preset: CaptureModePreset, val label: String) {
    override fun toString(): String = label
}

class MainActivity : AppCompatActivity() {
    private lateinit var controller: CameraController
    private lateinit var previewController: PreviewController

    private var usbAccessoryTransport: UsbAccessoryTransport? = null
    private var pendingAccessory: UsbAccessory? = null
    private var isLandscapeLocked = false
    private var keepScreenOnWhileStreaming = true
    private var backgroundStreamingEnabled = false
    private var controlsVisible = true
    private var debugModeEnabled = false
    private var isStreaming = false
    private var isActivityVisible = false
    private var suppressSelectionCallbacks = true

    private var availableProfiles: List<SupportedCaptureProfile> = emptyList()
    private lateinit var modeOptions: List<CaptureModeOption>

    private var statusTextView: TextView? = null
    private var previewStateTextView: TextView? = null
    private var streamStateTextView: TextView? = null
    private var previewDetailTextView: TextView? = null
    private var actualProfileTextView: TextView? = null
    private var debugOverlayView: View? = null
    private var debugLogScrollView: ScrollView? = null
    private var debugLogTextView: TextView? = null
    private var captureProfileSpinner: Spinner? = null
    private var captureModeSpinner: Spinner? = null
    private var transportSpinner: Spinner? = null
    private var hostInput: EditText? = null
    private var micCheckbox: CheckBox? = null
    private var backgroundStreamingCheckbox: CheckBox? = null
    private var sleepProtectionCheckbox: CheckBox? = null
    private var debugModeCheckbox: CheckBox? = null
    private var torchCheckbox: CheckBox? = null
    private var toggleUiButton: Button? = null
    private var idlePreviewView: PreviewView? = null
    private var streamPreviewView: TextureView? = null

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val streamingStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != StreamingService.ACTION_STATUS || !backgroundStreamingEnabled) return
            val state = intent.getStringExtra(StreamingService.EXTRA_STATUS_STATE) ?: "unknown"
            val reason = intent.getStringExtra(StreamingService.EXTRA_STATUS_REASON) ?: ""
            val receiver = intent.getStringExtra(StreamingService.EXTRA_RECEIVER) ?: ""
            when (state) {
                "running" -> {
                    isStreaming = true
                    renderStreamState(StreamUiState.STREAMING, "Background stream active -> $receiver")
                    renderPreviewState(PreviewUiState.PAUSED, getString(R.string.preview_hint_background))
                    showPreviewSurfaces(idleVisible = false, streamVisible = false)
                }
                "reconnecting" -> {
                    isStreaming = true
                    renderStreamState(StreamUiState.CONNECTING, "Background reconnecting: $reason")
                    renderPreviewState(PreviewUiState.PAUSED, getString(R.string.preview_hint_background))
                    showPreviewSurfaces(idleVisible = false, streamVisible = false)
                }
                else -> {
                    isStreaming = false
                    renderStreamState(StreamUiState.STOPPED, "Background stream stopped")
                    bindPreviewIfPossible("background_stream_stopped")
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        bindPreviewIfPossible("permission_result")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
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

        applyOrientationLock()

        idlePreviewView = findViewById(R.id.previewView)
        streamPreviewView = findViewById(R.id.streamPreviewView)
        previewStateTextView = findViewById(R.id.previewStateText)
        streamStateTextView = findViewById(R.id.streamStateText)
        previewDetailTextView = findViewById(R.id.previewDetailText)
        actualProfileTextView = findViewById(R.id.actualProfileText)
        statusTextView = findViewById(R.id.statusText)
        debugOverlayView = findViewById(R.id.debugOverlay)
        debugLogScrollView = findViewById(R.id.debugLogScroll)
        debugLogTextView = findViewById(R.id.debugLogText)
        captureProfileSpinner = findViewById(R.id.captureProfileSpinner)
        captureModeSpinner = findViewById(R.id.captureModeSpinner)
        transportSpinner = findViewById(R.id.transportSpinner)
        hostInput = findViewById(R.id.hostInput)
        micCheckbox = findViewById(R.id.micCheckbox)
        backgroundStreamingCheckbox = findViewById(R.id.backgroundStreamingCheckbox)
        sleepProtectionCheckbox = findViewById(R.id.sleepProtectionCheckbox)
        debugModeCheckbox = findViewById(R.id.debugModeCheckbox)
        torchCheckbox = findViewById(R.id.torchCheckbox)
        toggleUiButton = findViewById(R.id.toggleUiButton)

        val previewView = requireNotNull(idlePreviewView)
        previewController = PreviewController(
            context = this,
            lifecycleOwner = this,
            previewView = previewView,
            onStateChanged = { state, detail -> runOnUiThread { renderPreviewState(state, detail) } },
            onDebugEvent = { msg -> runOnUiThread { if (debugModeEnabled) appendDebugLogLine(msg) } },
        )
        controller = CameraController(
            context = this,
            onDebugEvent = { msg -> runOnUiThread { if (debugModeEnabled) appendDebugLogLine(msg) } },
            onStreamStateChanged = { state, detail -> runOnUiThread { renderStreamState(state, detail) } },
        )

        val start = findViewById<Button>(R.id.startButton)
        val stop = findViewById<Button>(R.id.stopButton)
        val orientationButton = findViewById<Button>(R.id.orientationButton)
        val controlsPanel = findViewById<View>(R.id.controlsPanel)
        val debugOverlayCloseButton = findViewById<Button>(R.id.debugOverlayCloseButton)
        val debugOverlayClearButton = findViewById<Button>(R.id.debugOverlayClearButton)

        val transportOptions = listOf("USB ADB", "USB Native", "USB AOA", "Wi-Fi")
        modeOptions = listOf(
            CaptureModeOption(CaptureModePreset.LATENCY, getString(R.string.capture_mode_latency)),
            CaptureModeOption(CaptureModePreset.BALANCED, getString(R.string.capture_mode_balanced)),
            CaptureModeOption(CaptureModePreset.LOW_LIGHT, getString(R.string.capture_mode_low_light)),
        )

        availableProfiles = CameraSurfacePipeline.enumerateSupportedProfiles(this)
        transportSpinner?.adapter = buildSpinnerAdapter(transportOptions)
        captureProfileSpinner?.adapter = buildSpinnerAdapter(availableProfiles)
        captureModeSpinner?.adapter = buildSpinnerAdapter(modeOptions)
        val defaultProfile = CameraSurfacePipeline.chooseProfileForRequest(this, 1280, 720, 60)
        val defaultProfileIndex = availableProfiles.indexOfFirst { it.id == defaultProfile?.id }.let { if (it < 0) 0 else it }
        if (availableProfiles.isNotEmpty()) {
            captureProfileSpinner?.setSelection(defaultProfileIndex)
        }
        captureModeSpinner?.setSelection(1)
        micCheckbox?.isChecked = true
        sleepProtectionCheckbox?.isChecked = keepScreenOnWhileStreaming
        backgroundStreamingCheckbox?.isChecked = backgroundStreamingEnabled
        debugModeCheckbox?.isChecked = debugModeEnabled
        torchCheckbox?.isChecked = false

        renderPreviewState(PreviewUiState.IDLE, getString(R.string.preview_hint_default))
        renderStreamState(StreamUiState.STOPPED, getString(R.string.stream_detail_default))
        if (availableProfiles.isNotEmpty()) {
            updateActualProfileText(currentCaptureSpec())
        } else {
            renderPreviewState(PreviewUiState.ERROR, getString(R.string.preview_no_profiles))
            renderStreamState(StreamUiState.ERROR, getString(R.string.preview_no_profiles))
            start.isEnabled = false
        }
        updateTorchAvailability()
        updateOrientationButtonText(orientationButton)
        applyControlsVisibility(controlsPanel, toggleUiButton ?: orientationButton)
        applyDebugModeUi()
        showPreviewSurfaces(idleVisible = true, streamVisible = false)
        if (debugModeEnabled) {
            appendDebugLogLine("file logs: ${AppLog.getLogDirPath()}")
        }

        ensurePermissions()
        registerReceiver(usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(aoaEntryReceiver, IntentFilter(ACTION_ENTER_AOA), Context.RECEIVER_EXPORTED)
        if (intent?.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            handleUsbAccessoryIntent(intent)
        }
        probeExistingUsbAccessory("activity_create", requestPermissionIfNeeded = false)

        sleepProtectionCheckbox?.setOnCheckedChangeListener { _, checked ->
            keepScreenOnWhileStreaming = checked
            persistFlags()
            applyKeepScreenOnFlag()
        }
        backgroundStreamingCheckbox?.setOnCheckedChangeListener { _, checked ->
            backgroundStreamingEnabled = checked
            persistFlags()
        }
        debugModeCheckbox?.setOnCheckedChangeListener { _, checked ->
            debugModeEnabled = checked
            persistFlags()
            applyDebugModeUi()
            if (checked) {
                appendDebugLogLine("debug mode enabled")
                appendDebugLogLine("file logs: ${AppLog.getLogDirPath()}")
            }
        }
        torchCheckbox?.setOnCheckedChangeListener { _, _ ->
            if (!suppressSelectionCallbacks) {
                updateTorchAvailability()
                updateActualProfileText(currentCaptureSpec())
                if (!isStreaming) {
                    refreshPreviewSpec("torch_toggle")
                }
            }
        }

        debugOverlayCloseButton.setOnClickListener {
            debugModeEnabled = false
            debugModeCheckbox?.isChecked = false
            persistFlags()
            applyDebugModeUi()
        }
        debugOverlayClearButton.setOnClickListener {
            debugLogTextView?.text = ""
        }

        orientationButton.setOnClickListener {
            isLandscapeLocked = !isLandscapeLocked
            persistFlags()
            applyOrientationLock()
            updateOrientationButtonText(orientationButton)
            previewController.rebindForRotation("orientation_toggle")
        }
        toggleUiButton?.setOnClickListener {
            controlsVisible = !controlsVisible
            persistFlags()
            applyControlsVisibility(controlsPanel, toggleUiButton ?: orientationButton)
        }

        val selectionListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSelectionCallbacks) return
                updateTorchAvailability()
                updateActualProfileText(currentCaptureSpec())
                refreshPreviewSpec("selection_changed")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        captureProfileSpinner?.onItemSelectedListener = selectionListener
        captureModeSpinner?.onItemSelectedListener = selectionListener
        suppressSelectionCallbacks = false

        start.setOnClickListener {
            ensurePermissions()
            val mode = currentTransportMode()
            val spec = currentCaptureSpec()
            val receiver = currentReceiverForMode(mode)
            val actualProfile = runCatching { CameraSurfacePipeline.prepareCaptureProfile(this, spec) }.getOrNull()

            if (mode == CameraController.TransportMode.USB_AOA) {
                if (backgroundStreamingEnabled) {
                    releaseUsbAccessoryTransport("handoff_to_service")
                } else {
                    probeExistingUsbAccessory("start_button")
                }
            }

            previewController.stop("stream_start")
            showPreviewSurfaces(idleVisible = false, streamVisible = !backgroundStreamingEnabled)
            isStreaming = true
            if (actualProfile != null) {
                updateActualProfileText(actualProfile.actualLabel, spec.mode, actualProfile.torchEnabled)
            } else {
                updateActualProfileText(spec)
            }
            renderPreviewState(
                if (backgroundStreamingEnabled) PreviewUiState.PAUSED else PreviewUiState.READY,
                if (backgroundStreamingEnabled) getString(R.string.preview_hint_background) else getString(R.string.preview_hint_streaming_live),
            )
            renderStreamState(StreamUiState.CONNECTING, "Starting ${spec.displayLabel}")

            if (backgroundStreamingEnabled) {
                controller.stop()
                startBackgroundStreaming(mode, receiver, spec, micCheckbox?.isChecked == true)
            } else {
                stopBackgroundStreaming()
                controller.start(
                    transport = mode,
                    receiverAddress = receiver,
                    captureSpec = spec,
                    streamPreviewView = streamPreviewView,
                    pushMic = micCheckbox?.isChecked == true,
                )
            }
            applyKeepScreenOnFlag()
        }

        stop.setOnClickListener {
            controller.stop()
            releaseUsbAccessoryTransport("stop_button")
            stopBackgroundStreaming()
            isStreaming = false
            renderStreamState(StreamUiState.STOPPED, "Stream stopped")
            bindPreviewIfPossible("stop_button")
            applyKeepScreenOnFlag()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            handleUsbAccessoryIntent(intent)
        }
    }

    override fun onDestroy() {
        previewController.stop("activity_destroy")
        controller.stop()
        releaseUsbAccessoryTransport("activity_destroy")
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(aoaEntryReceiver)
        } catch (_: Exception) {
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        val filter = IntentFilter(StreamingService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamingStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(streamingStatusReceiver, filter)
        }
        bindPreviewIfPossible("activity_start")
    }

    override fun onStop() {
        isActivityVisible = false
        if (!isStreaming) {
            previewController.stop("activity_stop")
        }
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
        super.onSaveInstanceState(outState)
    }

    private fun bindPreviewIfPossible(reason: String) {
        if (!isActivityVisible) return
        if (isStreaming) {
            showPreviewSurfaces(idleVisible = false, streamVisible = !backgroundStreamingEnabled)
            previewDetailTextView?.text =
                if (backgroundStreamingEnabled) getString(R.string.preview_hint_background) else getString(R.string.preview_hint_streaming_live)
            return
        }
        if (!hasCameraPermission()) {
            renderPreviewState(PreviewUiState.ERROR, getString(R.string.preview_permission_required))
            return
        }
        if (availableProfiles.isEmpty()) {
            renderPreviewState(PreviewUiState.ERROR, getString(R.string.preview_no_profiles))
            return
        }
        val spec = currentCaptureSpec()
        showPreviewSurfaces(idleVisible = true, streamVisible = false)
        updateTorchAvailability()
        updateActualProfileText(spec)
        previewController.updateSpec(spec, "spec_$reason")
        previewController.start(reason)
    }

    private fun refreshPreviewSpec(reason: String) {
        if (!isStreaming) {
            bindPreviewIfPossible(reason)
        }
    }

    private fun currentCaptureSpec(): CaptureSpec {
        val profile = availableProfiles.getOrNull(captureProfileSpinner?.selectedItemPosition ?: 0)
            ?: CameraSurfacePipeline.chooseProfileForRequest(this, 1280, 720, 30)
            ?: throw IllegalStateException("No capture profiles available")
        val mode = modeOptions.getOrNull(captureModeSpinner?.selectedItemPosition ?: 1)?.preset
            ?: CaptureModePreset.BALANCED
        val torchEnabled = torchCheckbox?.isChecked == true && profile.supportsTorch
        return CaptureSpec(profile = profile, mode = mode, torchEnabled = torchEnabled)
    }

    private fun currentTransportMode(): CameraController.TransportMode {
        return when (transportSpinner?.selectedItemPosition ?: 0) {
            0 -> CameraController.TransportMode.USB_ADB
            1 -> CameraController.TransportMode.USB_NATIVE
            2 -> CameraController.TransportMode.USB_AOA
            else -> CameraController.TransportMode.LAN
        }
    }

    private fun currentReceiverForMode(mode: CameraController.TransportMode): String {
        val input = hostInput?.text?.toString()?.trim().orEmpty()
        return input.ifBlank {
            when (mode) {
                CameraController.TransportMode.USB_ADB -> "127.0.0.1:39393"
                CameraController.TransportMode.USB_NATIVE -> ""
                CameraController.TransportMode.USB_AOA -> ""
                CameraController.TransportMode.LAN -> "192.168.1.100:39393"
            }
        }
    }

    private fun renderPreviewState(state: PreviewUiState, detail: String) {
        previewStateTextView?.text = when (state) {
            PreviewUiState.IDLE -> getString(R.string.preview_state_idle)
            PreviewUiState.STARTING -> getString(R.string.preview_state_starting)
            PreviewUiState.READY -> getString(R.string.preview_state_ready)
            PreviewUiState.PAUSED -> getString(R.string.preview_state_paused)
            PreviewUiState.ERROR -> getString(R.string.preview_state_error)
        }
        previewDetailTextView?.text = detail
    }

    private fun renderStreamState(state: StreamUiState, detail: String) {
        streamStateTextView?.text = when (state) {
            StreamUiState.IDLE -> getString(R.string.stream_state_idle)
            StreamUiState.CONNECTING -> getString(R.string.stream_state_connecting)
            StreamUiState.STREAMING -> getString(R.string.stream_state_streaming)
            StreamUiState.STOPPED -> getString(R.string.stream_state_stopped)
            StreamUiState.ERROR -> getString(R.string.stream_state_error)
        }
        statusTextView?.text = detail
    }

    private fun updateActualProfileText(spec: CaptureSpec) {
        val actualProfile = runCatching { CameraSurfacePipeline.prepareCaptureProfile(this, spec) }.getOrNull()
        if (actualProfile != null) {
            updateActualProfileText(actualProfile.actualLabel, spec.mode, actualProfile.torchEnabled)
        } else {
            updateActualProfileText(spec.profile.displayLabel, spec.mode, spec.torchEnabled)
        }
    }

    private fun updateActualProfileText(profileLabel: String, mode: CaptureModePreset, torchEnabled: Boolean) {
        actualProfileTextView?.text = getString(
            R.string.actual_profile_format,
            profileLabel,
            when (mode) {
                CaptureModePreset.LATENCY -> getString(R.string.capture_mode_latency)
                CaptureModePreset.BALANCED -> getString(R.string.capture_mode_balanced)
                CaptureModePreset.LOW_LIGHT -> getString(R.string.capture_mode_low_light)
            },
            if (torchEnabled) getString(R.string.torch_state_on) else getString(R.string.torch_state_off),
        )
    }

    private fun updateTorchAvailability() {
        val profile = availableProfiles.getOrNull(captureProfileSpinner?.selectedItemPosition ?: 0)
        val available = profile?.supportsTorch == true
        torchCheckbox?.isEnabled = available
        if (!available) {
            suppressSelectionCallbacks = true
            torchCheckbox?.isChecked = false
            suppressSelectionCallbacks = false
        }
    }

    private fun showPreviewSurfaces(idleVisible: Boolean, streamVisible: Boolean) {
        idlePreviewView?.visibility = if (idleVisible) View.VISIBLE else View.GONE
        streamPreviewView?.visibility = if (streamVisible) View.VISIBLE else View.GONE
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
            .apply()
    }

    private fun startBackgroundStreaming(
        transport: CameraController.TransportMode,
        receiver: String,
        spec: CaptureSpec,
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
            putExtra(StreamingService.EXTRA_WIDTH, spec.profile.width)
            putExtra(StreamingService.EXTRA_HEIGHT, spec.profile.height)
            putExtra(StreamingService.EXTRA_FPS, spec.profile.targetFps)
            putExtra(StreamingService.EXTRA_BITRATE, spec.profile.recommendedBitrate)
            putExtra(StreamingService.EXTRA_PUSH_MIC, pushMic)
            putExtra(StreamingService.EXTRA_CAPTURE_MODE, spec.mode.name)
            putExtra(StreamingService.EXTRA_TORCH_ENABLED, spec.torchEnabled)
            putExtra(StreamingService.EXTRA_CAMERA_ID, spec.profile.cameraId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopBackgroundStreaming() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
    }

    private fun applyControlsVisibility(panel: View, button: Button) {
        panel.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        button.text = if (controlsVisible) getString(R.string.btn_hide_controls) else getString(R.string.btn_show_controls)
    }

    private fun updateOrientationButtonText(button: Button) {
        button.text = if (isLandscapeLocked) getString(R.string.btn_orientation_landscape_on) else getString(R.string.btn_orientation_landscape_off)
    }

    private fun appendDebugLogLine(message: String) {
        val tv = debugLogTextView ?: return
        val line = "[${timeFmt.format(Date())}] $message"
        val prev = tv.text?.toString().orEmpty()
        tv.text = if (prev.isBlank()) line else "$prev\n$line"
        debugLogScrollView?.post { debugLogScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun applyDebugModeUi() {
        debugOverlayView?.visibility = if (debugModeEnabled) View.VISIBLE else View.GONE
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && accessory != null) {
                    openUsbAccessory(accessory, "permission_granted")
                } else {
                    AppLog.w("MainActivity", "USB accessory permission denied")
                }
            }
        }
    }

    private val aoaEntryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_ENTER_AOA) {
                AppLog.i("MainActivity", "Received ACTION_ENTER_AOA from ADB")
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val accessories = usbManager.accessoryList
                if (accessories != null && accessories.isNotEmpty()) {
                    handleUsbAccessoryIntent(Intent().apply {
                        putExtra(UsbManager.EXTRA_ACCESSORY, accessories[0])
                    })
                } else {
                    AppLog.w("MainActivity", "No USB accessory available for AOA entry")
                }
            }
        }
    }

    private fun handleUsbAccessoryIntent(intent: Intent) {
        val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
        if (accessory == null) return
        handleUsbAccessory(accessory, "intent")
    }

    private fun probeExistingUsbAccessory(reason: String, requestPermissionIfNeeded: Boolean = true): Boolean {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val accessory = usbManager.accessoryList?.firstOrNull() ?: return false
        if (!requestPermissionIfNeeded && !usbManager.hasPermission(accessory)) {
            return false
        }
        handleUsbAccessory(accessory, reason)
        return true
    }

    private fun handleUsbAccessory(accessory: UsbAccessory, reason: String) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val summary = accessorySummary(accessory)
        if (usbManager.hasPermission(accessory)) {
            AppLog.i("MainActivity", "USB accessory available reason=$reason $summary")
            openUsbAccessory(accessory, reason)
        } else {
            pendingAccessory = accessory
            AppLog.i("MainActivity", "Requesting USB accessory permission reason=$reason $summary")
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(accessory, pi)
        }
    }

    private fun openUsbAccessory(accessory: UsbAccessory, reason: String) {
        val existing = usbAccessoryTransport
        if (existing?.isConnected() == true) {
            controller.attachUsbAccessoryTransport(existing)
            AppLog.i("MainActivity", "Reusing USB AOA accessory connection reason=$reason ${accessorySummary(accessory)}")
            return
        }
        releaseUsbAccessoryTransport("reopen_for_$reason")
        val transport = UsbAccessoryTransport(this) { msg ->
            runOnUiThread {
                if (debugModeEnabled) appendDebugLogLine(msg)
            }
        }
        if (transport.open(accessory)) {
            usbAccessoryTransport = transport
            controller.attachUsbAccessoryTransport(transport)
            AppLog.i("MainActivity", "USB AOA accessory opened successfully reason=$reason ${accessorySummary(accessory)}")
        } else {
            AppLog.e("MainActivity", "Failed to open USB accessory reason=$reason ${accessorySummary(accessory)}")
        }
    }

    private fun releaseUsbAccessoryTransport(reason: String) {
        controller.attachUsbAccessoryTransport(null)
        val transport = usbAccessoryTransport ?: return
        usbAccessoryTransport = null
        try {
            transport.close()
        } catch (_: Throwable) {
        }
        AppLog.i("MainActivity", "Released USB AOA accessory transport reason=$reason")
    }

    private fun accessorySummary(accessory: UsbAccessory): String {
        val manufacturer = accessory.manufacturer?.takeIf { it.isNotBlank() } ?: "unknown"
        val model = accessory.model?.takeIf { it.isNotBlank() } ?: "unknown"
        val version = accessory.version?.takeIf { it.isNotBlank() } ?: "unknown"
        return "manufacturer=$manufacturer model=$model version=$version"
    }

    private fun ensurePermissions() {
        val needCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val needAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needCamera || needAudio) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun <T> buildSpinnerAdapter(items: List<T>): ArrayAdapter<T> {
        return ArrayAdapter(this, R.layout.spinner_item, items).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
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
    }
}
