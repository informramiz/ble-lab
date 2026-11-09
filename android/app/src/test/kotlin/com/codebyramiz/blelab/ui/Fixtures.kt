package com.codebyramiz.blelab.ui

import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.model.DeviceInfo
import com.codebyramiz.blelab.ble.model.MotionSample
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.ble.model.Telemetry
import com.codebyramiz.blelab.ble.model.TouchEvent
import com.codebyramiz.blelab.ble.model.TouchKind
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import com.codebyramiz.blelab.recording.RecordingSession
import com.codebyramiz.blelab.recording.RecordingState
import com.codebyramiz.blelab.ui.device.DeviceUiState
import com.codebyramiz.blelab.ui.device.MessageStatus
import com.codebyramiz.blelab.ui.device.Tilt
import com.codebyramiz.blelab.ui.history.HistoryUiState
import com.codebyramiz.blelab.ui.history.SessionDetailUiState
import com.codebyramiz.blelab.ui.scan.ScanUiState
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

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

    private val info = DeviceInfo("Espressif", "ESP32-WROOM-32", "ble-lab 1.1.0")

    private val motion = List(150) { i ->
        val t = i / 12.0
        MotionSample(
            boardMillis = i * 80L,
            ax = (0.35 * sin(t)).toFloat(),
            ay = (0.25 * sin(t * 0.7 + 1)).toFloat(),
            az = (0.95 + 0.05 * sin(t * 1.3)).toFloat(),
            gx = (40 * sin(t * 1.1)).toFloat(),
            gy = (25 * sin(t * 0.8 + 2)).toFloat(),
            gz = (15 * sin(t * 1.9)).toFloat(),
        )
    }

    private val touches = listOf(
        TouchEvent(0, TouchKind.Tap, injected = false, x = 112, y = 64),
        TouchEvent(1, TouchKind.LongPress, injected = false, x = 240, y = 180),
        TouchEvent(2, TouchKind.Tap, injected = false, x = 36, y = 210),
    )

    val ready = DeviceUiState(
        address = ADDRESS,
        name = "BLE Lab (demo)",
        connection = ConnectionState.Ready(ADDRESS, info, 247),
        samples = samples,
        latest = Telemetry(sequence = 1_204, value = samples.last(), ledOn = true, hasImu = true, hasTouch = true),
        packets = 1_205,
        ledOn = true,
        motion = motion,
        tilt = Tilt.from(motion.last()),
        touches = touches,
        message = MessageStatus.Sent("Hello from the phone"),
        recording = RecordingState.Recording(sessionId = 7, startedAtMillis = 0, telemetryCount = 214, motionCount = 1_860),
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

    /** A DevKit has neither IMU nor touch screen; the motion and touch cards degrade to explanations. */
    val devKit = ready.copy(
        latest = ready.latest?.copy(hasImu = false, hasTouch = false),
        motion = emptyList(),
        tilt = Tilt.Level,
        touches = emptyList(),
        message = MessageStatus.None,
        recording = RecordingState.Idle,
    )

    private val session = RecordingSession(
        id = 7,
        startedAtMillis = 1_762_700_000_000,
        endedAtMillis = 1_762_700_042_500,
        deviceName = "BLE Lab (demo)",
        deviceAddress = ADDRESS,
        telemetryCount = 214,
        motionCount = 1_860,
    )

    val history = HistoryUiState(
        sessions = listOf(
            session,
            session.copy(id = 6, startedAtMillis = 1_762_600_000_000, endedAtMillis = 1_762_600_015_000, deviceName = "ble-lab-esp32", deviceAddress = "24:6F:28:1A:2B:3C", telemetryCount = 76, motionCount = 0),
            session.copy(id = 5, startedAtMillis = 1_762_500_000_000, endedAtMillis = null, telemetryCount = 12, motionCount = 98),
        ),
        loaded = true,
    )

    val sessionDetail = SessionDetailUiState(
        loading = false,
        session = session,
        temperature = samples,
        acceleration = motion.map { sqrt(it.ax * it.ax + it.ay * it.ay + it.az * it.az) },
        rotation = motion.map { sqrt(it.gx * it.gx + it.gy * it.gy + it.gz * it.gz) },
    )
}
