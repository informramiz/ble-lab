package com.codebyramiz.blelab.ble.simulated

import com.codebyramiz.blelab.ble.BleScanner
import com.codebyramiz.blelab.ble.GattClient
import com.codebyramiz.blelab.ble.GattEvent
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.model.MotionSample
import com.codebyramiz.blelab.ble.model.ScanUpdate
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.ble.model.Telemetry
import com.codebyramiz.blelab.ble.model.TouchEvent
import com.codebyramiz.blelab.ble.model.TouchKind
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.protocol.GattStatus
import com.codebyramiz.blelab.ble.protocol.MessageCodec
import com.codebyramiz.blelab.ble.protocol.MotionCodec
import com.codebyramiz.blelab.ble.protocol.TelemetryCodec
import com.codebyramiz.blelab.ble.protocol.TouchCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * An in-memory ESP32 running the BLE Lab firmware. It backs the app's demo device (so
 * the app is usable with no hardware) and every JVM test and screenshot. Failure knobs
 * reproduce the field problems the connection manager must survive.
 */
class SimulatedPeripheral(
    val address: String = ADDRESS,
    val name: String = "BLE Lab (demo)",
    private val notifyIntervalMillis: Long = 200,
    private val latencyMillis: Long = 120,
    seed: Int = 7,
    /** True simulates a CoreS3 (motion and touch); false an IMU-less DevKit, which must degrade gracefully. */
    val hasBoardSensors: Boolean = true,
) {
    /** Fail the next N connection attempts with GATT 133. */
    @Volatile var connectFailuresRemaining: Int = 0

    /** Reject LED writes with insufficient authentication until bonded. */
    @Volatile var requireBonding: Boolean = false

    /** When false, [GattClient.createBond] fails as if the user tapped Cancel. */
    @Volatile var acceptBonding: Boolean = true

    @Volatile var bonded: Boolean = false
        private set

    @Volatile var ledOn: Boolean = false
        private set

    /** Every LED payload the central wrote, oldest first. */
    val ledWrites: MutableList<ByteArray> = mutableListOf()

    /** Every message the central wrote, decoded, oldest first. */
    val messages: MutableList<String> = mutableListOf()

    private val injectedTouches = MutableSharedFlow<TouchEvent>(extraBufferCapacity = 8)

    /** Makes the board report a touch, as if someone pressed its screen. */
    fun touch(x: Int, y: Int, kind: TouchKind = TouchKind.Tap) {
        injectedTouches.tryEmit(TouchEvent(touchCount++, kind, injected = false, x = x, y = y))
    }

    private var touchCount = 0

    var connectAttempts: Int = 0
        private set

    private val random = Random(seed)
    private var current: Client? = null

    /** Drops the link of the connected client, as if the board was reset or walked out of range. */
    fun dropLink(status: Int = GattStatus.CONNECTION_TIMEOUT) {
        current?.disconnects?.tryEmit(status)
    }

    fun newClient(): GattClient = Client().also { current = it }

    fun scanner(clock: () -> Long = System::currentTimeMillis, intervalMillis: Long = 1_000): BleScanner = BleScanner { filter ->
        flow {
            if (filter != null && filter != GattProfile.LAB_SERVICE) return@flow
            while (true) {
                val rssi = -52 + random.nextInt(-4, 5)
                emit(ScanUpdate.Found(ScannedDevice(address, name, rssi, isBleLab = true, lastSeenMillis = clock(), isSimulated = true)))
                delay(intervalMillis)
            }
        }
    }

    /** Synthetic sensor: a slow sine around 24 degrees with a little noise. */
    internal fun sample(sequence: Long): Telemetry {
        val wave = 24f + 3f * sin(2 * PI * sequence / 50.0).toFloat()
        val noise = (random.nextFloat() - 0.5f) * 0.4f
        return Telemetry(sequence, wave + noise, ledOn, hasImu = hasBoardSensors, hasTouch = hasBoardSensors)
    }

    /** Synthetic IMU: the board slowly rocks about both axes, so the tilt visual and charts move. */
    internal fun motionSample(index: Long, periodMillis: Int): MotionSample {
        val t = index * periodMillis / 1000.0
        val roll = 0.5 * sin(2 * PI * t / 6.0)
        val pitch = 0.35 * cos(2 * PI * t / 9.0)
        return MotionSample(
            boardMillis = index * periodMillis,
            ax = sin(pitch).toFloat(),
            ay = sin(roll).toFloat(),
            az = (cos(pitch) * cos(roll)).toFloat(),
            gx = (-pitch * 20).toFloat(),
            gy = (roll * 20).toFloat(),
            gz = (random.nextFloat() - 0.5f) * 2f,
        )
    }

    private inner class Client : GattClient {
        val disconnects = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        private val notifying = MutableStateFlow(emptySet<UUID>())
        private var connected = false

        @OptIn(ExperimentalCoroutinesApi::class)
        override val events: Flow<GattEvent> = merge(
            disconnects.map<Int, GattEvent> { status ->
                connected = false
                notifying.value = emptySet()
                GattEvent.Disconnected(status)
            },
            notifying.flatMapLatest { enabled ->
                merge(
                    if (GattProfile.TELEMETRY in enabled) telemetryEvents() else emptyFlow(),
                    if (GattProfile.MOTION in enabled) motionEvents() else emptyFlow(),
                    if (GattProfile.TOUCH in enabled) touchEvents() else emptyFlow(),
                )
            },
        )

        private fun telemetryEvents(): Flow<GattEvent> = flow {
            var sequence = 0L
            while (true) {
                delay(notifyIntervalMillis)
                emit(GattEvent.Notification(GattProfile.TELEMETRY, TelemetryCodec.encode(sample(sequence++))))
            }
        }

        private fun motionEvents(): Flow<GattEvent> = flow {
            var index = 0L
            while (true) {
                delay(MOTION_BATCH * MOTION_PERIOD_MILLIS.toLong())
                val batch = List(MOTION_BATCH) { motionSample(index++, MOTION_PERIOD_MILLIS) }
                emit(GattEvent.Notification(GattProfile.MOTION, MotionCodec.encode(batch, (index - MOTION_BATCH).toInt(), MOTION_PERIOD_MILLIS)))
            }
        }

        private fun touchEvents(): Flow<GattEvent> = injectedTouches.map { GattEvent.Notification(GattProfile.TOUCH, TouchCodec.encode(it)) }

        override suspend fun connect() {
            delay(latencyMillis * 3)
            connectAttempts++
            if (connectFailuresRemaining > 0) {
                connectFailuresRemaining--
                throw BleException(BleError.Gatt(GattStatus.GATT_ERROR))
            }
            connected = true
        }

        override suspend fun requestMtu(mtu: Int): Int = op { minOf(mtu, GattProfile.PREFERRED_MTU) }

        override suspend fun discoverServices(): Set<UUID> = op { setOf(GattProfile.LAB_SERVICE, GattProfile.DEVICE_INFO_SERVICE) }

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray = op {
            when (characteristic) {
                GattProfile.MANUFACTURER_NAME -> "Espressif".encodeToByteArray()
                GattProfile.MODEL_NUMBER -> (if (hasBoardSensors) "M5Stack CoreS3" else "ESP32-WROOM-32").encodeToByteArray()
                GattProfile.FIRMWARE_REVISION -> "ble-lab 1.1.0".encodeToByteArray()
                GattProfile.LED -> TelemetryCodec.encodeLed(ledOn)
                GattProfile.TELEMETRY -> TelemetryCodec.encode(sample(0))
                else -> throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE))
            }
        }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) = op {
            if (characteristic != GattProfile.LED && characteristic != GattProfile.MESSAGE) {
                throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE))
            }
            if (requireBonding && !bonded) throw BleException(BleError.Gatt(GattStatus.INSUFFICIENT_AUTHENTICATION))
            if (characteristic == GattProfile.MESSAGE) {
                messages += value.decodeToString()
            } else {
                ledWrites += value
                ledOn = value.firstOrNull() == 1.toByte()
            }
        }

        override suspend fun enableNotifications(service: UUID, characteristic: UUID) = op {
            val supported = characteristic == GattProfile.TELEMETRY || hasBoardSensors
            if (!supported) throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE))
            notifying.value += characteristic
        }

        override suspend fun createBond() {
            delay(latencyMillis * 4)
            if (!acceptBonding) throw BleException(BleError.BondingFailed)
            bonded = true
        }

        override fun close() {
            connected = false
            notifying.value = emptySet()
            if (current === this) current = null
        }

        private suspend fun <T> op(block: () -> T): T {
            delay(latencyMillis)
            if (!connected) throw BleException(BleError.NotConnected)
            return block()
        }
    }

    companion object {
        /** From the IEEE documentation range (00:00:5E:00:53:xx), so it can never collide with a real radio. */
        private const val MOTION_PERIOD_MILLIS = 20
        private const val MOTION_BATCH = 5

        const val ADDRESS = "00:00:5E:00:53:B1"
    }
}
