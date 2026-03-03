package com.acb.androidcam

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

data class ResolutionPreset(val label: String, val width: Int, val height: Int) {
    override fun toString(): String = label
}

class MainActivity : AppCompatActivity() {
    private lateinit var controller: CameraController
    private var isLandscapeLocked = false
    private var keepScreenOnWhileStreaming = true
    private var controlsVisible = true
    private var isStreaming = false

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
        controlsVisible = savedInstanceState?.getBoolean(KEY_CONTROLS_VISIBLE)
            ?: prefs.getBoolean(KEY_CONTROLS_VISIBLE, true)

        applyOrientationLock()

        val previewView = findViewById<PreviewView>(R.id.previewView)
        controller = CameraController(this, this, previewView)

        val status = findViewById<TextView>(R.id.statusText)
        val start = findViewById<Button>(R.id.startButton)
        val stop = findViewById<Button>(R.id.stopButton)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val transportSpinner = findViewById<Spinner>(R.id.transportSpinner)
        val resolutionSpinner = findViewById<Spinner>(R.id.resolutionSpinner)
        val micCheckbox = findViewById<CheckBox>(R.id.micCheckbox)
        val sleepProtectionCheckbox = findViewById<CheckBox>(R.id.sleepProtectionCheckbox)
        val orientationButton = findViewById<Button>(R.id.orientationButton)
        val controlsPanel = findViewById<View>(R.id.controlsPanel)
        val toggleUiButton = findViewById<Button>(R.id.toggleUiButton)

        val transportOptions = listOf("USB ADB", "Wi-Fi")
        val resolutions = listOf(
            ResolutionPreset("640x480", 640, 480),
            ResolutionPreset("1280x720", 1280, 720),
            ResolutionPreset("1920x1080", 1920, 1080)
        )

        transportSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transportOptions)
        resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        resolutionSpinner.setSelection(1)
        micCheckbox.isChecked = true
        sleepProtectionCheckbox.isChecked = keepScreenOnWhileStreaming
        updateOrientationButtonText(orientationButton)
        applyControlsVisibility(controlsPanel, toggleUiButton)

        ensurePermissions()

        sleepProtectionCheckbox.setOnCheckedChangeListener { _, checked ->
            keepScreenOnWhileStreaming = checked
            persistFlags()
            applyKeepScreenOnFlag()
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

            val mode = if (transportSpinner.selectedItemPosition == 0) {
                CameraController.TransportMode.USB_ADB
            } else {
                CameraController.TransportMode.LAN
            }

            val preset = resolutionSpinner.selectedItem as ResolutionPreset
            val receiver = hostInput.text.toString().ifBlank {
                if (mode == CameraController.TransportMode.USB_ADB) "127.0.0.1:39393" else "192.168.1.100:39393"
            }

            controller.start(
                transport = mode,
                receiverAddress = receiver,
                width = preset.width,
                height = preset.height,
                fps = 30,
                bitrate = 5_000_000,
                pushMic = micCheckbox.isChecked,
            )
            isStreaming = true
            applyKeepScreenOnFlag()
            status.text = getString(R.string.status_streaming, preset.label, receiver)
        }

        stop.setOnClickListener {
            controller.stop()
            isStreaming = false
            applyKeepScreenOnFlag()
            status.text = getString(R.string.status_stopped)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LANDSCAPE_LOCKED, isLandscapeLocked)
        outState.putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOnWhileStreaming)
        outState.putBoolean(KEY_CONTROLS_VISIBLE, controlsVisible)
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
            .putBoolean(KEY_CONTROLS_VISIBLE, controlsVisible)
            .apply()
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

    private fun ensurePermissions() {
        val needCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        val needAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED

        if (needCamera || needAudio) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    companion object {
        private const val PREFS_NAME = "acb_main"
        private const val KEY_LANDSCAPE_LOCKED = "landscape_locked"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_CONTROLS_VISIBLE = "controls_visible"
    }
}
