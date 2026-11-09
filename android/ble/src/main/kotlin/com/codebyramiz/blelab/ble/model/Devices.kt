package com.codebyramiz.blelab.ble.model

/** A peripheral seen while scanning. */
data class ScannedDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** True when the advertisement carries the BLE Lab service UUID. */
    val isBleLab: Boolean,
    val lastSeenMillis: Long,
    val isSimulated: Boolean = false,
)

/** What the scanner emits: a sighting or a failure the UI should surface. */
sealed interface ScanUpdate {
    data class Found(val device: ScannedDevice) : ScanUpdate
    data class Failed(val error: BleError) : ScanUpdate
}

/** Read from the standard Device Information service (0x180A). */
data class DeviceInfo(
    val manufacturer: String?,
    val model: String?,
    val firmware: String?,
)
