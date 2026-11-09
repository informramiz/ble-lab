package com.codebyramiz.blelab.ble.android

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for a bond to finish. [changes] carries `ACTION_BOND_STATE_CHANGED` states; because that
 * broadcast can be missed, [current] (the device's own `bondState`) is polled as well, and only a
 * poll that reads BONDED counts: a poll reading NONE can just mean bonding has not started yet.
 * Returns true when bonded, false on a BOND_NONE broadcast or after [timeoutMillis].
 */
internal suspend fun awaitBonded(
    changes: Flow<Int>,
    current: () -> Int,
    timeoutMillis: Long,
    pollMillis: Long = 500,
): Boolean {
    val polled = flow {
        while (true) {
            emit(current())
            delay(pollMillis)
        }
    }.filter { it == BluetoothDevice.BOND_BONDED }
    val result = withTimeoutOrNull(timeoutMillis) {
        merge(changes, polled).first { it == BluetoothDevice.BOND_BONDED || it == BluetoothDevice.BOND_NONE }
    }
    return result == BluetoothDevice.BOND_BONDED
}
