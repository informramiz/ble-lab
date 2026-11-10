package com.codebyramiz.blelab.ble.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.codebyramiz.blelab.ble.BleScanner
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.model.ScanUpdate
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.scan.KnownDevices
import com.codebyramiz.blelab.ble.scan.ScanDeviceCache
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * `BluetoothLeScanner` as a cold flow. Hardware filters on the service UUID let the
 * controller drop unrelated advertisements before they wake the app.
 * See https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices
 */
class AndroidBleScanner(
    private val context: Context,
    private val scanned: ScanDeviceCache<BluetoothDevice> = ScanDeviceCache(),
) : BleScanner {
    // Permission is checked by BlePermissionCheck just above every call; lint cannot follow it.
    @SuppressLint("MissingPermission")
    override fun scan(serviceFilter: UUID?): Flow<ScanUpdate> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) throw BleException(BleError.BluetoothOff)
        BlePermissionCheck.requireScan(context)
        val scanner = adapter.bluetoothLeScanner ?: throw BleException(BleError.BluetoothOff)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanned.remember(result.device.address, result.device)
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                trySend(
                    ScanUpdate.Found(
                        ScannedDevice(
                            address = result.device.address,
                            name = result.scanRecord?.deviceName,
                            rssi = result.rssi,
                            isBleLab = ParcelUuid(GattProfile.LAB_SERVICE) in uuids,
                            lastSeenMillis = System.currentTimeMillis(),
                        ),
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                trySend(ScanUpdate.Failed(BleError.ScanFailed(errorCode)))
                close()
            }
        }
        val filters = serviceFilter?.let { listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }.orEmpty()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // A bonded board that Android keeps linked stops advertising; list it from the system.
        knownDevices(adapter, bleLabOnly = serviceFilter != null).forEach { trySend(it) }
        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @SuppressLint("MissingPermission")
    private fun knownDevices(adapter: BluetoothAdapter, bleLabOnly: Boolean): List<ScanUpdate.Found> {
        // These lists need BLUETOOTH_CONNECT on Android 12+; without it the scan still works.
        if (runCatching { BlePermissionCheck.requireConnect(context) }.isFailure) return emptyList()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val open = manager?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        val now = System.currentTimeMillis()
        return (open + adapter.bondedDevices.orEmpty())
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
            .distinctBy { it.address }
            .mapNotNull { KnownDevices.toUpdate(it.address, it.name, bleLabOnly, now) }
    }
}
