@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codebyramiz.blelab.ui

import androidx.lifecycle.viewModelScope
import com.codebyramiz.blelab.MainDispatcherRule
import com.codebyramiz.blelab.ble.BleScanner
import com.codebyramiz.blelab.ble.BluetoothStateMonitor
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.ScanUpdate
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ui.scan.ScanViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ScanViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val bluetooth = MutableStateFlow(true)
    private val filters = mutableListOf<UUID?>()

    private fun device(address: String, rssi: Int, lab: Boolean) = ScannedDevice(address, "dev $address", rssi, isBleLab = lab, lastSeenMillis = System.currentTimeMillis())

    private fun viewModel(scanner: BleScanner) = ScanViewModel(
        scanner,
        object : BluetoothStateMonitor {
            override val enabled = bluetooth
        },
    )

    @Test
    fun `scan collects devices sorted lab first and stops after the time limit`() = runTest(main.dispatcher) {
        val vm = viewModel { filter ->
            filters += filter
            flow {
                emit(ScanUpdate.Found(device("AA", -80, lab = false)))
                emit(ScanUpdate.Found(device("BB", -60, lab = true)))
                awaitCancellation()
            }
        }
        vm.setBleLabOnly(false)
        vm.startScan()

        assertThat(vm.state.value.scanning).isTrue()
        assertThat(vm.state.value.devices.map { it.address }).containsExactly("BB", "AA").inOrder()
        assertThat(filters).containsExactly(null)

        advanceTimeBy(ScanViewModel.SCAN_DURATION_MILLIS + 1)
        assertThat(vm.state.value.scanning).isFalse()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `filter defaults to the BLE Lab service and toggling restarts a running scan`() = runTest(main.dispatcher) {
        val vm = viewModel { filter ->
            filters += filter
            flow { awaitCancellation() }
        }
        vm.startScan()
        vm.setBleLabOnly(false)
        assertThat(filters).containsExactly(GattProfile.LAB_SERVICE, null).inOrder()
        vm.stopScan()
        assertThat(vm.state.value.scanning).isFalse()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `scan failures and bluetooth state reach the UI`() = runTest(main.dispatcher) {
        val vm = viewModel { flow { emit(ScanUpdate.Failed(BleError.ScanFailed(2))) } }
        vm.startScan()
        assertThat(vm.state.value.error).isEqualTo(BleError.ScanFailed(2))
        assertThat(vm.state.value.scanning).isFalse()

        bluetooth.value = false
        assertThat(vm.state.value.bluetoothOn).isFalse()
        vm.viewModelScope.cancel()
    }
}
