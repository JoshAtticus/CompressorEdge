package compressedge.joshattic.us.model

import android.net.Uri

data class QueueItem(
    val uri: Uri,
    val originalSize: Long = 0L,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val originalBitrate: Int = 0,
    val originalAudioBitrate: Int = 0,
    val originalFps: Float = 30f,
    val originalVideoMime: String? = null,
    val durationMs: Long = 0L,
    val originalName: String? = null,
    
    // Status
    val isCompressing: Boolean = false,
    val isCompleted: Boolean = false,
    val progress: Float = 0f,
    val compressedUri: Uri? = null,
    val compressedSize: Long = 0L,
    val currentOutputSize: Long = 0L,
    val error: String? = null,

    // Specific Overrides (null means fallback to global state)
    val targetSizePercentageOverride: Float? = null,
    val targetResolutionHeightOverride: Int? = null,
    val targetFpsOverride: Int? = null,
    val audioBitrateOverride: Int? = null,
    val removeAudioOverride: Boolean? = null,
    val audioVolumeOverride: Float? = null
) {
    val isVertical: Boolean get() = originalHeight > originalWidth
}
