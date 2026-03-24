package com.acb.androidcam

data class SupportedCaptureProfile(
    val id: String,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val recommendedBitrate: Int,
    val supportsTorch: Boolean,
    val deliveryMode: CaptureDeliveryMode = CaptureDeliveryMode.STANDARD,
) {
    val aspectRatio: Float
        get() = if (height <= 0) 16f / 9f else width.toFloat() / height.toFloat()

    val displayLabel: String
        get() = buildString {
            append("${width}x${height} @ ${targetFps} FPS")
            if (deliveryMode == CaptureDeliveryMode.CONSTRAINED_HIGH_SPEED) {
                append(" (HS)")
            }
        }

    override fun toString(): String = displayLabel
}

enum class CaptureDeliveryMode {
    STANDARD,
    CONSTRAINED_HIGH_SPEED,
}

enum class CaptureModePreset {
    LATENCY,
    BALANCED,
    LOW_LIGHT,
}

data class CaptureSpec(
    val profile: SupportedCaptureProfile,
    val mode: CaptureModePreset = CaptureModePreset.BALANCED,
    val torchEnabled: Boolean = false,
) {
    val requestedWidth: Int
        get() = profile.width
    val requestedHeight: Int
        get() = profile.height
    val requestedFps: Int
        get() = profile.targetFps
    val preferredBitrate: Int
        get() = profile.recommendedBitrate
    val preferredCameraId: String
        get() = profile.cameraId
    val displayLabel: String
        get() = profile.displayLabel
}

enum class PreviewUiState {
    IDLE,
    STARTING,
    READY,
    PAUSED,
    ERROR,
}

enum class StreamUiState {
    IDLE,
    CONNECTING,
    STREAMING,
    STOPPED,
    ERROR,
}
