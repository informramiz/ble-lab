package com.codebyramiz.blelab.ble.scan

import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KnownDevicesTest {
    @Test
    fun `recognises a BLE Lab board by its advertised name`() {
        assertThat(KnownDevices.isBleLab("ble-lab-esp32")).isTrue()
        assertThat(KnownDevices.isBleLab("BLE-LAB-1")).isTrue()
        assertThat(KnownDevices.isBleLab("Headphones")).isFalse()
        assertThat(KnownDevices.isBleLab(null)).isFalse()
    }

    @Test
    fun `lab filter keeps only boards and marks them known without a signal strength`() {
        val board = KnownDevices.toUpdate("AA", "ble-lab-esp32", bleLabOnly = true, nowMillis = 5)!!.device
        assertThat(board.isKnown).isTrue()
        assertThat(board.isBleLab).isTrue()
        assertThat(board.rssi).isNull()
        assertThat(KnownDevices.toUpdate("BB", "Headphones", bleLabOnly = true, nowMillis = 5)).isNull()
    }

    @Test
    fun `without the lab filter other known devices are listed too`() {
        val other = KnownDevices.toUpdate("BB", "Headphones", bleLabOnly = false, nowMillis = 5)!!.device
        assertThat(other.isBleLab).isFalse()
    }

    @Test
    fun `a known device survives the stale timeout and keeps its name once it advertises again`() {
        val aggregator = ScanAggregator(staleAfterMillis = 10_000)
        aggregator.add(KnownDevices.toUpdate("AA", "ble-lab-esp32", true, 0)!!.device)
        assertThat(aggregator.snapshot(60_000)).hasSize(1)
        aggregator.add(ScannedDevice("AA", null, -50, true, 61_000))
        val merged = aggregator.snapshot(61_000).single()
        assertThat(merged.name).isEqualTo("ble-lab-esp32")
        assertThat(merged.rssi).isEqualTo(-50)
        assertThat(merged.isKnown).isTrue()
    }
}
