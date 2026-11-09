package com.codebyramiz.blelab.ble.scan

import com.codebyramiz.blelab.ble.model.ScanUpdate
import com.codebyramiz.blelab.ble.model.ScannedDevice

/**
 * Turns devices the system already knows into scan results. The service UUID cannot be read
 * from such a device without opening a link, so a BLE Lab board is recognised by its name.
 */
object KnownDevices {
    const val NAME_PREFIX = "ble-lab"

    fun isBleLab(name: String?): Boolean = name?.startsWith(NAME_PREFIX, ignoreCase = true) == true

    /** The result for one system-known device, or null if the active filter excludes it. */
    fun toUpdate(address: String, name: String?, bleLabOnly: Boolean, nowMillis: Long): ScanUpdate.Found? {
        val lab = isBleLab(name)
        if (bleLabOnly && !lab) return null
        return ScanUpdate.Found(ScannedDevice(address, name, rssi = null, isBleLab = lab, lastSeenMillis = nowMillis, isKnown = true))
    }
}
