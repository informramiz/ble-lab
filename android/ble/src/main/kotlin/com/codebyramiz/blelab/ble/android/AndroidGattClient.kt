package com.codebyramiz.blelab.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.codebyramiz.blelab.ble.GattClient
import com.codebyramiz.blelab.ble.GattClientFactory
import com.codebyramiz.blelab.ble.GattEvent
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.protocol.GattStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class AndroidGattClientFactory(private val context: Context) : GattClientFactory {
    override fun create(address: String): GattClient = AndroidGattClient(context.applicationContext, address)
}

/**
 * `BluetoothGatt` turned into suspend functions. Android allows one outstanding GATT
 * operation per connection, so a [Mutex] serialises them and each waits on a
 * [CompletableDeferred] completed from [BluetoothGattCallback]. Every wait has a timeout
 * because some stacks never call back.
 *
 * Runs only on a phone with a peripheral: excluded from JVM coverage, covered by the
 * manual checklist in the README.
 *
 * Permissions are checked by [BlePermissionCheck] before each public call; lint cannot
 * follow that, hence the MissingPermission suppressions.
 */
@SuppressLint("MissingPermission")
class AndroidGattClient(private val context: Context, private val address: String) : GattClient {
    private val operationLock = Mutex()
    private val _events = MutableSharedFlow<GattEvent>(extraBufferCapacity = 64)
    override val events: Flow<GattEvent> = _events.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var connectResult: CompletableDeferred<Unit>? = null

    @Volatile private var pending: CompletableDeferred<Any>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val connecting = connectResult
            if (newState == BluetoothProfile.STATE_CONNECTED && status == GattStatus.SUCCESS) {
                connecting?.complete(Unit)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connecting != null && !connecting.isCompleted) {
                    connecting.completeExceptionally(BleException(BleError.Gatt(status)))
                } else {
                    pending?.completeExceptionally(BleException(BleError.LinkLost(status)))
                    _events.tryEmit(GattEvent.Disconnected(status))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = finish(status) { mtu }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = finish(status) { gatt.services.map { it.uuid }.toSet() }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) = finish(status) { value }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) finish(status) { characteristic.value ?: ByteArray(0) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = finish(status) { Unit }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = finish(status) { Unit }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            _events.tryEmit(GattEvent.Notification(characteristic.uuid, value))
        }

        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                _events.tryEmit(GattEvent.Notification(characteristic.uuid, characteristic.value?.copyOf() ?: ByteArray(0)))
            }
        }
    }

    private fun finish(status: Int, value: () -> Any) {
        val op = pending ?: return
        if (status == GattStatus.SUCCESS) op.complete(value()) else op.completeExceptionally(BleException(BleError.Gatt(status)))
    }

    private val device: BluetoothDevice
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.getRemoteDevice(address)
            ?: throw BleException(BleError.BluetoothOff)

    override suspend fun connect() {
        BlePermissionCheck.requireConnect(context)
        val result = CompletableDeferred<Unit>()
        connectResult = result
        // autoConnect=false: connect now and fail fast. TRANSPORT_LE avoids a classic-BT
        // connection attempt on dual-mode chips, a common source of status 133.
        // API 37 deprecates this overload for connectGatt(BluetoothGattConnectionSettings, ...),
        // which is flag-gated (FLAG_GATT_CONN_SETTINGS); this one is still the only path on API 26-36.
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException(BleError.Gatt(GattStatus.OPERATION_TIMEOUT))
        } finally {
            connectResult = null
        }
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override val isBonded: Boolean
        get() {
            BlePermissionCheck.requireConnect(context)
            return device.bondState == BluetoothDevice.BOND_BONDED
        }

    override suspend fun requestMtu(mtu: Int): Int = operation { it.requestMtu(mtu) } as Int

    @Suppress("UNCHECKED_CAST")
    override suspend fun discoverServices(): Set<UUID> = operation { it.discoverServices() } as Set<UUID>

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray = operation { gatt ->
        gatt.readCharacteristic(gatt.characteristic(service, characteristic))
    } as ByteArray

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        operation { gatt -> writeCharacteristic(gatt, gatt.characteristic(service, characteristic), value) }
    }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        operation { gatt ->
            val target = gatt.characteristic(service, characteristic)
            val cccd = target.getDescriptor(GattProfile.CCCD) ?: throw BleException(BleError.ServiceNotFound)
            gatt.setCharacteristicNotification(target, true) &&
                writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    override suspend fun createBond() {
        BlePermissionCheck.requireConnect(context)
        val target = device
        if (target.bondState == BluetoothDevice.BOND_BONDED) return
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val changed = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                if (changed?.address != address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE -> bonded.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            if (!target.createBond()) throw BleException(BleError.BondingFailed)
            val ok = try {
                withTimeout(BOND_TIMEOUT_MILLIS) { bonded.await() }
            } catch (e: TimeoutCancellationException) {
                false
            }
            if (!ok) throw BleException(BleError.BondingFailed)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    override fun close() {
        val current = gatt ?: return
        gatt = null
        pending?.completeExceptionally(BleException(BleError.NotConnected))
        // disconnect() then close(): close() alone can leave the link up until supervision timeout,
        // and a leaked BluetoothGatt eventually exhausts the stack's client slots (status 133).
        runCatching { current.disconnect() }
        runCatching { current.close() }
    }

    /** Starts one GATT operation; [start] returns false if the stack refused to queue it. */
    private suspend fun operation(start: (BluetoothGatt) -> Boolean): Any = operationLock.withLock {
        BlePermissionCheck.requireConnect(context)
        val current = gatt ?: throw BleException(BleError.NotConnected)
        val op = CompletableDeferred<Any>()
        pending = op
        try {
            if (!start(current)) throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE))
            withTimeout(OPERATION_TIMEOUT_MILLIS) { op.await() }
        } catch (e: TimeoutCancellationException) {
            throw BleException(BleError.Gatt(GattStatus.OPERATION_TIMEOUT))
        } finally {
            pending = null
        }
    }

    private fun BluetoothGatt.characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic = getService(service)?.getCharacteristic(characteristic) ?: throw BleException(BleError.ServiceNotFound)

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = type
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        descriptor.value = value
        gatt.writeDescriptor(descriptor)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val OPERATION_TIMEOUT_MILLIS = 10_000L
        const val BOND_TIMEOUT_MILLIS = 30_000L
    }
}
