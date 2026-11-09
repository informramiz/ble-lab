package com.codebyramiz.blelab.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codebyramiz.blelab.ble.BleScanner
import com.codebyramiz.blelab.ble.BluetoothStateMonitor
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.ScanUpdate
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.scan.ScanAggregator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ScanUiState(
    val scanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
    val bleLabOnly: Boolean = true,
    val bluetoothOn: Boolean = true,
    val error: BleError? = null,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val scanner: BleScanner,
    bluetoothState: BluetoothStateMonitor,
) : ViewModel() {
    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val aggregator = ScanAggregator()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch { bluetoothState.enabled.collect { on -> _state.update { it.copy(bluetoothOn = on) } } }
    }

    fun startScan() {
        scanJob?.cancel()
        aggregator.clear()
        _state.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        val filter = if (_state.value.bleLabOnly) GattProfile.LAB_SERVICE else null
        scanJob = viewModelScope.launch {
            // Scans drain the battery and Android throttles apps that start more than 5 in 30 s,
            // so every scan is bounded.
            withTimeoutOrNull(SCAN_DURATION_MILLIS) {
                scanner.scan(filter).collect { update ->
                    when (update) {
                        is ScanUpdate.Found -> _state.update { it.copy(devices = aggregator.add(update.device)) }
                        is ScanUpdate.Failed -> _state.update { it.copy(error = update.error) }
                    }
                }
            }
            _state.update { it.copy(scanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun setBleLabOnly(enabled: Boolean) {
        _state.update { it.copy(bleLabOnly = enabled) }
        if (_state.value.scanning) startScan()
    }

    companion object {
        const val SCAN_DURATION_MILLIS = 12_000L
    }
}
