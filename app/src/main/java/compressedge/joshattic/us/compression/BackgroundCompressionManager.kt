package compressedge.joshattic.us.compression

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory bridge between the background service (which does the work) and the
 * ViewModel/UI (which observes progress). It lives for the lifetime of the process.
 */
object BackgroundCompressionManager {

    data class State(
        val isRunning: Boolean = false,
        val progress: Float = 0f,
        val outputSize: Long = 0L,
        val completed: Boolean = false,
        val compressedUri: Uri? = null,
        val compressedUris: List<Uri> = emptyList(),
        val compressedSize: Long = 0L,
        val originalSize: Long = 0L,
        val error: String? = null,
        val errorLog: String? = null,
        val hdrWarning: String? = null,
        val currentlyCompressingUri: Uri? = null,
        val currentlyCompressingIndex: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var pendingBatch: List<CompressionExecutor.Params> = emptyList()

    fun setRunning(originalSize: Long) {
        _state.value = State(isRunning = true, originalSize = originalSize)
    }

    fun updateProgress(progress: Float, outputSize: Long, currentUri: Uri? = null, currentIndex: Int = 0) {
        _state.update { 
            it.copy(
                progress = progress, 
                outputSize = outputSize,
                currentlyCompressingUri = currentUri ?: it.currentlyCompressingUri,
                currentlyCompressingIndex = currentIndex
            ) 
        }
    }

    fun setHdrWarning(message: String) {
        _state.update { it.copy(hdrWarning = message) }
    }

    fun complete(uri: Uri, size: Long, uris: List<Uri> = emptyList()) {
        val finalUris = if (uris.isNotEmpty()) uris else listOf(uri)
        _state.update {
            it.copy(isRunning = false, progress = 1f, completed = true, compressedUri = uri, compressedUris = finalUris, compressedSize = size)
        }
    }

    fun fail(error: String, errorLog: String) {
        _state.update {
            it.copy(isRunning = false, completed = true, error = error, errorLog = errorLog)
        }
    }

    fun reset() {
        _state.value = State()
        pendingBatch = emptyList()
    }
}
