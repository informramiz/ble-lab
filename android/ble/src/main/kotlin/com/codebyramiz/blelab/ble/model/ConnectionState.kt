package com.codebyramiz.blelab.ble.model

/** The connection state machine, as the UI sees it. */
sealed interface ConnectionState {
    data object Idle : ConnectionState

    data class Connecting(val address: String, val attempt: Int) : ConnectionState

    /** Connected; negotiating MTU, discovering services, enabling notifications. */
    data class Discovering(val address: String) : ConnectionState

    /** System pairing is in progress (started by the app, or on demand by a protected characteristic). */
    data class Bonding(val address: String) : ConnectionState

    /** [otaSupported]: the board exposes the OTA update service (firmware 1.2.0+). */
    data class Ready(val address: String, val info: DeviceInfo, val mtu: Int, val otaSupported: Boolean = false) : ConnectionState

    /** Waiting [delayMillis] before attempt number [attempt]. */
    data class Reconnecting(
        val address: String,
        val attempt: Int,
        val delayMillis: Long,
        val cause: BleError,
    ) : ConnectionState

    data class Failed(val address: String, val error: BleError) : ConnectionState
}
