package com.codebyramiz.blelab.ui

import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.model.DeviceInfo
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.ble.model.Telemetry
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import com.codebyramiz.blelab.ui.device.DeviceUiState
import com.codebyramiz.blelab.ui.scan.ScanUiState
import kotlin.math.PI
import kotlin.math.sin

/** Deterministic UI states shared by the Compose tests and the Roborazzi screenshots. */
object Fixtures {
    private const val ADDRESS = SimulatedPeripheral.ADDRESS

    val devices = listOf(
        ScannedDevice(ADDRESS, "BLE Lab (demo)", -52, isBleLab = true, lastSeenMillis = 0, isSimulated = true),
        ScannedDevice("24:6F:28:1A:2B:3C", "ble-lab-esp32", -63, isBleLab = true, lastSeenMillis = 0),
        ScannedDevice("C8:2E:18:44:01:9A", null, -78, isBleLab = false, lastSeenMillis = 0),
        ScannedDevice("F0:08:D1:77:5E:12", "Fitness Band", -88, isBleLab = false, lastSeenMillis = 0),
    )

    val scanResults = ScanUiState(devices = devices, bleLabOnly = false)

    private val samples = List(90) { i -> 24f + 3f * sin(2 * PI * i / 50.0).toFloat() + ((i * 37) % 7 - 3) * 0.05f }

    private val info = DeviceInfo("Espressif", "ESP32-WROOM-32", "ble-lab 1.0.0")

    val ready = DeviceUiState(
        address = ADDRESS,
        name = "BLE Lab (demo)",
        connection = ConnectionState.Ready(ADDRESS, info, 247),
        samples = samples,
        latest = Telemetry(sequence = 1_204, value = samples.last(), ledOn = true),
        packets = 1_205,
        ledOn = true,
        log = listOf(
            "Connecting, attempt 1",
            "Connected; negotiating MTU and discovering services",
            "Ready: MTU 247, notifications on",
            "LED on",
        ),
    )

    val reconnecting = ready.copy(
        connection = ConnectionState.Reconnecting(ADDRESS, attempt = 2, delayMillis = 1_000, cause = BleError.LinkLost(8)),
        ledOn = false,
        log = ready.log + "Connection lost: connection timeout; retry 2 in 1000 ms",
    )

    val failed = DeviceUiState(
        address = ADDRESS,
        name = "BLE Lab (demo)",
        connection = ConnectionState.Failed(ADDRESS, BleError.Gatt(133)),
        log = listOf("Connecting, attempt 6", "Failed: GATT_ERROR 133"),
    )
}
