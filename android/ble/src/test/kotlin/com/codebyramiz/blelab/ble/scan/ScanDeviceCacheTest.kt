package com.codebyramiz.blelab.ble.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanDeviceCacheTest {
    @Test
    fun `unknown address has no scanned device`() {
        assertThat(ScanDeviceCache<String>().find("AA:BB")).isNull()
    }

    @Test
    fun `returns the device remembered for the address`() {
        val cache = ScanDeviceCache<String>()
        cache.remember("AA:BB", "scanned")
        assertThat(cache.find("AA:BB")).isEqualTo("scanned")
        assertThat(cache.find("CC:DD")).isNull()
    }

    @Test
    fun `a newer scan hit replaces the older one`() {
        val cache = ScanDeviceCache<String>()
        cache.remember("AA:BB", "old")
        cache.remember("AA:BB", "new")
        assertThat(cache.find("AA:BB")).isEqualTo("new")
    }
}
