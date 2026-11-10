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
import com.codebyramiz.blelab.ble.ota.EcdsaOtaVerifier
import com.codebyramiz.blelab.ble.ota.OtaSignature
import com.codebyramiz.blelab.ble.ota.OtaSignatureVerifier
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.protocol.GattStatus
import com.codebyramiz.blelab.ble.protocol.MessageCodec
import com.codebyramiz.blelab.ble.protocol.MotionCodec
import com.codebyramiz.blelab.ble.protocol.OtaError
import com.codebyramiz.blelab.ble.protocol.OtaProtocol
import com.codebyramiz.blelab.ble.protocol.OtaState
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
import kotlinx.coroutines.flow.transform
import java.security.MessageDigest
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
    /** False simulates firmware older than 1.2.0, which has no update service. */
    val supportsOta: Boolean = true,
    /** Bytes the simulated update slot holds; larger images are refused like the board does. */
    private val otaSlotBytes: Long = 3L * 1024 * 1024,
    /** Pause before each simulated data write; keeps a demo transfer to a few seconds. */
    private val otaWriteDelayMillis: Long = 2,
    /** Plays the board's compiled-in public key; the default trusts the firmware's development key. */
    private val otaVerifier: OtaSignatureVerifier = EcdsaOtaVerifier(OtaSignature.FIRMWARE_DEV_KEY),
) {
    /** Fail the next N connection attempts with GATT 133. */
    @Volatile var connectFailuresRemaining: Int = 0

    /** Reject LED writes with insufficient authentication until bonded. */
    @Volatile var requireBonding: Boolean = false

    /** When false, [GattClient.createBond] fails as if the user tapped Cancel. */
    @Volatile var acceptBonding: Boolean = true

    /**
     * When true, [GattClient.createBond] reports a failure but the bond is stored anyway: the
     * user typed the passkey after the app stopped waiting, or the link blipped mid-pairing.
     */
    @Volatile var bondLandsAfterFailedWait: Boolean = false

    @Volatile var bonded: Boolean = false
        private set

    /** Drops the stored bond, as the user does by unpairing in system settings. */
    fun forgetBond() {
        bonded = false
    }

    @Volatile var ledOn: Boolean = false
        private set

    /** Every LED payload the central wrote, oldest first. */
    val ledWrites: MutableList<ByteArray> = mutableListOf()

    /** Every message the central wrote, decoded, oldest first. */
    val messages: MutableList<String> = mutableListOf()

    /** Device Information firmware revision; becomes [otaResultRevision] after an accepted OTA and reboot. */
    @Volatile var firmwareRevision: String = "ble-lab 1.1.0"

    /** What the board reports after it has restarted into an accepted image. */
    @Volatile var otaResultRevision: String = "ble-lab 1.3.0"

    /** Largest number of bytes the central ever had in flight beyond the last acknowledged offset. */
    @Volatile var otaMaxBytesAhead: Long = 0
        private set

    /** Images fully accepted so far. */
    @Volatile var otaCompleted: Int = 0
        private set

    @Volatile private var otaState = OtaState.Idle

    @Volatile private var otaError = OtaError.None
    private var otaOffset = 0L
    private var otaAcked = 0L
    private var otaSize = 0L
    private var otaHash = ByteArray(0)
    private var otaDigest: MessageDigest? = null
    private val otaOut = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)

    private fun otaStatusBytes(): ByteArray = ByteArray(OtaProtocol.STATUS_LENGTH).also {
        it[0] = otaState.code.toByte()
        it[1] = otaError.code.toByte()
        for (i in 0 until 4) it[2 + i] = (otaOffset shr (8 * i)).toByte()
    }

    private fun otaNotify() {
        otaAcked = otaOffset
        otaOut.tryEmit(otaStatusBytes())
    }

    private fun otaFail(error: OtaError) {
        otaState = OtaState.Error
        otaError = error
        otaDigest = null
        otaNotify()
    }

    private fun otaReset() {
        otaState = OtaState.Idle
        otaError = OtaError.None
        otaOffset = 0
        otaAcked = 0
        otaDigest = null
    }

    private fun otaControl(value: ByteArray) {
        when (value.firstOrNull()) {
            OtaProtocol.CMD_START -> {
                if (value.size != OtaProtocol.START_LENGTH) return otaFail(OtaError.BadCommand)
                if (otaState == OtaState.Receiving) return otaFail(OtaError.BadState)
                otaReset()
                otaSize = (0 until 4).sumOf { (value[1 + it].toLong() and 0xFF) shl (8 * it) }
                otaHash = value.copyOfRange(5, 5 + OtaProtocol.SHA256_LENGTH)
                if (otaSize == 0L || otaSize > otaSlotBytes) return otaFail(OtaError.TooLarge)
                val versionAt = 5 + OtaProtocol.SHA256_LENGTH
                val versionCode = (0 until 4).sumOf { (value[versionAt + it].toLong() and 0xFF) shl (8 * it) }
                // Same order as the firmware: authenticity first, then anti-rollback, all before any flash write.
                if (!otaVerifier.verify(otaSize, versionCode, otaHash, value.copyOfRange(versionAt + 4, value.size))) return otaFail(OtaError.BadSignature)
                if (versionCode < (OtaSignature.versionCode(firmwareRevision) ?: 0)) return otaFail(OtaError.Downgrade)
                otaDigest = MessageDigest.getInstance("SHA-256")
                otaState = OtaState.Receiving
                otaNotify()
            }

            OtaProtocol.CMD_ABORT -> {
                otaReset()
                otaError = OtaError.None
            }

            OtaProtocol.CMD_FINISH -> when {
                otaState != OtaState.Receiving -> otaFail(OtaError.BadState)

                otaOffset < otaSize -> otaFail(OtaError.Incomplete)

                !otaDigest!!.digest().contentEquals(otaHash) -> otaFail(OtaError.Hash)

                else -> {
                    otaState = OtaState.Done
                    otaCompleted++
                    otaNotify()
                }
            }

            else -> otaFail(OtaError.BadCommand)
        }
    }

    private fun otaData(value: ByteArray) {
        if (otaState != OtaState.Receiving) return
        otaMaxBytesAhead = maxOf(otaMaxBytesAhead, otaOffset + value.size - otaAcked)
        if (otaOffset + value.size > otaSize) return otaFail(OtaError.Overflow)
        otaDigest!!.update(value)
        otaOffset += value.size
        if (otaOffset == otaSize || otaOffset - otaAcked >= OtaProtocol.ACK_INTERVAL) otaNotify()
    }

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
                if (otaState == OtaState.Receiving) otaReset()
                connected = false
                notifying.value = emptySet()
                GattEvent.Disconnected(status)
            },
            notifying.flatMapLatest { enabled ->
                merge(
                    if (GattProfile.TELEMETRY in enabled) telemetryEvents() else emptyFlow(),
                    if (GattProfile.MOTION in enabled) motionEvents() else emptyFlow(),
                    if (GattProfile.TOUCH in enabled) touchEvents() else emptyFlow(),
                    if (GattProfile.OTA_STATUS in enabled) otaEvents() else emptyFlow(),
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

        /** Status notifications; after DONE the board restarts a moment later and the link drops. */
        private fun otaEvents(): Flow<GattEvent> = otaOut.transform { status ->
            emit(GattEvent.Notification(GattProfile.OTA_STATUS, status))
            if (status[0] == OtaState.Done.code.toByte()) {
                delay(OTA_REBOOT_MILLIS)
                firmwareRevision = otaResultRevision
                otaReset()
                disconnects.tryEmit(GattStatus.CONNECTION_TIMEOUT)
            }
        }

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

        override suspend fun discoverServices(): Set<UUID> = op { setOf(GattProfile.LAB_SERVICE, GattProfile.DEVICE_INFO_SERVICE) + (if (supportsOta) setOf(GattProfile.OTA_SERVICE) else emptySet()) }

        override suspend fun read(service: UUID, characteristic: UUID): ByteArray = op {
            when (characteristic) {
                GattProfile.MANUFACTURER_NAME -> "Espressif".encodeToByteArray()
                GattProfile.MODEL_NUMBER -> (if (hasBoardSensors) "M5Stack CoreS3" else "ESP32-WROOM-32").encodeToByteArray()
                GattProfile.FIRMWARE_REVISION -> firmwareRevision.encodeToByteArray()
                GattProfile.OTA_STATUS -> if (supportsOta) otaStatusBytes() else throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE))
                GattProfile.LED -> TelemetryCodec.encodeLed(ledOn)
                GattProfile.TELEMETRY -> TelemetryCodec.encode(sample(0))
                else -> throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE))
            }
        }

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) = op {
            if (characteristic == GattProfile.OTA_CONTROL && supportsOta) {
                if (requireBonding && !bonded) throw BleException(BleError.Gatt(GattStatus.INSUFFICIENT_AUTHENTICATION))
                return@op otaControl(value)
            }
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

        override suspend fun writeWithoutResponse(service: UUID, characteristic: UUID, value: ByteArray) {
            delay(otaWriteDelayMillis)
            if (!connected) throw BleException(BleError.NotConnected)
            if (characteristic != GattProfile.OTA_DATA || !supportsOta) throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE))
            otaData(value)
        }

        override suspend fun enableNotifications(service: UUID, characteristic: UUID) = op {
            val supported = characteristic == GattProfile.TELEMETRY || (characteristic == GattProfile.OTA_STATUS && supportsOta) || (hasBoardSensors && characteristic != GattProfile.OTA_STATUS)
            if (!supported) throw BleException(BleError.Gatt(GattStatus.GATT_FAILURE))
            notifying.value += characteristic
        }

        override val isBonded: Boolean get() = bonded || !requireBonding

        override suspend fun createBond() {
            delay(latencyMillis * 4)
            if (!acceptBonding) throw BleException(BleError.BondingFailed)
            bonded = true
            if (bondLandsAfterFailedWait) throw BleException(BleError.BondingFailed)
        }

        override fun close() {
            if (otaState == OtaState.Receiving) otaReset()
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

        /** Matches `OTA_REBOOT_DELAY_MS` in the firmware. */
        private const val OTA_REBOOT_MILLIS = 1_000L

        const val ADDRESS = "00:00:5E:00:53:B1"
    }
}
