@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codebyramiz.blelab.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.codebyramiz.blelab.MainDispatcherRule
import com.codebyramiz.blelab.ble.GattClientFactory
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import com.codebyramiz.blelab.ui.device.DeviceViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DeviceViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val peripheral = SimulatedPeripheral()

    private fun viewModel() = DeviceViewModel(
        SavedStateHandle(mapOf("address" to peripheral.address, "name" to peripheral.name)),
        GattClientFactory { peripheral.newClient() },
    )

    /** Runs [body] against a fresh ViewModel and always cancels its scope, so a failed assertion cannot leave the simulated peripheral streaming and hang runTest. */
    private fun vmTest(setUp: () -> Unit = {}, body: suspend TestScope.(DeviceViewModel) -> Unit) = runTest(main.dispatcher) {
        setUp()
        val vm = viewModel()
        try {
            body(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `connects on start and streams telemetry into the chart`() = vmTest { vm ->
        advanceTimeBy(5_000)
        val state = vm.state.value
        assertThat(state.connection).isInstanceOf(ConnectionState.Ready::class.java)
        assertThat(state.packets).isGreaterThan(10)
        assertThat(state.samples.size).isEqualTo(state.packets.coerceAtMost(DeviceViewModel.CHART_SIZE))
        assertThat(state.latest).isNotNull()
        assertThat(state.log.first()).isEqualTo("Idle")
        assertThat(state.log).contains("Ready: MTU 247, notifications on")
    }

    @Test
    fun `LED write is optimistic and confirmed`() = vmTest { vm ->
        advanceTimeBy(2_000)
        vm.setLed(true)
        assertThat(vm.state.value.ledOn).isTrue()
        assertThat(vm.state.value.ledPending).isTrue()
        advanceTimeBy(1_000)
        assertThat(vm.state.value.ledPending).isFalse()
        assertThat(peripheral.ledOn).isTrue()
        assertThat(vm.state.value.log).contains("LED on")
    }

    @Test
    fun `failed LED write rolls back and logs the reason`() = vmTest(setUp = {
        peripheral.requireBonding = true
        peripheral.acceptBonding = false
    }) { vm ->
        advanceTimeBy(2_000)
        vm.setLed(true)
        advanceTimeBy(2_000)
        assertThat(vm.state.value.ledOn).isFalse()
        assertThat(vm.state.value.log.any { it.startsWith("LED write failed") }).isTrue()
        assertThat(vm.state.value.connection).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun `retry after exhausting attempts connects again`() = vmTest(setUp = {
        peripheral.connectFailuresRemaining = 100
    }) { vm ->
        advanceTimeBy(120_000)
        assertThat(vm.state.value.connection).isInstanceOf(ConnectionState.Failed::class.java)
        assertThat(vm.state.value.log.any { it.contains("; retry 2 in") }).isTrue()

        peripheral.connectFailuresRemaining = 0
        vm.retry()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(vm.state.value.connection).isInstanceOf(ConnectionState.Ready::class.java)
    }
}
