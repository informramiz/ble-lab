package com.codebyramiz.blelab.ble.android

import android.bluetooth.BluetoothDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BondWaitTest {
    @Test
    fun `bonding then bonded broadcast completes`() = runTest {
        val changes = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val result = async { awaitBonded(changes, { BluetoothDevice.BOND_BONDING }, 30_000) }
        runCurrent()
        changes.emit(BluetoothDevice.BOND_BONDING)
        advanceTimeBy(11_000)
        changes.emit(BluetoothDevice.BOND_BONDED)
        assertThat(result.await()).isTrue()
    }

    @Test
    fun `a missed broadcast is caught by polling the device bond state`() = runTest {
        var state = BluetoothDevice.BOND_BONDING
        val result = async { awaitBonded(emptyFlow(), { state }, 30_000) }
        advanceTimeBy(14_000)
        state = BluetoothDevice.BOND_BONDED
        assertThat(result.await()).isTrue()
        assertThat(testScheduler.currentTime).isLessThan(30_000)
    }

    @Test
    fun `a none reading from polling alone does not fail the wait`() = runTest {
        val changes = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val result = async { awaitBonded(changes, { BluetoothDevice.BOND_NONE }, 30_000) }
        advanceTimeBy(5_000)
        changes.emit(BluetoothDevice.BOND_BONDED)
        assertThat(result.await()).isTrue()
    }

    @Test
    fun `none broadcast fails and so does the timeout`() = runTest {
        val changes = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val refused = async { awaitBonded(changes, { BluetoothDevice.BOND_NONE }, 30_000) }
        runCurrent()
        changes.emit(BluetoothDevice.BOND_NONE)
        assertThat(refused.await()).isFalse()
        assertThat(awaitBonded(emptyFlow(), { BluetoothDevice.BOND_BONDING }, 30_000)).isFalse()
    }
}
