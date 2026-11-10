package com.codebyramiz.blelab.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codebyramiz.blelab.recording.RecordingRepository
import com.codebyramiz.blelab.recording.RecordingSession
import com.codebyramiz.blelab.recording.SessionCsv
import com.codebyramiz.blelab.recording.SessionData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SessionDetailUiState(
    val loading: Boolean = true,
    val session: RecordingSession? = null,
    val temperature: List<Float> = emptyList(),
    /** One series per axis (X, Y, Z); empty when nothing was recorded. */
    val acceleration: List<List<Float>> = emptyList(),
    val rotation: List<List<Float>> = emptyList(),
) {
    val notFound: Boolean get() = !loading && session == null
}

@HiltViewModel
class SessionDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repository: RecordingRepository) : ViewModel() {
    private val sessionId: Long = checkNotNull(savedStateHandle["sessionId"]) { "sessionId nav argument missing" }

    private val _state = MutableStateFlow(SessionDetailUiState())
    val state: StateFlow<SessionDetailUiState> = _state.asStateFlow()

    private var data: SessionData? = null

    init {
        viewModelScope.launch {
            val loaded = repository.load(sessionId)
            data = loaded
            _state.value = if (loaded == null) {
                SessionDetailUiState(loading = false)
            } else {
                SessionDetailUiState(
                    loading = false,
                    session = loaded.session,
                    temperature = Series.downsample(loaded.telemetry.map { it.value }),
                    acceleration = Series.acceleration(loaded.motion).map { Series.downsample(it) },
                    rotation = Series.rotation(loaded.motion).map { Series.downsample(it) },
                )
            }
        }
    }

    /** The session as CSV text plus the file name to share it under, or null when it did not load. */
    fun csv(): Pair<String, String>? = data?.let { SessionCsv.fileName(it.session) to SessionCsv.format(it) }
}
