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
        val compressedSize: Long = 0L,
        val originalSize: Long = 0L,
        val error: String? = null,
        val errorLog: String? = null,
        val hdrWarning: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setRunning(originalSize: Long) {
        _state.value = State(isRunning = true, originalSize = originalSize)
    }

    fun updateProgress(progress: Float, outputSize: Long) {
        _state.update { it.copy(progress = progress, outputSize = outputSize) }
    }

    fun setHdrWarning(message: String) {
        _state.update { it.copy(hdrWarning = message) }
    }

    fun complete(uri: Uri, size: Long) {
        _state.update {
            it.copy(isRunning = false, progress = 1f, completed = true, compressedUri = uri, compressedSize = size)
        }
    }

    fun fail(error: String, errorLog: String) {
        _state.update {
            it.copy(isRunning = false, completed = true, error = error, errorLog = errorLog)
        }
    }

    fun reset() {
        _state.value = State()
    }
}
