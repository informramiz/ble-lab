package com.codebyramiz.blelab.ble

import app.cash.turbine.test
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.model.ScanUpdate
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID

class CompositeBleTest {
    private val peripheral = SimulatedPeripheral()

    @Test
    fun `real scanner failure is reported and the demo device still appears`() = runTest {
        val failing = BleScanner { flow { throw BleException(BleError.BluetoothOff) } }
        CompositeBleScanner(failing, peripheral.scanner(clock = { 0L })).scan(GattProfile.LAB_SERVICE).test {
            val updates = listOf(awaitItem(), awaitItem())
            assertThat(updates).contains(ScanUpdate.Failed(BleError.BluetoothOff))
            assertThat(updates.filterIsInstance<ScanUpdate.Found>().single().device.isSimulated).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unexpected scanner exceptions are wrapped`() = runTest {
        val failing = BleScanner { flow { throw IllegalStateException("boom") } }
        val updates = CompositeBleScanner(failing, BleScanner { flow { } }).scan(null).toList()
        assertThat(updates).containsExactly(ScanUpdate.Failed(BleError.Unexpected("boom")))
    }

    @Test
    fun `demo device hides from scans filtered on another service`() = runTest {
        assertThat(peripheral.scanner().scan(UUID.randomUUID()).toList()).isEmpty()
        assertThat(peripheral.scanner(intervalMillis = 1).scan(null).take(2).toList()).hasSize(2)
    }

    @Test
    fun `routes the demo address to the simulator`() {
        var realCalls = 0
        val factory = RoutingGattClientFactory({
            realCalls++
            peripheral.newClient()
        }, peripheral)
        factory.create(peripheral.address)
        assertThat(realCalls).isEqualTo(0)
        factory.create("AA:BB:CC:DD:EE:FF")
        assertThat(realCalls).isEqualTo(1)
    }
}
