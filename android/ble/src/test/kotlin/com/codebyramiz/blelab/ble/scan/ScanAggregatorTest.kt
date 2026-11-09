package com.codebyramiz.blelab.ble.scan

import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanAggregatorTest {
    private fun device(address: String, rssi: Int, at: Long, name: String? = null, lab: Boolean = false) = ScannedDevice(address, name, rssi, isBleLab = lab, lastSeenMillis = at)

    @Test
    fun `keeps one row per address with the latest RSSI`() {
        val aggregator = ScanAggregator()
        aggregator.add(device("A", -80, 0))
        val list = aggregator.add(device("A", -60, 100))
        assertThat(list.map { it.rssi }).containsExactly(-60)
    }

    @Test
    fun `remembers a name missing from later packets`() {
        val aggregator = ScanAggregator()
        aggregator.add(device("A", -70, 0, name = "ESP32"))
        assertThat(aggregator.add(device("A", -71, 10)).single().name).isEqualTo("ESP32")
    }

    @Test
    fun `sorts BLE Lab devices first then by signal`() {
        val aggregator = ScanAggregator()
        aggregator.add(device("weak", -90, 0))
        aggregator.add(device("strong", -40, 0))
        val list = aggregator.add(device("lab", -85, 0, lab = true))
        assertThat(list.map { it.address }).containsExactly("lab", "strong", "weak").inOrder()
    }

    @Test
    fun `drops devices not seen recently and clears`() {
        val aggregator = ScanAggregator(staleAfterMillis = 1_000)
        aggregator.add(device("old", -50, 0))
        assertThat(aggregator.add(device("new", -50, 5_000)).map { it.address }).containsExactly("new")
        aggregator.clear()
        assertThat(aggregator.snapshot(5_000)).isEmpty()
    }
}
