package com.codebyramiz.blelab.ble.model

/** The connection state machine, as the UI sees it. */
sealed interface ConnectionState {
    data object Idle : ConnectionState

    data class Connecting(val address: String, val attempt: Int) : ConnectionState

    /** Connected; negotiating MTU, discovering services, enabling notifications. */
    data class Discovering(val address: String) : ConnectionState

    /** The peripheral asked for an encrypted link; system pairing is in progress. */
    data class Bonding(val address: String) : ConnectionState

    data class Ready(val address: String, val info: DeviceInfo, val mtu: Int) : ConnectionState

    /** Waiting [delayMillis] before attempt number [attempt]. */
    data class Reconnecting(
        val address: String,
        val attempt: Int,
        val delayMillis: Long,
        val cause: BleError,
    ) : ConnectionState

    data class Failed(val address: String, val error: BleError) : ConnectionState
}
