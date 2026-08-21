package compressedge.joshattic.us.model

import android.net.Uri
import androidx.media3.common.MimeTypes
import compressedge.joshattic.us.BuildConfig
import compressedge.joshattic.us.utils.formatFileSize
import java.util.Locale

sealed class FilenameSegment {
    data class Text(val value: String) : FilenameSegment()
    data class Token(val key: String) : FilenameSegment()
}

val defaultTargetSizePresets = listOf(
    TargetSizePreset("github", 10f, "GitHub"),
    TargetSizePreset("discord", 20f, "Discord"),
    TargetSizePreset("email", 25f, "Email"),
    TargetSizePreset("stories", 50f, "Stories • Nitro Basic"),
    TargetSizePreset("messenger", 100f, "Messenger • BlueSky"),
    TargetSizePreset("nitro", 500f, "Nitro • Reels"),
    TargetSizePreset("twitter", 512f, "Twitter/X"),
    TargetSizePreset("whatsapp", 2048f, "WhatsApp • Telegram"),
    TargetSizePreset("tg_premium", 4096f, "TG Premium • Feed"),
    TargetSizePreset("x_premium", 8192f, "X Premium")
)

data class CompressorUiState(
    val selectedUri: Uri? = null,
    val originalSize: Long = 0L,
    val originalWidth: Int = 0,
    val originalHeight: Int = 0,
    val originalBitrate: Int = 0,
    val originalAudioBitrate: Int = 0,
    val originalFps: Float = 30f,
    val originalVideoMime: String? = null,
    val durationMs: Long = 0L,
    val originalName: String? = null,
    
    val isCompressing: Boolean = false,
    val isBackgroundCompression: Boolean = false,
    val progress: Float = 0f,
    val compressedUri: Uri? = null,
    val compressedUris: List<Uri> = emptyList(),
    val compressedSize: Long = 0L,
    val currentOutputSize: Long = 0L,
    val error: String? = null,
    val errorLog: String? = null,
    val saveSuccess: Boolean = false,
    val isSaving: Boolean = false,
    
    // Batch Mode
    val isBatchMode: Boolean = false,
    val queue: List<QueueItem> = emptyList(),
    val globalTargetSizePercentage: Float = 70f,
    val currentlyCompressingIndex: Int = 0,
    val currentlyCompressingUri: Uri? = null,
    
    // Configuration
    val activePreset: QualityPreset = QualityPreset.CUSTOM,
    val targetSizeMb: Float = 10f,
    val useH265: Boolean = true,
    val videoCodec: String = MimeTypes.VIDEO_H265,
    val targetResolutionHeight: Int = 0, // 0 means original
    val targetFps: Int = 0, // 0 means original
    val audioBitrateLocked: Boolean = false,
    val targetResolutionLocked: Boolean = false,
    val targetFpsLocked: Boolean = false,
    
    val totalSavedBytes: Long = 0L,
    
    val supportedCodecs: List<String> = emptyList(),
    val appInfoVersion: String = BuildConfig.VERSION_NAME,
    val showWhatsNewDialog: Boolean = false,
    val showBitrate: Boolean = false,
    val useMbps: Boolean = false,
    val showStorageSaved: Boolean = true,
    val showTargetSizePreset: Boolean = true,
    /** Only meaningful on Android 10+; forced off on older versions. */
    val autoSaveToPhotos: Boolean = false,
    val backgroundCompressionEnabled: Boolean = false,
    val backgroundCompressionPrompted: Boolean = false,
    val customOutputTreeUri: String? = null,
    val customOutputFolderName: String? = null,
    val hasShared: Boolean = false,
    val removeAudio: Boolean = false,
    val audioCodec: String = MimeTypes.AUDIO_AAC,
    val supportedAudioCodecs: List<String> = listOf(MimeTypes.AUDIO_AAC),
    val audioBitrate: Int = 128_000,
    val audioVolume: Float = 1.0f,
    val warnings: List<String> = emptyList(),
    val allCodecsEnabled: Boolean = false,
    val allCodecsUnlocked: Boolean = true,
    val highPresetConfig: QualityPresetConfig = QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000),
    val mediumPresetConfig: QualityPresetConfig = QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000),
    val lowPresetConfig: QualityPresetConfig = QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000),
    val targetSizePresets: List<TargetSizePreset> = defaultTargetSizePresets,
    val defaultVideoConfig: DefaultVideoConfig = DefaultVideoConfig(),
    val defaultAudioConfig: DefaultAudioConfig = DefaultAudioConfig(),
    val filenameSegments: List<FilenameSegment> = listOf(
        FilenameSegment.Text(""),
        FilenameSegment.Token("original_name"),
        FilenameSegment.Text(""),
        FilenameSegment.Token("compressed"),
        FilenameSegment.Text("")
    )
) {
    val targetSizeWarning: Boolean
        get() = if (isBatchMode) false else (durationMs > 0 && targetSizeMb < minimumSizeMb)

    val maxOriginalShortSide: Int
        get() {
            if (!isBatchMode) {
                return if (originalWidth > 0 && originalHeight > 0) Math.min(originalWidth, originalHeight) else 0
            }
            return queue.maxOfOrNull {
                if (it.originalWidth > 0 && it.originalHeight > 0) Math.min(it.originalWidth, it.originalHeight) else 0
            } ?: 0
        }

    val maxOriginalFps: Float
        get() {
            if (!isBatchMode) return originalFps
            return queue.maxOfOrNull { it.originalFps } ?: 30f
        }

    val maxOriginalAudioBitrate: Int
        get() {
            if (!isBatchMode) return originalAudioBitrate
            return queue.maxOfOrNull { it.originalAudioBitrate } ?: 0
        }

    /** Returns the settings autoAdjust would choose if user locks were released. */
    fun suggestedForTarget(): CompressorUiState {
        var suggestion = copy(
            audioBitrateLocked = false,
            targetResolutionLocked = false,
            targetFpsLocked = false
        )

        // Dropping to 30fps often preserves more spatial detail than dropping
        // to the next resolution tier, especially for portrait video.
        val currentFps = if (suggestion.targetFps > 0) suggestion.targetFps else suggestion.originalFps.toInt()
        if (currentFps > 30) {
            suggestion = suggestion.copy(targetFps = 30)
        }

        return suggestion.autoAdjust(targetSizeMb, lockAudioBitrate = false, allowUpward = false)
    }

    private val minBitrate: Long
        get() = calculateMinBitrate(
            targetResolutionHeight, 
            originalWidth,
            originalHeight, 
            videoCodec, 
            if (targetFps > 0) targetFps.toFloat() else originalFps
        )

    private fun calculateMinBitrate(targetShortSide: Int, origWidth: Int, origHeight: Int, codec: String, fps: Float): Long {
        val origShort = if (origWidth > 0 && origHeight > 0) minOf(origWidth, origHeight) else origHeight
        val h = if (targetShortSide > 0 && origShort > 0) minOf(targetShortSide, origShort) else if (targetShortSide > 0) targetShortSide else origShort
        var base = when {
            h >= 2160 -> 4_000_000L
            h >= 1440 -> 2_500_000L
            h >= 1080 -> 1_500_000L
            h >= 720 -> 1_000_000L
            h >= 480 -> 500_000L
            h >= 360 -> 350_000L
            else -> 200_000L
        }
        
        if (codec == MimeTypes.VIDEO_H265) {
            base = (base * 0.7).toLong()
        } else if (codec == MimeTypes.VIDEO_AV1) {
            base = (base * 0.6).toLong()
        }
        
        val multiplier = if (fps > 45) 1.5f else 1.0f
        return (base * multiplier).toLong()
    }

    val minimumSizeMb: Float
        get() {
            if (isBatchMode) {
                // Approximate minimum size for the whole batch
                var totalMin = 0f
                for (item in queue) {
                    val seconds = item.durationMs / 1000f
                    if (seconds <= 0) continue
                    val audioBits = if (item.removeAudioOverride ?: removeAudio) 0f else {
                        val rate = if (audioBitrate == 0) 256_000f else audioBitrate.toFloat() // Using global for simplicity here
                        rate * seconds
                    }
                    val itemMinBitrate = calculateMinBitrate(
                        item.targetResolutionHeightOverride ?: targetResolutionHeight,
                        item.originalWidth,
                        item.originalHeight,
                        videoCodec,
                        (item.targetFpsOverride ?: targetFps).toFloat().takeIf { it > 0 } ?: item.originalFps
                    )
                    val minBits = itemMinBitrate * seconds
                    totalMin += (minBits + audioBits) / 8f / (1024f * 1024f)
                }
                return totalMin.coerceAtLeast(0.1f)
            }
            if (durationMs <= 0) return 0.1f
            val seconds = durationMs / 1000f
            val audioBits = if (removeAudio) 0f else {
                val rate = if (audioBitrate == 0) 256_000f else audioBitrate.toFloat()
                rate * seconds
            }
            val minBits = minBitrate * seconds
            val totalBits = minBits + audioBits
            val minMb = (totalBits / 8f) / (1024f * 1024f)
            return minMb
        }

    val estimatedSize: String
        get() {
            if (isBatchMode) {
                var totalEstimated = 0f
                for (item in queue) {
                    val itemOriginal = item.originalSize / (1024f * 1024f)
                    // If targetSizePercentageOverride is 0..100, we use it. Otherwise fallback to globalTargetSizePercentage.
                    val pct = item.targetSizePercentageOverride ?: globalTargetSizePercentage
                    val target = (itemOriginal * (pct / 100f)).coerceAtLeast(0.1f)
                    
                    // We need item's minimumSizeMb, approximated
                    val seconds = item.durationMs / 1000f
                    val audioBits = if (item.removeAudioOverride ?: removeAudio) 0f else {
                        val rate = if (audioBitrate == 0) 256_000f else audioBitrate.toFloat()
                        rate * seconds
                    }
                    val itemMinBitrate = calculateMinBitrate(
                        item.targetResolutionHeightOverride ?: targetResolutionHeight,
                        item.originalWidth,
                        item.originalHeight,
                        videoCodec,
                        (item.targetFpsOverride ?: targetFps).toFloat().takeIf { it > 0 } ?: item.originalFps
                    )
                    val minBits = itemMinBitrate * seconds
                    val itemMinMb = ((minBits + audioBits) / 8f / (1024f * 1024f)).coerceAtLeast(0.1f)
                    
                    totalEstimated += target.coerceAtLeast(itemMinMb)
                }
                return String.format(Locale.US, "%.1f MB", totalEstimated)
            }
            val actualTarget = targetSizeMb.coerceAtLeast(minimumSizeMb)
            return String.format(Locale.US, "%.1f MB", actualTarget)
        }
    
    val targetBitrate: Int
        get() {
             val durationSec = if (durationMs > 0) durationMs / 1000.0 else 0.0
             if (durationSec <= 0) return 2_000_000
             
             val targetBits = targetSizeMb * 8 * 1024 * 1024
             
             val audioBits = if (removeAudio) 0.0 else {
                 val rate = if (audioBitrate == 0) 256_000.0 else audioBitrate.toDouble()
                 rate * durationSec
             }
             
             val overheadBits = (targetBits * 0.02) + (50 * 1024 * 8)
             
             var availableVideoBits = targetBits - audioBits - overheadBits
             
             availableVideoBits = availableVideoBits.coerceAtLeast(targetBits * 0.1) 
             
             val calculated = (availableVideoBits / durationSec).toLong()
             
             val original = if (originalBitrate > 0) originalBitrate.toLong() else Long.MAX_VALUE
             val final = calculated.coerceAtLeast(minBitrate).coerceAtMost(original)
             return final.toInt()
        }

    val formattedBitrate: String
        get() {
            if (!showBitrate) return ""
            return if (useMbps) {
                String.format(Locale.US, "%.1f Mbps", targetBitrate / 1_000_000f)
            } else {
                "${targetBitrate / 1000} kbps"
            }
        }

    val formattedOriginalBitrate: String
        get() {
            if (!showBitrate) return ""
            if (originalBitrate <= 0) return ""
            return if (useMbps) {
                String.format(Locale.US, "%.1f Mbps", originalBitrate / 1_000_000f)
            } else {
                "${originalBitrate / 1000} kbps"
            }
        }
        
    val formattedTotalSaved: String
        get() = formatFileSize(totalSavedBytes)

    val totalOriginalSizeBatch: Long
        get() = if (isBatchMode) queue.sumOf { it.originalSize } else originalSize

    val formattedOriginalSize: String
        get() = formatFileSize(totalOriginalSizeBatch)
        
    val formattedCompressedSize: String
        get() = formatFileSize(compressedSize)
        
    val formattedCurrentOutputSize: String
        get() = formatFileSize(currentOutputSize)

    fun autoAdjust(targetMb: Float, lockAudioBitrate: Boolean = false, allowUpward: Boolean = true): CompressorUiState {
        if (isBatchMode) return this
        var state = this
        val audioLocked = lockAudioBitrate || audioBitrateLocked
        val incomingAudioBitrate = audioBitrate
        val origShort = if (originalWidth > 0 && originalHeight > 0) minOf(originalWidth, originalHeight) else originalHeight

        var attempts = 0
        val maxAttempts = 20

        // Downward adjustment (Reduce quality to fit target)
        while (state.minimumSizeMb > targetMb && attempts < maxAttempts) {
            attempts++
            
            if (!audioLocked) {
                // 1. Reduce Audio Bitrate to 128k
                if (state.audioBitrate > 128_000) {
                    state = state.copy(audioBitrate = 128_000)
                    continue
                }
            }

            // Prefer reducing framerate over spatial resolution.
            val effectiveFps = if (state.targetFps > 0) state.targetFps else state.originalFps.toInt()

            // 2. Reduce FPS to 30 if higher
            if (!targetFpsLocked && effectiveFps > 30) {
                state = state.copy(targetFps = 30)
                continue
            }

            // 3. Reduce Resolution
            val currentShort = if (state.targetResolutionHeight > 0) state.targetResolutionHeight else origShort
            val newShortSide = when {
                currentShort > 2160 -> 2160
                currentShort > 1440 -> 1440
                currentShort > 1080 -> 1080
                currentShort > 720 -> 720
                currentShort > 480 -> 480
                currentShort > 360 -> 360
                else -> 240
            }
            
            if (!targetResolutionLocked && newShortSide < currentShort) {
                state = state.copy(targetResolutionHeight = newShortSide)
                continue
            }

            if (!audioLocked) {
                // 4. Reduce Audio Bitrate to 96k if really needed (big gap)
                if (state.audioBitrate > 96_000 && state.minimumSizeMb > targetMb * 1.5) {
                    state = state.copy(audioBitrate = 96_000)
                    continue
                }
            }

            // 5. Reduce FPS to 24 only after reaching the lowest resolution.
            if (!targetFpsLocked && effectiveFps > 24) {
                 state = state.copy(targetFps = 24)
                 continue
            }
             
             break 
        }

        // Upward adjustment (Increase quality if we have headroom)
        if (allowUpward) {
            attempts = 0
            while (attempts < maxAttempts) {
                attempts++
                var changed = false
            
                // 1. Try to increase Resolution 
                val currentShort = if (state.targetResolutionHeight > 0) state.targetResolutionHeight else origShort
                if (!state.targetResolutionLocked && currentShort < origShort) {
                    val nextShortSide = when {
                        currentShort < 360 -> 360
                        currentShort < 480 -> 480
                        currentShort < 720 -> 720
                        currentShort < 1080 -> 1080
                        currentShort < 1440 -> 1440
                        currentShort < 2160 -> 2160
                        else -> origShort
                    }.coerceAtMost(origShort)

                    val useOriginal = nextShortSide >= origShort
                    val testState = state.copy(targetResolutionHeight = if (useOriginal) 0 else nextShortSide) 
                    
                    if (testState.minimumSizeMb <= targetMb) {
                        state = testState
                        changed = true
                        continue
                    }
                }
                
                // 2. Try to increase FPS
                val currentFps = if (state.targetFps > 0) state.targetFps else state.originalFps.toInt()
                if (!state.targetFpsLocked && currentFps < state.originalFps.toInt()) {
                     val nextFps = if (currentFps < 30) 30 else state.originalFps.toInt()
                     val useOriginal = nextFps >= state.originalFps.toInt()
                     val testState = state.copy(targetFps = if (useOriginal) 0 else nextFps)
                     
                     if (testState.minimumSizeMb <= targetMb) {
                        state = testState
                        changed = true
                        continue
                     }
                }
                
                if (!audioLocked) {
                    // 3. Try to increase Audio Bitrate (never above the value entering this adjustment)
                    val maxAudio = if (incomingAudioBitrate > 0) {
                        incomingAudioBitrate
                    } else if (state.originalAudioBitrate > 0) {
                        state.originalAudioBitrate
                    } else {
                        320_000
                    }
                    if (state.audioBitrate < maxAudio) {
                        val nextAudio = when {
                            state.audioBitrate < 96_000 -> 96_000
                            state.audioBitrate < 128_000 -> 128_000
                            state.audioBitrate < 192_000 -> 192_000
                            state.audioBitrate < 320_000 -> 320_000
                            else -> maxAudio
                        }.coerceAtMost(maxAudio)
                        
                        val testState = state.copy(audioBitrate = nextAudio)
                        if (testState.minimumSizeMb <= targetMb) {
                            state = testState
                            changed = true
                            continue
                        }
                    }
                }

                if (!changed) break
            }
        }

        return state
    }
}
