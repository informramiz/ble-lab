package com.codebyramiz.blelab.ui.device

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codebyramiz.blelab.ble.GattClientFactory
import com.codebyramiz.blelab.ble.connection.DeviceConnectionManager
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.model.Telemetry
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
)

@HiltViewModel
class DeviceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    clientFactory: GattClientFactory,
) : ViewModel() {
    private val address: String = checkNotNull(savedStateHandle["address"]) { "address nav argument missing" }
    private val manager = DeviceConnectionManager(clientFactory, viewModelScope)

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
            }
        }
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
                        val reason = (e as? BleException)?.error?.message ?: e.message.orEmpty()
                        state.copy(ledOn = previous, ledPending = false, log = (state.log + "LED write failed: $reason").takeLast(LOG_SIZE))
                    },
                )
            }
        }
    }

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
    }
}
