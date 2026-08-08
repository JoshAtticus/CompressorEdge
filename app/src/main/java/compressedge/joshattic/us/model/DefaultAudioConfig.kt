package compressedge.joshattic.us.model

import androidx.media3.common.MimeTypes

data class DefaultAudioConfig(
    val defaultAudioCodec: String = MimeTypes.AUDIO_AAC,
    val defaultAudioBitrate: Int = 128_000, // 128 kbps
    val defaultRemoveAudio: Boolean = false,
    val defaultAudioVolume: Float = 1.0f    // 100%
)
