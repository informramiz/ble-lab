package com.codebyramiz.blelab.ui.device

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codebyramiz.blelab.ble.GattClientFactory
import com.codebyramiz.blelab.ble.connection.DeviceConnectionManager
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.model.MotionSample
import com.codebyramiz.blelab.ble.model.Telemetry
import com.codebyramiz.blelab.ble.model.TouchEvent
import com.codebyramiz.blelab.recording.Clock
import com.codebyramiz.blelab.recording.RecordingRepository
import com.codebyramiz.blelab.recording.RecordingState
import com.codebyramiz.blelab.recording.SessionRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeviceUiState(
    val address: String,
    val name: String?,
    val connection: ConnectionState = ConnectionState.Idle,
    val samples: List<Float> = emptyList(),
    val latest: Telemetry? = null,
    val packets: Int = 0,
    val ledOn: Boolean = false,
    val ledPending: Boolean = false,
    val log: List<String> = emptyList(),
    /** Newest last, at most [DeviceViewModel.MOTION_SIZE] samples. */
    val motion: List<MotionSample> = emptyList(),
    val tilt: Tilt = Tilt.Level,
    /** Newest last, at most [DeviceViewModel.TOUCH_LOG_SIZE] events. */
    val touches: List<TouchEvent> = emptyList(),
    val message: MessageStatus = MessageStatus.None,
    val recording: RecordingState = RecordingState.Idle,
) {
    /** The firmware flags which sensors it has in every telemetry packet; false until the first one arrives. */
    val hasImu: Boolean get() = latest?.hasImu == true
    val hasTouch: Boolean get() = latest?.hasTouch == true
}

/** Outcome of the last text sent to the board's screen. */
sealed interface MessageStatus {
    data object None : MessageStatus

    data object Sending : MessageStatus

    data class Sent(val text: String) : MessageStatus

    data class Failed(val reason: String) : MessageStatus
}

@HiltViewModel
class DeviceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    clientFactory: GattClientFactory,
    repository: RecordingRepository,
    clock: Clock,
) : ViewModel() {
    private val address: String = checkNotNull(savedStateHandle["address"]) { "address nav argument missing" }
    private val manager = DeviceConnectionManager(clientFactory, viewModelScope)
    private val recorder = SessionRecorder(repository, clock)

    private val _state = MutableStateFlow(DeviceUiState(address, savedStateHandle["name"]))
    val state: StateFlow<DeviceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            manager.state.collect { connection ->
                _state.update { it.copy(connection = connection, log = (it.log + describe(connection)).takeLast(LOG_SIZE)) }
            }
        }
        viewModelScope.launch {
            manager.telemetry.collect { reading ->
                _state.update {
                    it.copy(
                        samples = (it.samples + reading.value).takeLast(CHART_SIZE),
                        latest = reading,
                        packets = it.packets + 1,
                        // The peripheral reports the real LED state in every packet; trust it unless a write is in flight.
                        ledOn = if (it.ledPending) it.ledOn else reading.ledOn,
                    )
                }
                recorder.onTelemetry(reading)
            }
        }
        viewModelScope.launch {
            manager.motion.collect { sample ->
                _state.update { it.copy(motion = (it.motion + sample).takeLast(MOTION_SIZE), tilt = Tilt.from(sample)) }
                recorder.onMotion(sample)
            }
        }
        viewModelScope.launch {
            manager.touches.collect { touch ->
                _state.update { it.copy(touches = (it.touches + touch).takeLast(TOUCH_LOG_SIZE), log = (it.log + "Touch ${touch.kind} at ${touch.x},${touch.y}").takeLast(LOG_SIZE)) }
            }
        }
        viewModelScope.launch { recorder.state.collect { recording -> _state.update { it.copy(recording = recording) } } }
        manager.connect(address)
    }

    fun retry() = manager.connect(address)

    fun setLed(on: Boolean) {
        val previous = _state.value.ledOn
        _state.update { it.copy(ledOn = on, ledPending = true) }
        viewModelScope.launch {
            val result = manager.setLed(on)
            _state.update { state ->
                result.fold(
                    onSuccess = { state.copy(ledPending = false, log = (state.log + "LED ${if (on) "on" else "off"}").takeLast(LOG_SIZE)) },
                    onFailure = { e ->
                        state.copy(ledOn = previous, ledPending = false, log = (state.log + "LED write failed: ${reasonOf(e)}").takeLast(LOG_SIZE))
                    },
                )
            }
        }
    }

    fun sendMessage(text: String) {
        if (_state.value.message == MessageStatus.Sending) return
        _state.update { it.copy(message = MessageStatus.Sending) }
        viewModelScope.launch {
            val result = manager.sendMessage(text)
            _state.update { state ->
                result.fold(
                    onSuccess = { state.copy(message = MessageStatus.Sent(text.trim()), log = (state.log + "Message sent").takeLast(LOG_SIZE)) },
                    onFailure = { e -> state.copy(message = MessageStatus.Failed(reasonOf(e)), log = (state.log + "Message failed: ${reasonOf(e)}").takeLast(LOG_SIZE)) },
                )
            }
        }
    }

    fun toggleRecording() {
        viewModelScope.launch {
            if (recorder.state.value is RecordingState.Recording) {
                if (recorder.stop() != null) appendLog("Recording saved")
            } else {
                recorder.start(address, _state.value.name)
                appendLog("Recording started")
            }
        }
    }

    /** Closes an open recording; called when the screen is left so the session gets an end time. */
    fun stopRecording() {
        viewModelScope.launch { recorder.stop() }
    }

    private fun appendLog(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(LOG_SIZE)) }

    private fun reasonOf(e: Throwable): String = (e as? BleException)?.error?.message ?: e.message.orEmpty()

    override fun onCleared() {
        manager.disconnect()
    }

    private fun describe(state: ConnectionState): String = when (state) {
        ConnectionState.Idle -> "Idle"
        is ConnectionState.Connecting -> "Connecting, attempt ${state.attempt}"
        is ConnectionState.Discovering -> "Connected; negotiating MTU and discovering services"
        is ConnectionState.Bonding -> "Pairing requested by peripheral"
        is ConnectionState.Ready -> "Ready: MTU ${state.mtu}, notifications on"
        is ConnectionState.Reconnecting -> "${state.cause.message}; retry ${state.attempt} in ${state.delayMillis} ms"
        is ConnectionState.Failed -> "Failed: ${state.error.message}"
    }

    companion object {
        const val CHART_SIZE = 120
        const val LOG_SIZE = 40
        const val MOTION_SIZE = 150
        const val TOUCH_LOG_SIZE = 20
    }
}
