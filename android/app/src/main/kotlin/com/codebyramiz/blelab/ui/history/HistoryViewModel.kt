package com.codebyramiz.blelab.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codebyramiz.blelab.recording.RecordingRepository
import com.codebyramiz.blelab.recording.RecordingSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(val sessions: List<RecordingSession> = emptyList(), val loaded: Boolean = false)

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repository: RecordingRepository) : ViewModel() {
    val state: StateFlow<HistoryUiState> = repository.sessions()
        .map { HistoryUiState(it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun delete(sessionId: Long) {
        viewModelScope.launch { repository.delete(sessionId) }
    }
}
