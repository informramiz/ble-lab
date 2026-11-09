package com.codebyramiz.blelab.ble

import com.codebyramiz.blelab.ble.model.ScanUpdate
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * The seam between the app and the radio. Everything above this file is plain Kotlin
 * and runs on the JVM against [com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral];
 * the `android` package implements it with `android.bluetooth`.
 */
fun interface BleScanner {
    /** Cold flow: collecting starts a scan, cancelling stops it. */
    fun scan(serviceFilter: UUID?): Flow<ScanUpdate>
}

interface BluetoothStateMonitor {
    /** Emits the adapter's on/off state now and on every change. */
    val enabled: Flow<Boolean>
}

/** Events the peripheral pushes without being asked. */
sealed interface GattEvent {
    data class Notification(val characteristic: UUID, val value: ByteArray) : GattEvent {
        override fun equals(other: Any?): Boolean = other is Notification && other.characteristic == characteristic && other.value.contentEquals(value)

        override fun hashCode(): Int = 31 * characteristic.hashCode() + value.contentHashCode()
    }

    data class Disconnected(val status: Int) : GattEvent
}

/**
 * One GATT connection to one peripheral. Operations suspend until the stack answers and
 * throw [com.codebyramiz.blelab.ble.model.BleException] on a non-success status or timeout.
 * Implementations serialise operations: GATT allows only one in flight at a time.
 */
interface GattClient {
    val events: Flow<GattEvent>

    suspend fun connect()

    /**
     * True when the link is already encrypted with a stored bond, or the peripheral needs none.
     * When false the manager pairs right after connecting, while the app is in the foreground:
     * Android only shows a tap-to-pair notification for a pairing the peripheral starts itself.
     */
    val isBonded: Boolean

    /** Returns the MTU the peripheral agreed to. */
    suspend fun requestMtu(mtu: Int): Int

    /** Returns the UUIDs of the discovered services. */
    suspend fun discoverServices(): Set<UUID>

    suspend fun read(service: UUID, characteristic: UUID): ByteArray

    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray)

    suspend fun enableNotifications(service: UUID, characteristic: UUID)

    /** Starts system pairing and suspends until the device is bonded. */
    suspend fun createBond()

    /** Disconnects and releases the stack's client slot. Safe to call twice. */
    fun close()
}

fun interface GattClientFactory {
    fun create(address: String): GattClient
}
