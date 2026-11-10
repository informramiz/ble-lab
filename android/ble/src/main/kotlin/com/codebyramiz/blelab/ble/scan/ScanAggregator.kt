package com.codebyramiz.blelab.ble.scan

import com.codebyramiz.blelab.ble.model.ScannedDevice

/**
 * Folds raw advertisement sightings into a stable list: one row per address, newest
 * RSSI wins, devices unseen for [staleAfterMillis] drop out, strongest signal first.
 */
class ScanAggregator(private val staleAfterMillis: Long = 10_000) {
    private val devices = LinkedHashMap<String, ScannedDevice>()

    fun add(device: ScannedDevice): List<ScannedDevice> {
        val previous = devices[device.address]
        // Scan responses often omit the name; keep the one we already learned.
        devices[device.address] = device.copy(
            name = device.name ?: previous?.name,
            rssi = device.rssi ?: previous?.rssi,
            isKnown = device.isKnown || previous?.isKnown == true,
        )
        return snapshot(device.lastSeenMillis)
    }

    fun snapshot(nowMillis: Long): List<ScannedDevice> {
        // A known device is not advertising, so silence says nothing about whether it is around.
        devices.values.removeAll { !it.isKnown && nowMillis - it.lastSeenMillis > staleAfterMillis }
        return devices.values.sortedWith(compareByDescending<ScannedDevice> { it.isBleLab }.thenByDescending { it.rssi ?: Int.MIN_VALUE })
    }

    fun clear() = devices.clear()
}
