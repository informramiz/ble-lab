package com.codebyramiz.blelab.ble.protocol

/**
 * GATT / HCI status codes that reach `BluetoothGattCallback` (and their CoreBluetooth
 * equivalents), with what they mean in practice and whether a reconnect is worth it.
 */
object GattStatus {
    const val SUCCESS = 0
    const val INSUFFICIENT_AUTHENTICATION = 0x05
    const val CONNECTION_TIMEOUT = 0x08
    const val INSUFFICIENT_ENCRYPTION = 0x0F
    const val REMOTE_USER_TERMINATED = 0x13
    const val LOCAL_HOST_TERMINATED = 0x16
    const val LMP_RESPONSE_TIMEOUT = 0x22
    const val FAILED_TO_ESTABLISH = 0x3E

    /** The infamous Android `GATT_ERROR` (133): a catch-all from the stack, usually transient. */
    const val GATT_ERROR = 0x85
    const val GATT_FAILURE = 0x101

    /** Not a stack code: our own marker for an operation that never called back. */
    const val OPERATION_TIMEOUT = -1

    private val retryable = setOf(
        CONNECTION_TIMEOUT,
        REMOTE_USER_TERMINATED,
        LOCAL_HOST_TERMINATED,
        LMP_RESPONSE_TIMEOUT,
        FAILED_TO_ESTABLISH,
        GATT_ERROR,
        GATT_FAILURE,
        OPERATION_TIMEOUT,
    )

    fun isRetryable(status: Int): Boolean = status in retryable

    fun needsBonding(status: Int): Boolean = status == INSUFFICIENT_AUTHENTICATION || status == INSUFFICIENT_ENCRYPTION

    fun describe(status: Int): String = when (status) {
        SUCCESS -> "Success"
        INSUFFICIENT_AUTHENTICATION -> "Insufficient authentication (pairing needed)"
        CONNECTION_TIMEOUT -> "Link supervision timeout (device out of range)"
        INSUFFICIENT_ENCRYPTION -> "Insufficient encryption (pairing needed)"
        REMOTE_USER_TERMINATED -> "Peripheral closed the connection"
        LOCAL_HOST_TERMINATED -> "Phone closed the connection"
        LMP_RESPONSE_TIMEOUT -> "Link layer response timeout"
        FAILED_TO_ESTABLISH -> "Connection failed to be established"
        GATT_ERROR -> "GATT_ERROR 133 (stack busy or device gone; retrying usually works)"
        GATT_FAILURE -> "GATT failure"
        OPERATION_TIMEOUT -> "Operation timed out"
        else -> "GATT status $status"
    }
}
