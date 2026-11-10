package com.codebyramiz.blelab.ble.scan

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the platform device object of the latest scan hit per address. Android attaches the
 * advertised name to the object a scan returns; one rebuilt from the bare address can have no
 * name, which makes the pairing prompt read "Tap to pair with null". Entries are kept for the
 * app's lifetime and replaced on every new hit; at most one per address nearby is ever stored.
 */
class ScanDeviceCache<T : Any> {
    private val devices = ConcurrentHashMap<String, T>()

    fun remember(address: String, device: T) {
        devices[address] = device
    }

    /** The scanned device for [address], or null when it was not seen in a scan (known/bonded reconnects). */
    fun find(address: String): T? = devices[address]
}
