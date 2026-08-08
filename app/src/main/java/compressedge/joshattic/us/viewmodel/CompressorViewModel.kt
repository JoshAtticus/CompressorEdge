package compressedge.joshattic.us.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import compressedge.joshattic.us.BuildConfig
import compressedge.joshattic.us.R
import compressedge.joshattic.us.compression.BackgroundCompressionManager
import compressedge.joshattic.us.compression.BackgroundCompressionService
import compressedge.joshattic.us.compression.CompressionExecutor
import compressedge.joshattic.us.model.CompressorUiState
import compressedge.joshattic.us.model.FilenameSegment
import compressedge.joshattic.us.model.DefaultAudioConfig
import compressedge.joshattic.us.model.DefaultVideoConfig
import compressedge.joshattic.us.model.QualityPreset
import compressedge.joshattic.us.model.QualityPresetConfig
import compressedge.joshattic.us.model.TargetSizePreset
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(UnstableApi::class)
class CompressorViewModel(application: Application) : AndroidViewModel(application) {
    private data class VideoTrackInfo(
        val mimeType: String?,
        val width: Int,
        val height: Int,
        val frameRate: Float
    )

    private data class CompressionPlan(
        val outputVideoMimeType: String,
        val outputHeight: Int,
        val outputFps: Int,
        val warnings: List<String>,
        val blockingError: String?
    )

    private val _uiState = MutableStateFlow(CompressorUiState())
    val uiState = _uiState.asStateFlow()
    
    private val prefs: SharedPreferences by lazy {
        getApplication<Application>().getSharedPreferences("compressor_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREF_CUSTOM_OUTPUT_TREE_URI = "custom_output_tree_uri"
        private const val PREF_CUSTOM_OUTPUT_FOLDER_NAME = "custom_output_folder_name"
        private const val PREF_SAVED_VERSION_CODE = "saved_app_version_code"
        private const val PREF_FILENAME_SEGMENTS = "filename_segments_v2"
        private const val DEFAULT_FILENAME_SEGMENTS = "token:original_name|token:compressed"
        private val CURRENT_VERSION_CODE = BuildConfig.VERSION_CODE
        private const val PERSIST_URI_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    init {
        val saved = prefs.getLong("total_saved_bytes", 0L)
        val showBitrate = prefs.getBoolean("show_bitrate", false)
        val useMbps = prefs.getBoolean("use_mbps", false)
        val showStorageSaved = prefs.getBoolean("show_storage_saved", true)
        val showTargetSizePreset = prefs.getBoolean("show_target_size_preset", true)
        val autoSaveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val autoSaveToPhotos = autoSaveSupported && prefs.getBoolean("auto_save_photos", true)
        val customOutputTreeUri = prefs.getString(PREF_CUSTOM_OUTPUT_TREE_URI, null)
        val customOutputFolderName = prefs.getString(PREF_CUSTOM_OUTPUT_FOLDER_NAME, null)
        val highConfig = loadQualityPresetConfig("preset_high", QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000))
        val mediumConfig = loadQualityPresetConfig("preset_medium", QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000))
        val lowConfig = loadQualityPresetConfig("preset_low", QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000))
        val sizePresetsList = loadTargetSizePresets()
        val defaultVideo = loadDefaultVideoConfig()
        val defaultAudio = loadDefaultAudioConfig()
        val filenameSegments = deserializeSegments(
            prefs.getString(PREF_FILENAME_SEGMENTS, DEFAULT_FILENAME_SEGMENTS) ?: DEFAULT_FILENAME_SEGMENTS
        )

        val savedVersionCode = prefs.getInt(PREF_SAVED_VERSION_CODE, -1)
        val showWhatsNew = if (savedVersionCode != -1) {
            CURRENT_VERSION_CODE > savedVersionCode
        } else {
            val isExistingUser = prefs.all.isNotEmpty()
            if (!isExistingUser) {
                prefs.edit().putInt(PREF_SAVED_VERSION_CODE, CURRENT_VERSION_CODE).apply()
            }
            isExistingUser
        }
        val backgroundCompressionEnabled = prefs.getBoolean("background_compression_enabled", false)
        val backgroundCompressionPrompted = prefs.getBoolean("background_compression_prompted", false)

        _uiState.update { it.copy(
            totalSavedBytes = saved, 
            showBitrate = showBitrate, 
            useMbps = useMbps,
            showStorageSaved = showStorageSaved,
            showTargetSizePreset = showTargetSizePreset,
            autoSaveToPhotos = autoSaveToPhotos,
            backgroundCompressionEnabled = backgroundCompressionEnabled,
            backgroundCompressionPrompted = backgroundCompressionPrompted,
            customOutputTreeUri = customOutputTreeUri,
            customOutputFolderName = customOutputFolderName,
            highPresetConfig = highConfig,
            mediumPresetConfig = mediumConfig,
            lowPresetConfig = lowConfig,
            targetSizePresets = sizePresetsList,
            defaultVideoConfig = defaultVideo,
            defaultAudioConfig = defaultAudio,
            filenameSegments = filenameSegments,
            showWhatsNewDialog = showWhatsNew
        ) }
        checkSupportedCodecs()
        checkSupportedAudioCodecs()
        clearCache()
    }

    fun dismissWhatsNewDialog() {
        prefs.edit().putInt(PREF_SAVED_VERSION_CODE, CURRENT_VERSION_CODE).apply()
        _uiState.update { it.copy(showWhatsNewDialog = false) }
    }
    
    internal fun checkSupportedCodecs() {
        val allCodecsEnabled = prefs.getBoolean("all_codecs_enabled", false)
        val supported = mutableListOf<String>()

        if (allCodecsEnabled) {
            supported.addAll(getDeviceEncoders())
        } else {
            supported.add(MimeTypes.VIDEO_H264)
            if (hasEncoder(MimeTypes.VIDEO_H265)) {
                supported.add(MimeTypes.VIDEO_H265)
            }
            if (hasEncoder(MimeTypes.VIDEO_AV1)) {
                supported.add(MimeTypes.VIDEO_AV1)
            }
        }
        
        _uiState.update { 
            var newCodec = it.videoCodec
            if (!supported.contains(newCodec)) {
                newCodec = when {
                    supported.contains(MimeTypes.VIDEO_H265) -> MimeTypes.VIDEO_H265
                    supported.contains(MimeTypes.VIDEO_H264) -> MimeTypes.VIDEO_H264
                    supported.isNotEmpty() -> supported.first()
                    else -> MimeTypes.VIDEO_H264
                }
            }
            it.copy(
                supportedCodecs = supported, 
                videoCodec = newCodec, 
                useH265 = newCodec == MimeTypes.VIDEO_H265,
                allCodecsEnabled = allCodecsEnabled,
                allCodecsUnlocked = true
            ) 
        }
    }

    internal fun checkSupportedAudioCodecs() {
        val supported = mutableListOf(MimeTypes.AUDIO_AAC)
        // Opus is usually software-only; include software encoders for audio.
        if (hasAnyAudioEncoder(MimeTypes.AUDIO_OPUS)) {
            supported.add(MimeTypes.AUDIO_OPUS)
        }

        _uiState.update {
            var newCodec = it.audioCodec
            if (!supported.contains(newCodec)) {
                newCodec = MimeTypes.AUDIO_AAC
            }
            var defaultConfig = it.defaultAudioConfig
            if (!supported.contains(defaultConfig.defaultAudioCodec)) {
                defaultConfig = defaultConfig.copy(defaultAudioCodec = MimeTypes.AUDIO_AAC)
            }
            it.copy(
                supportedAudioCodecs = supported,
                audioCodec = newCodec,
                defaultAudioConfig = defaultConfig
            )
        }
    }

    /** True if any encoder (hardware or software) supports the given audio MIME type. */
    internal fun hasAnyAudioEncoder(mimeType: String): Boolean {
        return try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(mimeType, ignoreCase = true)) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    internal fun getDeviceEncoders(): List<String> {
        val codecs = mutableSetOf<String>()
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.startsWith("video/", ignoreCase = true)) {
                        codecs.add(type.lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val preferred = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)
        return codecs.toList().sortedWith { a, b ->
            val indexA = preferred.indexOf(a)
            val indexB = preferred.indexOf(b)
            when {
                indexA != -1 && indexB != -1 -> indexA.compareTo(indexB)
                indexA != -1 -> -1
                indexB != -1 -> 1
                else -> a.compareTo(b)
            }
        }
    }

    fun isSoftwareCodec(mimeType: String): Boolean {
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            var hasHardware = false
            var hasSoftware = false
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    val isSW = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isSoftwareOnly
                    } else {
                        val name = info.name.lowercase()
                        name.startsWith("c2.android") || name.startsWith("omx.google")
                    }
                    if (isSW) {
                        hasSoftware = true
                    } else {
                        hasHardware = true
                    }
                }
            }
            return hasSoftware && !hasHardware
        } catch (e: Exception) {
            return false
        }
    }

    fun enableAllCodecsFeature() {
        prefs.edit {
            putBoolean("all_codecs_enabled", true)
        }
        checkSupportedCodecs()
    }

    fun disableAllCodecsFeature() {
        prefs.edit {
            putBoolean("all_codecs_enabled", false)
        }
        checkSupportedCodecs()
    }

    internal fun hasEncoder(mimeType: String): Boolean {
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (info.isSoftwareOnly) {
                        continue
                    }
                } else {
                    val name = info.name.lowercase()
                    if (name.startsWith("c2.android")) {
                        continue
                    }
                }

                if (info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                    return true
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private var compressionJob: Job? = null
    private var bgJob: Job? = null
    private var activeTransformer: Transformer? = null

    fun updateSelectedUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            var size = 0L
            var width = 0
            var height = 0
            var bitrate = 0
            var audioBitrate = 0
            var fps = 30f
            var videoMime: String? = null
            var duration = 0L
            var originalName: String? = null

            try {
                audioBitrate = getAudioBitrate(context, uri)
                val videoInfo = getVideoTrackInfo(context, uri)
                videoMime = videoInfo?.mimeType
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    size = it.statSize
                }
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(context, uri)

                width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

                val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rotation == 90 || rotation == 270) {
                    val temp = width
                    width = height
                    height = temp
                }

                bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

                val fpsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                fps = fpsStr?.toFloatOrNull() ?: 0f
                if (fps <= 0f && videoInfo != null && videoInfo.frameRate > 0f) {
                    fps = videoInfo.frameRate
                }
                if (fps <= 0f) {
                    fps = 30f
                }

                val cursor = context.contentResolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        originalName = cursor.getString(nameIndex)
                    }
                    cursor.close()
                }

                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val current = _uiState.value
            val videoConfig = current.defaultVideoConfig
            val audioConfig = current.defaultAudioConfig

            val defaultTargetMb = if (size > 0) (size / (1024.0 * 1024.0) * videoConfig.defaultSizeRatio).toFloat().coerceAtLeast(0.1f) else 10f

            val preferredCodec = if (current.supportedCodecs.contains(videoConfig.defaultVideoCodec)) videoConfig.defaultVideoCodec
                                 else if (current.supportedCodecs.contains(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265
                                 else MimeTypes.VIDEO_H264

            val preferredAudioCodec = if (current.supportedAudioCodecs.contains(audioConfig.defaultAudioCodec)) {
                audioConfig.defaultAudioCodec
            } else {
                MimeTypes.AUDIO_AAC
            }

            val isVertical = height > width
            fun getTargetHeight(targetShortSide: Int): Int {
                if (width <= 0 || height <= 0) return height
                if (isVertical) {
                    val targetWidth = minOf(targetShortSide, width)
                    return (targetWidth.toDouble() * height / width).toInt()
                } else {
                    return minOf(targetShortSide, height)
                }
            }

            val targetHeight = if (videoConfig.defaultTargetResolutionHeight > 0) getTargetHeight(videoConfig.defaultTargetResolutionHeight) else height
            val targetFpsVal = if (videoConfig.defaultTargetFps > 0 && fps >= videoConfig.defaultTargetFps) videoConfig.defaultTargetFps else 0

            _uiState.update { state ->
                state.copy(
                    selectedUri = uri,
                    originalSize = size,
                    originalWidth = width,
                    originalHeight = height,
                    originalBitrate = bitrate,
                    originalAudioBitrate = audioBitrate,
                    originalFps = fps,
                    originalVideoMime = videoMime,
                    durationMs = duration,
                    originalName = originalName,
                    targetSizeMb = defaultTargetMb,
                    targetResolutionHeight = targetHeight,
                    targetFps = targetFpsVal,
                     videoCodec = preferredCodec,
                     useH265 = preferredCodec == MimeTypes.VIDEO_H265,
                     activePreset = QualityPreset.CUSTOM,
                     audioCodec = preferredAudioCodec,
                     audioBitrate = audioConfig.defaultAudioBitrate,
                     audioBitrateLocked = false,
                     targetResolutionLocked = false,
                     targetFpsLocked = false,
                     removeAudio = audioConfig.defaultRemoveAudio,
                    audioVolume = audioConfig.defaultAudioVolume,
                    isCompressing = false,
                    progress = 0f,
                    compressedUri = null,
                    compressedSize = 0L,
                    currentOutputSize = 0L,
                    error = null,
                    errorLog = null,
                    saveSuccess = false,
                    isSaving = false,
                    hasShared = false
                ).autoAdjust(defaultTargetMb)
            }
        }
    }
    
    fun markAsShared() {
        _uiState.update { it.copy(hasShared = true) }
    }
    
    fun applyPreset(preset: QualityPreset) {
        if (preset == QualityPreset.CUSTOM) {
             _uiState.update { it.copy(activePreset = QualityPreset.CUSTOM) }
             return
        }
        
        val current = _uiState.value
        val isVertical = current.originalHeight > current.originalWidth
        
        fun getTargetHeight(targetShortSide: Int): Int {
            if (current.originalWidth <= 0 || current.originalHeight <= 0) return current.originalHeight
            
            if (isVertical) {
                val targetWidth = minOf(targetShortSide, current.originalWidth)
                return (targetWidth.toDouble() * current.originalHeight / current.originalWidth).toInt()
            } else {
                return minOf(targetShortSide, current.originalHeight)
            }
        }

        val config = when(preset) {
            QualityPreset.HIGH -> current.highPresetConfig
            QualityPreset.MEDIUM -> current.mediumPresetConfig
            QualityPreset.LOW -> current.lowPresetConfig
            else -> null
        }

        if (config != null) {
            val targetHeight = if (config.resolutionShortSide > 0) getTargetHeight(config.resolutionShortSide) else current.originalHeight
            val targetFpsVal = if (config.targetFps > 0 && current.originalFps >= config.targetFps) config.targetFps else 0
            val targetMb = (current.originalSize / (1024.0 * 1024.0) * config.sizeRatio).toFloat().coerceAtLeast(0.1f)

            _uiState.update { 
                it.copy(
                    activePreset = preset,
                    targetResolutionHeight = targetHeight,
                    targetFps = targetFpsVal,
                    targetSizeMb = targetMb,
                    audioBitrate = config.audioBitrate,
                    audioBitrateLocked = true,
                    targetResolutionLocked = true,
                    targetFpsLocked = true,
                    removeAudio = false
                ).autoAdjust(targetMb, lockAudioBitrate = true, allowUpward = false)
            }
        }
    }

    fun updateHighPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(highPresetConfig = config) }
        saveQualityPresetConfig("preset_high", config)
    }

    fun updateMediumPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(mediumPresetConfig = config) }
        saveQualityPresetConfig("preset_medium", config)
    }

    fun updateLowPresetConfig(config: QualityPresetConfig) {
        _uiState.update { it.copy(lowPresetConfig = config) }
        saveQualityPresetConfig("preset_low", config)
    }

    fun resetQualityPresets() {
        val defaultConfigHigh = QualityPresetConfig(resolutionShortSide = 0, targetFps = 0, sizeRatio = 0.7f, audioBitrate = 320_000)
        val defaultConfigMedium = QualityPresetConfig(resolutionShortSide = 1080, targetFps = 30, sizeRatio = 0.4f, audioBitrate = 192_000)
        val defaultConfigLow = QualityPresetConfig(resolutionShortSide = 720, targetFps = 30, sizeRatio = 0.2f, audioBitrate = 128_000)
        _uiState.update { it.copy(
            highPresetConfig = defaultConfigHigh,
            mediumPresetConfig = defaultConfigMedium,
            lowPresetConfig = defaultConfigLow
        ) }
        prefs.edit().remove("preset_high").remove("preset_medium").remove("preset_low").apply()
    }

    fun addTargetSizePreset(label: String, sizeMb: Float) {
        val newPreset = compressedge.joshattic.us.model.TargetSizePreset(
            id = "custom_" + System.currentTimeMillis(),
            sizeMb = sizeMb,
            label = label,
            isCustom = true
        )
        val newList = (_uiState.value.targetSizePresets + newPreset).sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun updateTargetSizePreset(id: String, label: String, sizeMb: Float) {
        val newList = _uiState.value.targetSizePresets.map { preset ->
            if (preset.id == id) preset.copy(label = label, sizeMb = sizeMb)
            else preset
        }.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun deleteTargetSizePreset(id: String) {
        val newList = _uiState.value.targetSizePresets.filterNot { it.id == id }.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        saveTargetSizePresets(newList)
    }

    fun resetTargetSizePresets() {
        val newList = compressedge.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        _uiState.update { it.copy(targetSizePresets = newList) }
        prefs.edit().remove("target_size_presets").apply()
    }

    fun updateDefaultVideoConfig(config: DefaultVideoConfig) {
        _uiState.update { it.copy(defaultVideoConfig = config) }
        saveDefaultVideoConfig(config)
    }

    fun resetDefaultVideoConfig() {
        val defaultConfig = DefaultVideoConfig()
        _uiState.update { it.copy(defaultVideoConfig = defaultConfig) }
        prefs.edit().remove("default_video_config").apply()
    }

    fun updateDefaultAudioConfig(config: DefaultAudioConfig) {
        _uiState.update { it.copy(defaultAudioConfig = config) }
        saveDefaultAudioConfig(config)
    }

    fun resetDefaultAudioConfig() {
        val defaultConfig = DefaultAudioConfig()
        _uiState.update { it.copy(defaultAudioConfig = defaultConfig) }
        prefs.edit().remove("default_audio_config").apply()
    }

    internal fun normalizeSegments(segments: List<FilenameSegment>): List<FilenameSegment> {
        val result = mutableListOf<FilenameSegment>()
        var currentText = ""
        for (seg in segments) {
            when (seg) {
                is FilenameSegment.Text -> {
                    currentText += seg.value
                }
                is FilenameSegment.Token -> {
                    result.add(FilenameSegment.Text(currentText))
                    currentText = ""
                    result.add(seg)
                }
            }
        }
        result.add(FilenameSegment.Text(currentText))
        return result
    }

    fun updateFilenameSegments(segments: List<FilenameSegment>) {
        val normalized = normalizeSegments(segments)
        _uiState.update { it.copy(filenameSegments = normalized) }
        prefs.edit().putString(PREF_FILENAME_SEGMENTS, serializeSegments(normalized)).apply()
    }

    fun insertFilenameTokenAt(index: Int, tokenKey: String) {
        val current = _uiState.value.filenameSegments
        // Each token can only be added once
        if (current.any { it is FilenameSegment.Token && it.key == tokenKey }) return
        val newList = current.toMutableList()
        val clampedIndex = index.coerceIn(0, newList.size)
        newList.add(clampedIndex, FilenameSegment.Token(tokenKey))
        updateFilenameSegments(newList)
    }

    fun removeFilenameSegmentAt(index: Int) {
        val current = _uiState.value.filenameSegments.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            updateFilenameSegments(current)
        }
    }

    fun moveFilenameSegment(fromIndex: Int, toIndex: Int) {
        val current = _uiState.value.filenameSegments.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            updateFilenameSegments(current)
        }
    }

    fun resetFilenamePattern() {
        val defaultSegments = listOf(
            FilenameSegment.Token("original_name"),
            FilenameSegment.Token("compressed")
        )
        updateFilenameSegments(defaultSegments)
    }

    internal fun serializeSegments(segments: List<FilenameSegment>): String {
        return segments
            .filter { it !is FilenameSegment.Text || it.value.isNotEmpty() }
            .joinToString("|") { seg ->
                when (seg) {
                    is FilenameSegment.Text -> "text:${seg.value}"
                    is FilenameSegment.Token -> "token:${seg.key}"
                }
            }
    }

    internal fun deserializeSegments(raw: String): List<FilenameSegment> {
        if (raw.isBlank()) return normalizeSegments(emptyList())
        val parsed = raw.split("|").mapNotNull { part ->
            when {
                part.startsWith("text:") -> {
                    val value = part.removePrefix("text:")
                    if (value.isNotEmpty()) FilenameSegment.Text(value) else null
                }
                part.startsWith("token:") -> {
                    val key = part.removePrefix("token:")
                    if (key.isNotEmpty()) FilenameSegment.Token(key) else null
                }
                else -> null
            }
        }
        return normalizeSegments(parsed)
    }

    fun generatePreviewFileName(state: CompressorUiState): String {
        return compressedOutputFileName(state)
    }

    internal fun compressedOutputFileName(state: CompressorUiState): String {
        val dateStr by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
        val timeStr by lazy { SimpleDateFormat("HH-mm-ss", Locale.US).format(Date()) }
        val randomStr by lazy { String.format(Locale.US, "%06d", (0..999999).random()) }

        val resStr by lazy {
            val h = if (state.targetResolutionHeight > 0) state.targetResolutionHeight else state.originalHeight
            if (h > 0) "${h}p" else "1080p"
        }

        val fpsStr by lazy {
            val fps = if (state.targetFps > 0) state.targetFps else state.originalFps.toInt()
            if (fps > 0) "${fps}fps" else "30fps"
        }

        val videoBitrateStr by lazy {
            val calcBitrate = state.targetBitrate
            if (calcBitrate > 0) {
                if (state.useMbps) {
                    val mbps = calcBitrate / 1_000_000f
                    String.format(Locale.US, "Video_%.1fMbps", mbps)
                } else {
                    val kbps = calcBitrate / 1000
                    "Video_${kbps}kbps"
                }
            } else {
                "Video_5Mbps"
            }
        }

        val audioBitrateStr by lazy {
            if (state.removeAudio) {
                "NoAudio"
            } else {
                val kbps = if (state.audioBitrate > 0) state.audioBitrate / 1000 else 128
                "Audio_${kbps}kbps"
            }
        }

        val encodingStr by lazy {
            when (state.videoCodec) {
                MimeTypes.VIDEO_H265 -> "H265"
                MimeTypes.VIDEO_H264 -> "H264"
                MimeTypes.VIDEO_AV1 -> "AV1"
                MimeTypes.VIDEO_VP9 -> "VP9"
                else -> state.videoCodec.substringAfterLast("/").uppercase(Locale.US)
            }
        }

        val audioStatusStr by lazy {
            if (state.removeAudio) "NoAudio" else "WithAudio"
        }

        val presetStr by lazy {
            when (state.activePreset) {
                QualityPreset.HIGH -> "High"
                QualityPreset.MEDIUM -> "Medium"
                QualityPreset.LOW -> "Low"
                QualityPreset.CUSTOM -> "Custom"
            }
        }

        val originalNameStr by lazy {
            state.originalName?.substringBeforeLast(".")?.takeIf { it.isNotBlank() } ?: "Compressed"
        }

        val parts = mutableListOf<String>()
        for (segment in state.filenameSegments) {
            val evaluated = when (segment) {
                is FilenameSegment.Text -> segment.value.trim()
                is FilenameSegment.Token -> when (segment.key) {
                    "original_name" -> originalNameStr
                    "compressed" -> "Compressed"
                    "date" -> dateStr
                    "time" -> timeStr
                    "random" -> randomStr
                    "resolution" -> resStr
                    "framerate", "fps" -> fpsStr
                    "bitrate" -> videoBitrateStr
                    "audio_bitrate" -> audioBitrateStr
                    "encoding", "codec" -> encodingStr
                    "audio_status" -> audioStatusStr
                    "preset" -> presetStr
                    else -> segment.key
                }
            }
            if (evaluated.isNotBlank()) {
                parts.add(evaluated)
            }
        }

        val rawName = parts.joinToString("_")
        val sanitized = rawName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')

        val finalStem = if (sanitized.isBlank()) "Compressed_${System.currentTimeMillis()}" else sanitized
        return "$finalStem.mp4"
    }

    private fun saveDefaultVideoConfig(config: DefaultVideoConfig) {
        val obj = JSONObject().apply {
            put("defaultVideoCodec", config.defaultVideoCodec)
            put("defaultTargetResolutionHeight", config.defaultTargetResolutionHeight)
            put("defaultTargetFps", config.defaultTargetFps)
            put("defaultSizeRatio", config.defaultSizeRatio.toDouble())
        }
        prefs.edit().putString("default_video_config", obj.toString()).apply()
    }

    private fun loadDefaultVideoConfig(): DefaultVideoConfig {
        val str = prefs.getString("default_video_config", null) ?: return DefaultVideoConfig()
        return try {
            val obj = JSONObject(str)
            DefaultVideoConfig(
                defaultVideoCodec = obj.optString("defaultVideoCodec", MimeTypes.VIDEO_H265),
                defaultTargetResolutionHeight = obj.optInt("defaultTargetResolutionHeight", 0),
                defaultTargetFps = obj.optInt("defaultTargetFps", 0),
                defaultSizeRatio = obj.optDouble("defaultSizeRatio", 0.7).toFloat()
            )
        } catch (e: Exception) {
            DefaultVideoConfig()
        }
    }

    private fun saveDefaultAudioConfig(config: DefaultAudioConfig) {
        val obj = JSONObject().apply {
            put("defaultAudioCodec", config.defaultAudioCodec)
            put("defaultAudioBitrate", config.defaultAudioBitrate)
            put("defaultRemoveAudio", config.defaultRemoveAudio)
            put("defaultAudioVolume", config.defaultAudioVolume.toDouble())
        }
        prefs.edit().putString("default_audio_config", obj.toString()).apply()
    }

    private fun loadDefaultAudioConfig(): DefaultAudioConfig {
        val str = prefs.getString("default_audio_config", null) ?: return DefaultAudioConfig()
        return try {
            val obj = JSONObject(str)
            DefaultAudioConfig(
                defaultAudioCodec = obj.optString("defaultAudioCodec", MimeTypes.AUDIO_AAC),
                defaultAudioBitrate = obj.optInt("defaultAudioBitrate", 128_000),
                defaultRemoveAudio = obj.optBoolean("defaultRemoveAudio", false),
                defaultAudioVolume = obj.optDouble("defaultAudioVolume", 1.0).toFloat()
            )
        } catch (e: Exception) {
            DefaultAudioConfig()
        }
    }

    private fun saveQualityPresetConfig(key: String, config: QualityPresetConfig) {
        val obj = JSONObject().apply {
            put("resolutionShortSide", config.resolutionShortSide)
            put("targetFps", config.targetFps)
            put("sizeRatio", config.sizeRatio.toDouble())
            put("audioBitrate", config.audioBitrate)
        }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    private fun loadQualityPresetConfig(key: String, default: QualityPresetConfig): QualityPresetConfig {
        val str = prefs.getString(key, null) ?: return default
        return try {
            val obj = JSONObject(str)
            QualityPresetConfig(
                resolutionShortSide = obj.getInt("resolutionShortSide"),
                targetFps = obj.getInt("targetFps"),
                sizeRatio = obj.getDouble("sizeRatio").toFloat(),
                audioBitrate = obj.getInt("audioBitrate")
            )
        } catch (e: Exception) {
            try {
                val parts = str.split(",")
                QualityPresetConfig(
                    resolutionShortSide = parts[0].toInt(),
                    targetFps = parts[1].toInt(),
                    sizeRatio = parts[2].toFloat(),
                    audioBitrate = parts[3].toInt()
                )
            } catch (ex: Exception) {
                default
            }
        }
    }

    private fun saveTargetSizePresets(list: List<compressedge.joshattic.us.model.TargetSizePreset>) {
        val array = JSONArray()
        for (preset in list) {
            val obj = JSONObject().apply {
                put("id", preset.id)
                put("sizeMb", preset.sizeMb.toDouble())
                put("label", preset.label)
                put("isCustom", preset.isCustom)
            }
            array.put(obj)
        }
        prefs.edit().putString("target_size_presets", array.toString()).apply()
    }

    private fun loadTargetSizePresets(): List<compressedge.joshattic.us.model.TargetSizePreset> {
        val str = prefs.getString("target_size_presets", null) ?: return compressedge.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        return try {
            if (str.startsWith("[")) {
                val array = JSONArray(str)
                val list = mutableListOf<compressedge.joshattic.us.model.TargetSizePreset>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        compressedge.joshattic.us.model.TargetSizePreset(
                            id = obj.getString("id"),
                            sizeMb = obj.getDouble("sizeMb").toFloat(),
                            label = obj.getString("label"),
                            isCustom = obj.optBoolean("isCustom", false)
                        )
                    )
                }
                list.ifEmpty { compressedge.joshattic.us.model.defaultTargetSizePresets }.sortedBy { it.sizeMb }
            } else {
                // Legacy split parser fallback
                str.split(";\n", ";").mapNotNull { itemStr ->
                    val parts = itemStr.trim().split("|")
                    if (parts.size >= 4) {
                        compressedge.joshattic.us.model.TargetSizePreset(
                            id = parts[0],
                            sizeMb = parts[1].toFloat(),
                            label = parts[2],
                            isCustom = parts[3].trim().toBoolean()
                        )
                    } else null
                }.ifEmpty { compressedge.joshattic.us.model.defaultTargetSizePresets }.sortedBy { it.sizeMb }
            }
        } catch (e: Exception) {
            compressedge.joshattic.us.model.defaultTargetSizePresets.sortedBy { it.sizeMb }
        }
    }

    fun setTargetSizePreview(mb: Float) {
        _uiState.update { it.copy(targetSizeMb = mb, activePreset = QualityPreset.CUSTOM) }
    }

    fun setTargetSize(mb: Float) {
        _uiState.update { it.copy(targetSizeMb = mb, activePreset = QualityPreset.CUSTOM).autoAdjust(mb) }
    }

    fun setVideoCodec(codec: String) {
        _uiState.update { 
            val temp = it.copy(
                videoCodec = codec, 
                useH265 = codec == MimeTypes.VIDEO_H265, 
                activePreset = QualityPreset.CUSTOM
            )
            temp.autoAdjust(temp.targetSizeMb)
        }
    }

    fun setAudioCodec(codec: String) {
        _uiState.update {
            it.copy(
                audioCodec = codec,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun toggleShowBitrate() {
        _uiState.update { 
            val newValue = !it.showBitrate
            prefs.edit { putBoolean("show_bitrate", newValue) }
            it.copy(showBitrate = newValue)
        }
    }

    fun toggleBitrateUnit() {
        _uiState.update { 
            val newValue = !it.useMbps
            prefs.edit { putBoolean("use_mbps", newValue) }
            it.copy(useMbps = newValue)
        }
    }

    fun toggleShowStorageSaved() {
        _uiState.update { 
            val newValue = !it.showStorageSaved
            prefs.edit { putBoolean("show_storage_saved", newValue) }
            it.copy(showStorageSaved = newValue)
        }
    }

    fun toggleShowTargetSizePreset() {
        _uiState.update { 
            val newValue = !it.showTargetSizePreset
            prefs.edit { putBoolean("show_target_size_preset", newValue) }
            it.copy(showTargetSizePreset = newValue)
        }
    }

    fun toggleAutoSaveToPhotos() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        _uiState.update { 
            val newValue = !it.autoSaveToPhotos
            prefs.edit { putBoolean("auto_save_photos", newValue) }
            it.copy(autoSaveToPhotos = newValue)
        }
    }

    fun toggleBackgroundCompression() {
        _uiState.update {
            val newValue = !it.backgroundCompressionEnabled
            prefs.edit {
                putBoolean("background_compression_enabled", newValue)
                putBoolean("background_compression_prompted", true)
            }
            it.copy(
                backgroundCompressionEnabled = newValue,
                backgroundCompressionPrompted = true
            )
        }
    }

    fun setBackgroundCompressionEnabled(enabled: Boolean) {
        _uiState.update {
            prefs.edit { putBoolean("background_compression_enabled", enabled) }
            it.copy(
                backgroundCompressionEnabled = enabled,
                backgroundCompressionPrompted = true
            )
        }
    }

    fun setCustomOutputFolder(context: Context, treeUri: Uri) {
        try {
            releasePersistedTreeUri(context, _uiState.value.customOutputTreeUri)
            context.contentResolver.takePersistableUriPermission(treeUri, PERSIST_URI_FLAGS)
            val folderName = resolveFolderDisplayName(context, treeUri)
            prefs.edit {
                putString(PREF_CUSTOM_OUTPUT_TREE_URI, treeUri.toString())
                putString(PREF_CUSTOM_OUTPUT_FOLDER_NAME, folderName)
            }
            _uiState.update {
                it.copy(
                    customOutputTreeUri = treeUri.toString(),
                    customOutputFolderName = folderName
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearCustomOutputFolder(context: Context) {
        releasePersistedTreeUri(context, _uiState.value.customOutputTreeUri)
        prefs.edit {
            remove(PREF_CUSTOM_OUTPUT_TREE_URI)
            remove(PREF_CUSTOM_OUTPUT_FOLDER_NAME)
        }
        _uiState.update {
            it.copy(customOutputTreeUri = null, customOutputFolderName = null)
        }
    }

    private fun releasePersistedTreeUri(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(uriString)
            val stillHeld = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            if (stillHeld) {
                context.contentResolver.releasePersistableUriPermission(uri, PERSIST_URI_FLAGS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun resolveFolderDisplayName(context: Context, treeUri: Uri): String {
        DocumentFile.fromTreeUri(context, treeUri)?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val path = docId.substringAfter(':', missingDelimiterValue = docId)
            path.substringAfterLast('/').ifBlank { path }.ifBlank {
                getApplication<Application>().getString(R.string.output_location_default)
            }
        } catch (_: Exception) {
            getApplication<Application>().getString(R.string.output_location_default)
        }
    }

    /** Saves using the configured location: custom SAF folder, or default Photos gallery. */
    fun saveCompressedOutput(context: Context) {
        val treeUri = _uiState.value.customOutputTreeUri
        if (!treeUri.isNullOrBlank()) {
            saveToCustomTree(context, Uri.parse(treeUri))
        } else {
            saveToGallery(context)
        }
    }

    fun toggleRemoveAudio() {
        _uiState.update { 
            val temp = it.copy(removeAudio = !it.removeAudio, activePreset = QualityPreset.CUSTOM)
            if (temp.removeAudio) {
                 temp
            } else {
                 temp.autoAdjust(temp.targetSizeMb)    
            }
        }
    }

    fun setAudioBitrate(bitrate: Int) {
        _uiState.update {
            val temp = it.copy(
                audioBitrate = bitrate,
                audioBitrateLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
            temp.autoAdjust(temp.targetSizeMb, lockAudioBitrate = true)
        }
    }

    fun setAudioVolume(volume: Float) {
        _uiState.update { it.copy(audioVolume = volume, activePreset = QualityPreset.CUSTOM) }
    }

    fun setResolution(height: Int) {
        _uiState.update {
            val isVertical = it.originalHeight > it.originalWidth
            val mappedHeight = if (
                isVertical &&
                it.originalWidth > 0 &&
                it.originalHeight > 0 &&
                height > 0
            ) {
                (height.toLong() * it.originalHeight / it.originalWidth).toInt()
            } else {
                height
            }
            it.copy(
                targetResolutionHeight = mappedHeight,
                targetResolutionLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun setFps(fps: Int) {
        _uiState.update {
            it.copy(targetFps = fps, targetFpsLocked = true, activePreset = QualityPreset.CUSTOM)
        }
    }

    fun acceptSuggestedResolution() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                targetResolutionHeight = suggestion.targetResolutionHeight,
                targetResolutionLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptSuggestedFps() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                targetFps = suggestion.targetFps,
                targetFpsLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptSuggestedAudioBitrate() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                audioBitrate = suggestion.audioBitrate,
                audioBitrateLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }

    fun acceptAllSuggestions() {
        _uiState.update { state ->
            val suggestion = state.suggestedForTarget()
            state.copy(
                audioBitrate = suggestion.audioBitrate,
                targetResolutionHeight = suggestion.targetResolutionHeight,
                targetFps = suggestion.targetFps,
                audioBitrateLocked = true,
                targetResolutionLocked = true,
                targetFpsLocked = true,
                activePreset = QualityPreset.CUSTOM
            )
        }
    }
    
    fun cancelCompression() {
        activeTransformer?.cancel()
        activeTransformer = null
        compressionJob?.cancel()
        compressionJob = null
        bgJob?.cancel()
        bgJob = null
        if (_uiState.value.isBackgroundCompression) {
            BackgroundCompressionService.stop(getApplication())
        }
        BackgroundCompressionManager.reset()
        _uiState.update { it.copy(isCompressing = false, isBackgroundCompression = false, progress = 0f) }
    }
    
    private fun clearCache() {
        try {
            val context = getApplication<Application>()
            val outputDir = File(context.cacheDir, "compressed_videos")
            if (outputDir.exists()) {
                outputDir.listFiles()?.forEach { 
                    try { it.delete() } catch(e: Exception) {} 
                }
            }
        } catch(e: Exception) {
             e.printStackTrace()
        }
    }

    fun reset() {
        val current = _uiState.value
        val savedBytes = current.totalSavedBytes
        val supportedCodecs = current.supportedCodecs
        val supportedAudioCodecs = current.supportedAudioCodecs
        val showBitrate = current.showBitrate
        val useMbps = current.useMbps
        
        bgJob?.cancel()
        bgJob = null
        activeTransformer?.cancel()
        activeTransformer = null
        compressionJob?.cancel()
        compressionJob = null
        if (current.isBackgroundCompression) {
            BackgroundCompressionService.stop(getApplication())
        }
        BackgroundCompressionManager.reset()
        clearCache()

        val defaultCodec = if (supportedCodecs.contains(MimeTypes.VIDEO_H265)) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264
        val useH265 = defaultCodec == MimeTypes.VIDEO_H265
        val defaultAudioCodec = if (supportedAudioCodecs.contains(current.defaultAudioConfig.defaultAudioCodec)) {
            current.defaultAudioConfig.defaultAudioCodec
        } else {
            MimeTypes.AUDIO_AAC
        }
        
        _uiState.update {
            CompressorUiState(
                totalSavedBytes = savedBytes,
                supportedCodecs = supportedCodecs,
                supportedAudioCodecs = supportedAudioCodecs,
                showBitrate = showBitrate,
                useMbps = useMbps,
                showStorageSaved = current.showStorageSaved,
                showTargetSizePreset = current.showTargetSizePreset,
                autoSaveToPhotos = current.autoSaveToPhotos,
                backgroundCompressionEnabled = current.backgroundCompressionEnabled,
                backgroundCompressionPrompted = current.backgroundCompressionPrompted,
                customOutputTreeUri = current.customOutputTreeUri,
                customOutputFolderName = current.customOutputFolderName,
                allCodecsEnabled = current.allCodecsEnabled,
                allCodecsUnlocked = current.allCodecsUnlocked,
                highPresetConfig = current.highPresetConfig,
                mediumPresetConfig = current.mediumPresetConfig,
                lowPresetConfig = current.lowPresetConfig,
                targetSizePresets = current.targetSizePresets,
                defaultVideoConfig = current.defaultVideoConfig,
                defaultAudioConfig = current.defaultAudioConfig,
                videoCodec = defaultCodec,
                useH265 = useH265,
                audioCodec = defaultAudioCodec
            )
        }
    }

    fun startCompression(context: Context) = viewModelScope.launch(Dispatchers.Main) {
        bgJob?.cancel()
        bgJob = null
        BackgroundCompressionManager.reset()

        val currentState = _uiState.value
        val inputUri = currentState.selectedUri ?: return@launch

        val plan = withContext(Dispatchers.IO) { buildCompressionPlan(context, currentState, inputUri) }
        if (plan.blockingError != null) {
            _uiState.update { it.copy(error = plan.blockingError, errorLog = null, isCompressing = false) }
            return@launch
        }

        _uiState.update {
            it.copy(
                isCompressing = true,
                isBackgroundCompression = false,
                progress = 0f,
                currentOutputSize = 0L,
                error = null,
                errorLog = null,
                compressedUri = null,
                saveSuccess = false,
                isSaving = false,
                warnings = plan.warnings
            )
        }

        val outputDir = File(context.cacheDir, "compressed_videos")
        outputDir.mkdirs()
        val baseName = currentState.originalName?.substringBeforeLast(".") ?: "Compressed_${System.currentTimeMillis()}"
        val outputFile = File(outputDir, "${baseName}_Compressed.mp4")
        if (outputFile.exists()) {
            outputFile.delete()
        }
        val outputPath = outputFile.absolutePath

        val audioBitrateToUse = if (currentState.audioBitrate == 0) {
            if (currentState.originalAudioBitrate > 0) currentState.originalAudioBitrate else 128_000
        } else {
            currentState.audioBitrate
        }

        val params = CompressionExecutor.Params(
            inputUri = inputUri,
            outputPath = outputPath,
            videoMimeType = plan.outputVideoMimeType,
            outputHeight = plan.outputHeight,
            outputFps = plan.outputFps,
            originalWidth = currentState.originalWidth,
            originalHeight = currentState.originalHeight,
            originalFps = currentState.originalFps,
            targetBitrate = currentState.targetBitrate,
            audioBitrate = audioBitrateToUse,
            audioCodec = currentState.audioCodec,
            removeAudio = currentState.removeAudio,
            audioVolume = currentState.audioVolume,
            onHdrToneMap = {
                val warningMsg = getApplication<Application>().getString(R.string.warning_hdr_tone_mapped)
                _uiState.update { it.copy(warnings = listOf(warningMsg)) }
            }
        )

        val transformer = CompressionExecutor.execute(
            context,
            params,
            onCompleted = { finalSize ->
                val savedBytes = currentState.originalSize - finalSize
                var newTotal = _uiState.value.totalSavedBytes
                if (savedBytes > 0) {
                    newTotal += savedBytes
                    prefs.edit { putLong("total_saved_bytes", newTotal) }
                }
                _uiState.update {
                    it.copy(
                        isCompressing = false,
                        progress = 1f,
                        compressedUri = Uri.fromFile(outputFile),
                        compressedSize = finalSize,
                        totalSavedBytes = newTotal
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && _uiState.value.autoSaveToPhotos) {
                    saveCompressedOutput(getApplication())
                }
            },
            onError = { exportException ->
                val errorMsg = CompressionExecutor.errorMessage(getApplication<Application>(), exportException)
                _uiState.update {
                    it.copy(
                        isCompressing = false,
                        error = errorMsg,
                        errorLog = exportException.stackTraceToString()
                    )
                }
            }
        )

        activeTransformer = transformer

        compressionJob = viewModelScope.launch {
            while (_uiState.value.isCompressing) {
                val progressHolder = androidx.media3.transformer.ProgressHolder()
                val state = transformer.getProgress(progressHolder)
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    val currentSize = if (outputFile.exists()) outputFile.length() else 0L
                    _uiState.update { it.copy(progress = progressHolder.progress / 100f, currentOutputSize = currentSize) }
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    fun startBackgroundCompression(context: Context) = viewModelScope.launch(Dispatchers.Main) {
        compressionJob?.cancel()
        compressionJob = null
        activeTransformer?.cancel()
        activeTransformer = null
        BackgroundCompressionManager.reset()

        val currentState = _uiState.value
        val inputUri = currentState.selectedUri ?: return@launch

        val plan = withContext(Dispatchers.IO) { buildCompressionPlan(context, currentState, inputUri) }
        if (plan.blockingError != null) {
            _uiState.update { it.copy(error = plan.blockingError, errorLog = null, isCompressing = false) }
            return@launch
        }

        _uiState.update {
            it.copy(
                isCompressing = true,
                isBackgroundCompression = true,
                progress = 0f,
                currentOutputSize = 0L,
                error = null,
                errorLog = null,
                compressedUri = null,
                saveSuccess = false,
                isSaving = false,
                warnings = plan.warnings
            )
        }

        val outputDir = File(context.cacheDir, "compressed_videos")
        outputDir.mkdirs()
        val baseName = currentState.originalName?.substringBeforeLast(".") ?: "Compressed_${System.currentTimeMillis()}"
        val outputFile = File(outputDir, "${baseName}_Compressed.mp4")
        if (outputFile.exists()) {
            outputFile.delete()
        }
        val outputPath = outputFile.absolutePath

        val audioBitrateToUse = if (currentState.audioBitrate == 0) {
            if (currentState.originalAudioBitrate > 0) currentState.originalAudioBitrate else 128_000
        } else {
            currentState.audioBitrate
        }

        val intent = Intent(context, BackgroundCompressionService::class.java).apply {
            action = BackgroundCompressionService.ACTION_START
            putExtra(BackgroundCompressionService.EXTRA_INPUT_URI, inputUri.toString())
            putExtra(BackgroundCompressionService.EXTRA_OUTPUT_PATH, outputPath)
            putExtra(BackgroundCompressionService.EXTRA_VIDEO_MIME, plan.outputVideoMimeType)
            putExtra(BackgroundCompressionService.EXTRA_OUTPUT_HEIGHT, plan.outputHeight)
            putExtra(BackgroundCompressionService.EXTRA_OUTPUT_FPS, plan.outputFps)
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_WIDTH, currentState.originalWidth)
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_HEIGHT, currentState.originalHeight)
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_FPS, currentState.originalFps)
            putExtra(BackgroundCompressionService.EXTRA_ORIGINAL_SIZE, currentState.originalSize)
            putExtra(BackgroundCompressionService.EXTRA_TARGET_BITRATE, currentState.targetBitrate)
            putExtra(BackgroundCompressionService.EXTRA_AUDIO_BITRATE, audioBitrateToUse)
            putExtra(BackgroundCompressionService.EXTRA_AUDIO_CODEC, currentState.audioCodec)
            putExtra(BackgroundCompressionService.EXTRA_REMOVE_AUDIO, currentState.removeAudio)
            putExtra(BackgroundCompressionService.EXTRA_AUDIO_VOLUME, currentState.audioVolume)
        }

        BackgroundCompressionManager.setRunning(currentState.originalSize)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            e.printStackTrace()
            BackgroundCompressionManager.reset()
            startCompression(context)
            return@launch
        }

        bgJob = viewModelScope.launch {
            BackgroundCompressionManager.state.collect { bg ->
                when {
                    bg.completed && bg.compressedUri != null -> {
                        val savedBytes = currentState.originalSize - bg.compressedSize
                        var newTotal = _uiState.value.totalSavedBytes
                        if (savedBytes > 0) {
                            newTotal += savedBytes
                            prefs.edit { putLong("total_saved_bytes", newTotal) }
                        }
                        _uiState.update {
                            it.copy(
                                isCompressing = false,
                                isBackgroundCompression = false,
                                progress = 1f,
                                compressedUri = bg.compressedUri,
                                compressedSize = bg.compressedSize,
                                totalSavedBytes = newTotal,
                                warnings = bg.hdrWarning?.let { listOf(it) } ?: it.warnings
                            )
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && _uiState.value.autoSaveToPhotos) {
                            saveCompressedOutput(getApplication())
                        }
                    }
                    bg.completed && bg.error != null -> {
                        _uiState.update {
                            it.copy(
                                isCompressing = false,
                                isBackgroundCompression = false,
                                error = bg.error,
                                errorLog = bg.errorLog
                            )
                        }
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                isCompressing = bg.isRunning,
                                isBackgroundCompression = bg.isRunning,
                                progress = bg.progress,
                                currentOutputSize = bg.outputSize
                            )
                        }
                    }
                }
            }
        }
    }

    private fun getAudioBitrate(context: Context, uri: Uri): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        return format.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        return 0
    }

    private fun getVideoTrackInfo(context: Context, uri: Uri): VideoTrackInfo? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    var frameRate = 0f
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            frameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        } catch (e: Exception) {
                            try {
                                frameRate = format.getFloat(MediaFormat.KEY_FRAME_RATE)
                            } catch (ignored: Exception) {}
                        }
                    }
                    return VideoTrackInfo(mime, width, height, frameRate)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        return null
    }

    private fun buildCompressionPlan(context: Context, state: CompressorUiState, inputUri: Uri): CompressionPlan {
        var outputMime = state.videoCodec
        var outputHeight = state.targetResolutionHeight
        var outputFps = state.targetFps
        val warnings = mutableListOf<String>()

        val sourceInfo = getVideoTrackInfo(context, inputUri)
        val sourceMime = sourceInfo?.mimeType ?: state.originalVideoMime
        val sourceWidth = sourceInfo?.width ?: 0
        val sourceHeight = sourceInfo?.height ?: 0
        val sourceFps = if ((sourceInfo?.frameRate ?: 0f) > 0f) sourceInfo!!.frameRate else state.originalFps

        if (!sourceMime.isNullOrBlank() && sourceWidth > 0 && sourceHeight > 0) {
            val decoderSupported = isCodecConfigurationSupported(
                mimeType = sourceMime,
                width = sourceWidth,
                height = sourceHeight,
                fps = sourceFps,
                encoder = false
            )
            if (!decoderSupported) {
                return CompressionPlan(
                    outputVideoMimeType = outputMime,
                    outputHeight = outputHeight,
                    outputFps = outputFps,
                    warnings = warnings,
                    blockingError = getApplication<Application>().getString(
                        R.string.error_decoder_config_unsupported_details,
                        sourceWidth,
                        sourceHeight,
                        sourceFps,
                        sourceMime.substringAfter("/")
                    )
                )
            }
        }

        val attemptedConfigs = mutableListOf<Triple<String, Int, Int>>()
        fun isCurrentOutputSupported(mime: String, height: Int, fps: Int): Boolean {
            val safeHeight = if (height > 0) height else state.originalHeight
            val safeFps = if (fps > 0) fps else state.originalFps.toInt()
            val aspectRatio = if (state.originalHeight > 0) state.originalWidth.toFloat() / state.originalHeight else 16f / 9f
            var outputWidth = (safeHeight * aspectRatio).toInt().coerceAtLeast(2)
            var outputActualHeight = safeHeight.coerceAtLeast(2)
            if (outputWidth % 2 != 0) outputWidth -= 1
            if (outputActualHeight % 2 != 0) outputActualHeight -= 1
            attemptedConfigs.add(Triple(mime, outputActualHeight, safeFps))
            return isCodecConfigurationSupported(
                mimeType = mime,
                width = outputWidth,
                height = outputActualHeight,
                fps = safeFps.toFloat(),
                encoder = true
            )
        }

        if (!isCurrentOutputSupported(outputMime, outputHeight, outputFps)) {
            if (outputMime != MimeTypes.VIDEO_H264 && isCurrentOutputSupported(MimeTypes.VIDEO_H264, outputHeight, outputFps)) {
                outputMime = MimeTypes.VIDEO_H264
                warnings.add(getApplication<Application>().getString(R.string.warning_codec_fallback_h264))
            } else {
                val fallbackHeights = listOf(1080, 720, 540, 480)
                    .filter { it in 2..state.originalHeight }
                    .ifEmpty { listOf(state.originalHeight.coerceAtLeast(2)) }
                val fallbackFps = listOf(30, 24)
                var supported = false

                for (heightCandidate in fallbackHeights) {
                    for (fpsCandidate in fallbackFps) {
                        if (isCurrentOutputSupported(MimeTypes.VIDEO_H264, heightCandidate, fpsCandidate)) {
                            outputMime = MimeTypes.VIDEO_H264
                            outputHeight = heightCandidate
                            outputFps = fpsCandidate
                            warnings.add(
                                getApplication<Application>().getString(
                                    R.string.warning_quality_fallback,
                                    outputHeight,
                                    outputFps
                                )
                            )
                            supported = true
                            break
                        }
                    }
                    if (supported) break
                }

                if (!supported) {
                    val attempted = attemptedConfigs
                        .joinToString(separator = ", ") { "${it.first.substringAfter("/")} ${it.second}p@${it.third}fps" }
                    return CompressionPlan(
                        outputVideoMimeType = outputMime,
                        outputHeight = outputHeight,
                        outputFps = outputFps,
                        warnings = warnings,
                        blockingError = getApplication<Application>().getString(
                            R.string.error_encoder_config_unsupported_details,
                            attempted
                        )
                    )
                }
            }
        }

        return CompressionPlan(
            outputVideoMimeType = outputMime,
            outputHeight = outputHeight,
            outputFps = outputFps,
            warnings = warnings,
            blockingError = null
        )
    }

    private fun isCodecConfigurationSupported(
        mimeType: String,
        width: Int,
        height: Int,
        fps: Float,
        encoder: Boolean
    ): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val safeFps = kotlin.math.ceil(if (fps > 0f) fps.toDouble() else 30.0)
            codecList.codecInfos
                .asSequence()
                .filter { it.isEncoder == encoder }
                .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
                .any { info ->
                    try {
                        val capabilities = info.getCapabilitiesForType(mimeType)
                        val videoCaps = capabilities.videoCapabilities ?: return@any false
                        videoCaps.areSizeAndRateSupported(width, height, safeFps) ||
                            videoCaps.areSizeAndRateSupported(height, width, safeFps)
                    } catch (_: Exception) {
                        false
                    }
                }
        } catch (_: Exception) {
            false
        }
    }

    fun saveToUri(context: Context, targetUri: Uri) {
        val currentState = _uiState.value
        if (currentState.isSaving) return
        val compressedUri = currentState.compressedUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(compressedUri.path!!)
                if (!file.exists()) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }
                
                context.contentResolver.openOutputStream(targetUri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                 _uiState.update { it.copy(saveSuccess = true) }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun saveToCustomTree(context: Context, treeUri: Uri) {
        val currentState = _uiState.value
        if (currentState.isSaving) return
        val compressedUri = currentState.compressedUri ?: return

        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(compressedUri.path!!)
                if (!file.exists()) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }

                val tree = DocumentFile.fromTreeUri(context, treeUri)
                if (tree == null || !tree.canWrite()) {
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, "Folder not writable"))
                    }
                    return@launch
                }

                val targetName = compressedOutputFileName(currentState)
                tree.findFile(targetName)?.takeIf { it.isFile }?.delete()
                val target = tree.createFile("video/mp4", targetName)
                if (target == null) {
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.error_gallery_entry))
                    }
                    return@launch
                }

                context.contentResolver.openOutputStream(target.uri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                } ?: run {
                    _uiState.update {
                        it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, "No output stream"))
                    }
                    return@launch
                }

                _uiState.update { it.copy(saveSuccess = true) }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun saveToGallery(context: Context) {
        val currentState = _uiState.value
        if (currentState.isSaving) return
        val compressedUri = currentState.compressedUri ?: return
        
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(compressedUri.path!!)
                if (!file.exists()) {
                    _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_file_lost)) }
                    return@launch
                }

                val targetName = compressedOutputFileName(currentState)

                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)

                    if (!containsKey(MediaStore.Video.Media.DATE_ADDED)) {
                        put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    }
                    if (!containsKey(MediaStore.Video.Media.DATE_MODIFIED)) {
                        put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Compressor Edge")
                    }
                }

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val itemUri = context.contentResolver.insert(collection, values)
                
                if (itemUri != null) {
                    context.contentResolver.openOutputStream(itemUri).use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out!!)
                        }
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        context.contentResolver.update(itemUri, values, null, null)
                    }
                    
                    _uiState.update { it.copy(saveSuccess = true) }
                } else {
                     _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_gallery_entry)) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(error = getApplication<Application>().getString(R.string.error_save_failed, e.message)) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }
}
