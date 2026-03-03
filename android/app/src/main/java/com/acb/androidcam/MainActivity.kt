package com.acb.androidcam

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        isLandscapeLocked = savedInstanceState?.getBoolean(KEY_LANDSCAPE_LOCKED)
            ?: getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_LANDSCAPE_LOCKED, false)
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
        val orientationButton = findViewById<Button>(R.id.orientationButton)

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
        updateOrientationButtonText(orientationButton)

        ensurePermissions()

        orientationButton.setOnClickListener {
            isLandscapeLocked = !isLandscapeLocked
            persistOrientationLock()
            applyOrientationLock()
            updateOrientationButtonText(orientationButton)
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
            status.text = getString(R.string.status_streaming, preset.label, receiver)
        }

        stop.setOnClickListener {
            controller.stop()
            status.text = getString(R.string.status_stopped)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_LANDSCAPE_LOCKED, isLandscapeLocked)
        super.onSaveInstanceState(outState)
    }

    private fun applyOrientationLock() {
        requestedOrientation = if (isLandscapeLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun persistOrientationLock() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LANDSCAPE_LOCKED, isLandscapeLocked)
            .apply()
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
    }
}
