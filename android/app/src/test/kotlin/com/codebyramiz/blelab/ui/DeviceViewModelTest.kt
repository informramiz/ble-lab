@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codebyramiz.blelab.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.codebyramiz.blelab.MainDispatcherRule
import com.codebyramiz.blelab.ble.GattClientFactory
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.model.TouchKind
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import com.codebyramiz.blelab.recording.FakeRecordingRepository
import com.codebyramiz.blelab.recording.RecordingState
import com.codebyramiz.blelab.ui.device.DeviceViewModel
import com.codebyramiz.blelab.ui.device.FirmwareUpdate
import com.codebyramiz.blelab.ui.device.MessageStatus
import com.codebyramiz.blelab.ui.device.Tilt
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class DeviceViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private var peripheral = SimulatedPeripheral()
    private val repository = FakeRecordingRepository()
    private var now = 10_000L

    private fun viewModel() = DeviceViewModel(
        SavedStateHandle(mapOf("address" to peripheral.address, "name" to peripheral.name)),
        GattClientFactory { peripheral.newClient() },
        repository,
        { now },
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
    }) { vm ->
        advanceTimeBy(2_000)
        // The bond is lost after connecting and the user then refuses to pair again.
        peripheral.forgetBond()
        peripheral.acceptBonding = false
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

    @Test
    fun `motion samples fill the chart and drive the tilt`() = vmTest { vm ->
        advanceTimeBy(5_000)
        val state = vm.state.value
        assertThat(state.hasImu).isTrue()
        assertThat(state.motion).isNotEmpty()
        assertThat(state.motion.size).isAtMost(DeviceViewModel.MOTION_SIZE)
        assertThat(state.tilt).isNotEqualTo(Tilt.Level)
    }

    @Test
    fun `a board without sensors degrades to no motion and no touch`() = vmTest(setUp = {
        peripheral = SimulatedPeripheral(hasBoardSensors = false)
    }) { vm ->
        advanceTimeBy(5_000)
        val state = vm.state.value
        assertThat(state.connection).isInstanceOf(ConnectionState.Ready::class.java)
        assertThat(state.latest).isNotNull()
        assertThat(state.hasImu).isFalse()
        assertThat(state.hasTouch).isFalse()
        assertThat(state.motion).isEmpty()
        assertThat(state.tilt).isEqualTo(Tilt.Level)
    }

    @Test
    fun `touch events from the board are listed and logged`() = vmTest { vm ->
        advanceTimeBy(2_000)
        peripheral.touch(10, 20)
        peripheral.touch(30, 40, TouchKind.LongPress)
        advanceTimeBy(500)
        val touches = vm.state.value.touches
        assertThat(touches.map { it.x to it.y }).containsExactly(10 to 20, 30 to 40).inOrder()
        assertThat(touches.last().kind).isEqualTo(TouchKind.LongPress)
        assertThat(vm.state.value.log).contains("Touch LongPress at 30,40")
    }

    @Test
    fun `message reaches the board and reports success`() = vmTest { vm ->
        advanceTimeBy(2_000)
        vm.sendMessage("  Hello  ")
        assertThat(vm.state.value.message).isEqualTo(MessageStatus.Sending)
        advanceTimeBy(1_000)
        assertThat(peripheral.messages).containsExactly("Hello")
        assertThat(vm.state.value.message).isEqualTo(MessageStatus.Sent("Hello"))
    }

    @Test
    fun `failed message write shows the reason`() = vmTest(setUp = {
        peripheral.requireBonding = true
        peripheral.acceptBonding = false
    }) { vm ->
        advanceTimeBy(2_000)
        vm.sendMessage("Hello")
        advanceTimeBy(2_000)
        assertThat(vm.state.value.message).isInstanceOf(MessageStatus.Failed::class.java)
        assertThat(peripheral.messages).isEmpty()
    }

    @Test
    fun `recording stores a session and closing it sets the end time`() = vmTest { vm ->
        advanceTimeBy(2_000)
        vm.toggleRecording()
        runCurrent()
        assertThat(vm.state.value.recording).isInstanceOf(RecordingState.Recording::class.java)
        now = 15_000
        advanceTimeBy(3_000)
        vm.toggleRecording()
        runCurrent()
        assertThat(vm.state.value.recording).isEqualTo(RecordingState.Idle)
        val session = repository.sessions().first().single()
        assertThat(session.endedAtMillis).isEqualTo(15_000)
        assertThat(session.deviceAddress).isEqualTo(peripheral.address)
        assertThat(session.telemetryCount).isGreaterThan(0)
        assertThat(session.motionCount).isGreaterThan(0)
        assertThat(vm.state.value.log).contains("Recording saved")
    }

    @Test
    fun `stopRecording closes an open session and is harmless when idle`() = vmTest { vm ->
        advanceTimeBy(1_000)
        vm.stopRecording()
        runCurrent()
        assertThat(repository.sessions().first()).isEmpty()
        vm.toggleRecording()
        runCurrent()
        vm.stopRecording()
        runCurrent()
        assertThat(repository.sessions().first().single().endedAtMillis).isNotNull()
    }

    private fun image(size: Int = 20_000, revision: String = "ble-lab 1.2.0"): ByteArray {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        bytes[0] = 0xE9.toByte()
        revision.toByteArray().copyInto(bytes, 100)
        return bytes
    }

    @Test
    fun `a file that is not a firmware image is rejected`() = vmTest { vm ->
        advanceTimeBy(5_000)
        vm.pickFirmware(ByteArray(500))
        assertThat(vm.state.value.firmware).isInstanceOf(FirmwareUpdate.Failed::class.java)
        vm.pickFirmware(null)
        assertThat((vm.state.value.firmware as FirmwareUpdate.Failed).reason).contains("could not be read")
    }

    @Test
    fun `a picked image shows its size and version before anything is sent`() = vmTest { vm ->
        advanceTimeBy(5_000)
        assertThat(vm.state.value.canUpdateFirmware).isTrue()
        vm.pickFirmware(image())
        assertThat(vm.state.value.firmware).isEqualTo(FirmwareUpdate.Picked(20_000, "ble-lab 1.2.0"))
        assertThat(peripheral.otaCompleted).isEqualTo(0)
    }

    @Test
    fun `update streams the image, the board restarts and the new version is confirmed`() = vmTest { vm ->
        advanceTimeBy(5_000)
        vm.pickFirmware(image())
        vm.startFirmwareUpdate()
        runCurrent()
        assertThat(vm.state.value.firmware).isInstanceOf(FirmwareUpdate.Running::class.java)
        advanceTimeBy(30_000)
        assertThat(peripheral.otaCompleted).isEqualTo(1)
        assertThat(vm.state.value.firmware).isEqualTo(FirmwareUpdate.Finished("ble-lab 1.2.0"))
        assertThat(vm.state.value.connection).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun `cancelling mid transfer leaves the board untouched and the file ready to retry`() = vmTest { vm ->
        advanceTimeBy(5_000)
        vm.pickFirmware(image(size = 400_000))
        vm.startFirmwareUpdate()
        advanceTimeBy(300)
        assertThat(vm.state.value.firmware).isInstanceOf(FirmwareUpdate.Running::class.java)
        vm.cancelFirmwareUpdate()
        advanceTimeBy(1_000)
        assertThat(vm.state.value.firmware).isEqualTo(FirmwareUpdate.Picked(400_000, "ble-lab 1.2.0"))
        assertThat(peripheral.otaCompleted).isEqualTo(0)
    }

    @Test
    fun `a board that came back on another version is reported as rolled back`() = vmTest(setUp = { peripheral.otaResultRevision = "ble-lab 1.1.0" }) { vm ->
        advanceTimeBy(5_000)
        vm.pickFirmware(image())
        vm.startFirmwareUpdate()
        advanceTimeBy(30_000)
        val failed = vm.state.value.firmware as FirmwareUpdate.Failed
        assertThat(failed.reason).contains("rolled back")
    }

    @Test
    fun `a board without the update service cannot be updated`() = vmTest(setUp = { peripheral = SimulatedPeripheral(supportsOta = false) }) { vm ->
        advanceTimeBy(5_000)
        assertThat(vm.state.value.canUpdateFirmware).isFalse()
    }
}
