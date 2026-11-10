package com.codebyramiz.blelab.ble.connection

import app.cash.turbine.test
import com.codebyramiz.blelab.ble.GattClient
import com.codebyramiz.blelab.ble.GattEvent
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.protocol.GattStatus
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceConnectionManagerTest {
    private val peripheral = SimulatedPeripheral()
    private val noJitter = BackoffPolicy(initialDelayMillis = 500, jitterRatio = 0.0, maxAttempts = 4)

    private fun TestScope.manager(backoff: BackoffPolicy = noJitter, client: () -> GattClient = peripheral::newClient) = DeviceConnectionManager({ client() }, backgroundScope, backoff)

    private fun TestScope.record(manager: DeviceConnectionManager): List<ConnectionState> {
        val states = mutableListOf<ConnectionState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.state.toList(states) }
        return states
    }

    @Test
    fun `walks connect, discover, ready with MTU and device info`() = runTest {
        val manager = manager()
        val states = record(manager)
        manager.connect(peripheral.address)
        advanceTimeBy(5_000)

        assertThat(states.map { it::class }).containsAtLeast(
            ConnectionState.Idle::class,
            ConnectionState.Connecting::class,
            ConnectionState.Discovering::class,
            ConnectionState.Ready::class,
        ).inOrder()
        val ready = manager.state.value as ConnectionState.Ready
        assertThat(ready.mtu).isEqualTo(GattProfile.PREFERRED_MTU)
        assertThat(ready.info.manufacturer).isEqualTo("Espressif")
        assertThat(ready.info.firmware).isEqualTo("ble-lab 1.1.0")
    }

    @Test
    fun `streams decoded telemetry once ready`() = runTest {
        val manager = manager()
        manager.connect(peripheral.address)
        manager.telemetry.test {
            assertThat(awaitItem().sequence).isEqualTo(0)
            assertThat(awaitItem().sequence).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `retries GATT 133 with backoff and then connects`() = runTest {
        peripheral.connectFailuresRemaining = 2
        val manager = manager()
        val states = record(manager)
        manager.connect(peripheral.address)
        advanceTimeBy(10_000)

        val waits = states.filterIsInstance<ConnectionState.Reconnecting>()
        assertThat(waits.map { it.attempt }).containsExactly(2, 3).inOrder()
        assertThat(waits.map { it.delayMillis }).containsExactly(1_000L, 2_000L).inOrder()
        assertThat(waits.first().cause).isEqualTo(BleError.Gatt(GattStatus.GATT_ERROR))
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Ready::class.java)
        assertThat(peripheral.connectAttempts).isEqualTo(3)
    }

    @Test
    fun `gives up after max attempts`() = runTest {
        peripheral.connectFailuresRemaining = Int.MAX_VALUE
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(120_000)

        assertThat(manager.state.value).isEqualTo(ConnectionState.Failed(peripheral.address, BleError.Gatt(GattStatus.GATT_ERROR)))
        assertThat(peripheral.connectAttempts).isEqualTo(noJitter.maxAttempts)
    }

    @Test
    fun `reconnects after the link drops and resets the attempt counter`() = runTest {
        val manager = manager()
        val states = record(manager)
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)
        peripheral.dropLink(GattStatus.CONNECTION_TIMEOUT)
        advanceTimeBy(5_000)

        val wait = states.filterIsInstance<ConnectionState.Reconnecting>().single()
        assertThat(wait.cause).isEqualTo(BleError.LinkLost(GattStatus.CONNECTION_TIMEOUT))
        assertThat(wait.attempt).isEqualTo(1)
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun `user disconnect stops the reconnect loop`() = runTest {
        peripheral.connectFailuresRemaining = Int.MAX_VALUE
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(1_000)
        manager.disconnect()
        val attempts = peripheral.connectAttempts
        advanceTimeBy(60_000)

        assertThat(manager.state.value).isEqualTo(ConnectionState.Idle)
        assertThat(peripheral.connectAttempts).isEqualTo(attempts)
    }

    @Test
    fun `writes the LED payload`() = runTest {
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)

        assertThat(manager.setLed(true).isSuccess).isTrue()
        assertThat(peripheral.ledOn).isTrue()
        assertThat(peripheral.ledWrites.last()).isEqualTo(byteArrayOf(1))
    }

    @Test
    fun `pairs right after connecting when the peripheral needs it`() = runTest {
        peripheral.requireBonding = true
        val manager = manager()
        val states = record(manager)
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)

        assertThat(peripheral.bonded).isTrue()
        val kinds = states.map { it::class }
        assertThat(kinds.indexOf(ConnectionState.Bonding::class)).isLessThan(kinds.indexOf(ConnectionState.Discovering::class))
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun `does not reconnect while pairing is waiting for the user`() = runTest {
        peripheral.requireBonding = true
        val manager = manager()
        val states = record(manager)
        manager.connect(peripheral.address)
        advanceTimeBy(500)

        assertThat(manager.state.value).isInstanceOf(ConnectionState.Bonding::class.java)
        assertThat(states.filterIsInstance<ConnectionState.Connecting>()).hasSize(1)
        assertThat(states.filterIsInstance<ConnectionState.Reconnecting>()).isEmpty()
    }

    @Test
    fun `fails without retrying when the user refuses pairing`() = runTest {
        peripheral.requireBonding = true
        peripheral.acceptBonding = false
        val manager = manager()
        val states = record(manager)
        manager.connect(peripheral.address)
        advanceTimeBy(60_000)

        val failed = manager.state.value as ConnectionState.Failed
        assertThat(failed.error).isEqualTo(BleError.BondingFailed)
        assertThat(states.filterIsInstance<ConnectionState.Connecting>()).hasSize(1)
    }

    @Test
    fun `pairs on demand when the bond is lost mid-session, then retries the write`() = runTest {
        peripheral.requireBonding = true
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)
        peripheral.forgetBond()

        assertThat(manager.setLed(true).isSuccess).isTrue()
        assertThat(peripheral.bonded).isTrue()
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun `an unbonded protected write pairs from the app first, so the board never rejects it`() = runTest {
        peripheral.requireBonding = true
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)
        peripheral.forgetBond()

        assertThat(manager.setLed(true).isSuccess).isTrue()
        assertThat(peripheral.bonded).isTrue()
        assertThat(peripheral.insufficientAuthRejections).isEqualTo(0)
        assertThat(peripheral.ledOn).isTrue()
    }

    @Test
    fun `a write the board rejects for insufficient authentication pairs and retries`() = runTest {
        peripheral.requireBonding = true
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)
        peripheral.peerForgetsBond()

        assertThat(manager.sendMessage("hi").isSuccess).isTrue()
        assertThat(peripheral.insufficientAuthRejections).isEqualTo(1)
        assertThat(peripheral.bonded).isTrue()
        assertThat(peripheral.messages).containsExactly("hi")
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun `a bond that lands after the pairing wait failed still ends in Ready`() = runTest {
        peripheral.requireBonding = true
        peripheral.bondLandsAfterFailedWait = true
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)

        assertThat(peripheral.bonded).isTrue()
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun `on-demand pairing that reports failure but bonds still retries the write`() = runTest {
        peripheral.requireBonding = true
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)
        peripheral.forgetBond()
        peripheral.bondLandsAfterFailedWait = true

        assertThat(manager.setLed(true).isSuccess).isTrue()
        assertThat(peripheral.ledOn).isTrue()
        assertThat(manager.state.value).isInstanceOf(ConnectionState.Ready::class.java)
    }

    @Test
    fun `LED write without a connection fails fast`() = runTest {
        val result = manager().setLed(true)
        assertThat((result.exceptionOrNull() as BleException).error).isEqualTo(BleError.NotConnected)
    }

    @Test
    fun `wrong device fails without retrying`() = runTest {
        var created = 0
        val manager = manager {
            created++
            StubClient(services = setOf(GattProfile.DEVICE_INFO_SERVICE))
        }
        manager.connect("AA:BB:CC:DD:EE:FF")
        advanceTimeBy(60_000)

        assertThat(manager.state.value).isEqualTo(ConnectionState.Failed("AA:BB:CC:DD:EE:FF", BleError.ServiceNotFound))
        assertThat(created).isEqualTo(1)
    }

    @Test
    fun `falls back to the default MTU and tolerates missing device info`() = runTest {
        val manager = manager { StubClient(services = setOf(GattProfile.LAB_SERVICE), mtuFails = true) }
        manager.connect("AA:BB:CC:DD:EE:FF")
        advanceTimeBy(1_000)

        val ready = manager.state.value as ConnectionState.Ready
        assertThat(ready.mtu).isEqualTo(GattProfile.DEFAULT_MTU)
        assertThat(ready.info.manufacturer).isNull()
    }

    private class StubClient(private val services: Set<UUID>, private val mtuFails: Boolean = false) : GattClient {
        override val events: Flow<GattEvent> = emptyFlow()

        override suspend fun connect() = Unit

        override suspend fun requestMtu(mtu: Int): Int = if (mtuFails) throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE)) else mtu

        override suspend fun discoverServices(): Set<UUID> = services

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray = ByteArray(0)

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) = Unit

        override suspend fun writeWithoutResponse(service: UUID, characteristic: UUID, value: ByteArray) = Unit

        override suspend fun enableNotifications(service: UUID, characteristic: UUID) = Unit

        override val isBonded: Boolean = true

        override suspend fun createBond() = Unit

        override fun close() = Unit
    }

    @Test
    fun `streams motion samples unpacked from batches`() = runTest {
        val manager = manager()
        manager.connect(peripheral.address)
        manager.motion.test {
            val first = awaitItem()
            val second = awaitItem()
            assertThat(second.boardMillis - first.boardMillis).isEqualTo(20)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `forwards board touches`() = runTest {
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)
        manager.touches.test {
            peripheral.touch(120, 80)
            val event = awaitItem()
            assertThat(event.x).isEqualTo(120)
            assertThat(event.y).isEqualTo(80)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sends a message to the board`() = runTest {
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)

        assertThat(manager.sendMessage("  hi board ").isSuccess).isTrue()
        assertThat(peripheral.messages).containsExactly("hi board")
    }

    @Test
    fun `a blank message never reaches the radio`() = runTest {
        val manager = manager()
        manager.connect(peripheral.address)
        advanceTimeBy(3_000)

        assertThat(manager.sendMessage("  ").isFailure).isTrue()
        assertThat(peripheral.messages).isEmpty()
    }

    @Test
    fun `a board without sensors still connects and just stays silent`() = runTest {
        val devKit = SimulatedPeripheral(hasBoardSensors = false)
        val manager = DeviceConnectionManager({ devKit.newClient() }, backgroundScope, noJitter)
        manager.connect(devKit.address)
        advanceTimeBy(5_000)

        assertThat(manager.state.value).isInstanceOf(ConnectionState.Ready::class.java)
        manager.telemetry.test {
            assertThat(awaitItem().hasImu).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
