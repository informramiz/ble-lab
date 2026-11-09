package com.codebyramiz.blelab.ble.model

import com.codebyramiz.blelab.ble.protocol.GattStatus

/** Everything that can go wrong on the BLE path, typed so the UI can explain it. */
sealed interface BleError {
    val message: String
    val retryable: Boolean

    data class Gatt(val status: Int) : BleError {
        override val message: String get() = GattStatus.describe(status)
        override val retryable: Boolean get() = GattStatus.isRetryable(status)
    }

    /** The link dropped after the session was ready (out of range, peripheral reset). */
    data class LinkLost(val status: Int) : BleError {
        override val message: String get() = "Connection lost: ${GattStatus.describe(status)}"
        override val retryable: Boolean get() = true
    }

    data object ServiceNotFound : BleError {
        override val message = "This device does not expose the BLE Lab service"
        override val retryable = false
    }

    data object BondingFailed : BleError {
        override val message = "Pairing was rejected or failed"
        override val retryable = false
    }

    data object NotConnected : BleError {
        override val message = "Not connected"
        override val retryable = false
    }

    data class MissingPermission(val permission: String) : BleError {
        override val message: String get() = "Missing permission $permission"
        override val retryable = false
    }

    data object BluetoothOff : BleError {
        override val message = "Bluetooth is turned off"
        override val retryable = false
    }

    data class ScanFailed(val code: Int) : BleError {
        override val message: String get() = "Scan failed (code $code)"
        override val retryable = true
    }

    data class Unexpected(override val message: String) : BleError {
        override val retryable = false
    }
}

/** Thrown by [com.codebyramiz.blelab.ble.GattClient] operations; carries a typed [BleError]. */
class BleException(val error: BleError) : Exception(error.message)
