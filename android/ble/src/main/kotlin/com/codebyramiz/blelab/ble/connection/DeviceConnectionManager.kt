package com.codebyramiz.blelab.ble.connection

import com.codebyramiz.blelab.ble.GattClient
import com.codebyramiz.blelab.ble.GattClientFactory
import com.codebyramiz.blelab.ble.GattEvent
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.model.DeviceInfo
import com.codebyramiz.blelab.ble.model.MotionSample
import com.codebyramiz.blelab.ble.model.Telemetry
import com.codebyramiz.blelab.ble.model.TouchEvent
import com.codebyramiz.blelab.ble.ota.FirmwareImage
import com.codebyramiz.blelab.ble.ota.OtaLink
import com.codebyramiz.blelab.ble.ota.OtaProgress
import com.codebyramiz.blelab.ble.ota.OtaUpdater
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.protocol.GattStatus
import com.codebyramiz.blelab.ble.protocol.MessageCodec
import com.codebyramiz.blelab.ble.protocol.MotionCodec
import com.codebyramiz.blelab.ble.protocol.OtaProtocol
import com.codebyramiz.blelab.ble.protocol.OtaStatus
import com.codebyramiz.blelab.ble.protocol.TelemetryCodec
import com.codebyramiz.blelab.ble.protocol.TouchCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns the connection to one peripheral and drives it through
 * `Connecting -> Discovering (MTU, services, device info, notifications) -> Ready`,
 * reconnecting with [BackoffPolicy] after GATT 133, timeouts and link loss, and pairing
 * when the peripheral answers with insufficient authentication/encryption.
 *
 * Every Android quirk lives behind [GattClient], so this whole state machine is tested
 * on the JVM with virtual time.
 */
class DeviceConnectionManager(
    private val clientFactory: GattClientFactory,
    private val scope: CoroutineScope,
    private val backoff: BackoffPolicy = BackoffPolicy(),
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _telemetry = MutableSharedFlow<Telemetry>(extraBufferCapacity = 64)
    val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()

    private val _motion = MutableSharedFlow<MotionSample>(extraBufferCapacity = 256)

    /** IMU samples, unpacked from the board's batched notifications. Silent on boards without an IMU. */
    val motion: SharedFlow<MotionSample> = _motion.asSharedFlow()

    private val _touches = MutableSharedFlow<TouchEvent>(extraBufferCapacity = 32)

    /** Touches on the board's screen. */
    val touches: SharedFlow<TouchEvent> = _touches.asSharedFlow()

    private val otaStatusEvents = MutableSharedFlow<OtaStatus>(extraBufferCapacity = 16)

    private var connectionJob: Job? = null

    @Volatile
    private var activeClient: GattClient? = null

    /** Connects to [address], replacing any current connection. */
    fun connect(address: String) {
        connectionJob?.cancel()
        connectionJob = scope.launch { runWithReconnect(address) }
    }

    /** User-initiated disconnect: stops reconnecting and returns to [ConnectionState.Idle]. */
    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        activeClient?.close()
        activeClient = null
        _state.value = ConnectionState.Idle
    }

    /** Writes the LED characteristic; pairs first if the peripheral demands an encrypted link. */
    suspend fun setLed(on: Boolean): Result<Unit> = write(GattProfile.LED, TelemetryCodec.encodeLed(on))

    /** Shows [text] on the board's screen. Blank text fails without touching the radio. */
    suspend fun sendMessage(text: String): Result<Unit> {
        val payload = MessageCodec.encode(text) ?: return Result.failure(IllegalArgumentException("Message is blank"))
        return write(GattProfile.MESSAGE, payload)
    }

    /**
     * Streams [image] to the board. Only valid while [ConnectionState.Ready] with
     * [ConnectionState.Ready.otaSupported]; otherwise the flow fails at once. When the board
     * accepts the image it restarts, the link drops, and this manager reconnects on its own,
     * so the new firmware revision shows up in the next [ConnectionState.Ready].
     */
    fun updateFirmware(image: FirmwareImage): Flow<OtaProgress> {
        val client = activeClient
        val ready = _state.value as? ConnectionState.Ready
        if (client == null || ready == null) return flowOf(OtaProgress.Failed(BleError.NotConnected.message))
        if (!ready.otaSupported) return flowOf(OtaProgress.Failed("This firmware has no update service; flash 1.3.0 over USB once"))
        val link = object : OtaLink {
            override val status: Flow<OtaStatus> = otaStatusEvents
            override suspend fun writeControl(value: ByteArray) = withBonding(client, ready.address) {
                client.write(GattProfile.OTA_SERVICE, GattProfile.OTA_CONTROL, value)
            }

            override suspend fun writeData(value: ByteArray) = client.writeWithoutResponse(GattProfile.OTA_SERVICE, GattProfile.OTA_DATA, value)
        }
        return OtaUpdater(link, ready.mtu).update(image)
    }

    private suspend fun write(characteristic: UUID, payload: ByteArray): Result<Unit> {
        val client = activeClient ?: return Result.failure(BleException(BleError.NotConnected))
        val ready = _state.value as? ConnectionState.Ready
        return try {
            withBonding(client, ready?.address) { client.write(GattProfile.LAB_SERVICE, characteristic, payload) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BleException) {
            Result.failure(e)
        } finally {
            if (ready != null && _state.value is ConnectionState.Bonding) _state.value = ready
        }
    }

    private suspend fun runWithReconnect(address: String) {
        var attempt = 0
        while (true) {
            attempt++
            _state.value = ConnectionState.Connecting(address, attempt)
            val client = clientFactory.create(address)
            val error = try {
                runSession(client, address, onReady = { attempt = 0 })
            } catch (e: CancellationException) {
                client.close()
                throw e
            } catch (e: BleException) {
                e.error
            } finally {
                activeClient = null
            }
            client.close()

            // A drop after Ready resets the counter, so a long session is not one strike from giving up.
            val nextAttempt = attempt + 1
            if (!error.retryable || attempt >= backoff.maxAttempts) {
                _state.value = ConnectionState.Failed(address, error)
                return
            }
            val wait = backoff.delayFor(nextAttempt.coerceAtLeast(1))
            _state.value = ConnectionState.Reconnecting(address, nextAttempt, wait, error)
            delay(wait)
        }
    }

    /** Runs one connection until it drops. Always ends by returning or throwing the cause. */
    private suspend fun runSession(client: GattClient, address: String, onReady: () -> Unit): BleError = coroutineScope {
        val disconnected = CompletableDeferred<Int>()
        val events = launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                when (event) {
                    is GattEvent.Disconnected -> disconnected.complete(event.status)
                    is GattEvent.Notification -> dispatch(event)
                }
            }
        }

        client.connect()
        if (!client.isBonded) {
            // Pair up front, in the foreground: left to the peripheral, Android only posts a
            // "tap to pair" notification and the pairing times out. Reconnecting is paused
            // because this runs inside the session, and a refusal ends it (BondingFailed is final).
            _state.value = ConnectionState.Bonding(address)
            pair(client)
        }
        _state.value = ConnectionState.Discovering(address)
        val mtu = try {
            client.requestMtu(GattProfile.PREFERRED_MTU)
        } catch (e: BleException) {
            // Some stacks reject MTU exchange; 23 still works, just with smaller packets.
            GattProfile.DEFAULT_MTU
        }
        val services = client.discoverServices()
        if (GattProfile.LAB_SERVICE !in services) throw BleException(BleError.ServiceNotFound)

        val info = if (GattProfile.DEVICE_INFO_SERVICE in services) {
            DeviceInfo(
                manufacturer = readString(client, GattProfile.MANUFACTURER_NAME),
                model = readString(client, GattProfile.MODEL_NUMBER),
                firmware = readString(client, GattProfile.FIRMWARE_REVISION),
            )
        } else {
            DeviceInfo(null, null, null)
        }
        withBonding(client, address) { client.enableNotifications(GattProfile.LAB_SERVICE, GattProfile.TELEMETRY) }
        // Firmware 1.0.0 has neither characteristic; a refusal there must not fail the connection.
        for (optional in listOf(GattProfile.MOTION, GattProfile.TOUCH)) {
            try {
                client.enableNotifications(GattProfile.LAB_SERVICE, optional)
            } catch (e: BleException) {
                if (e.error !is BleError.Gatt) throw e
            }
        }

        // The OTA status notifications drive the transfer's flow control. Without the service this is skipped.
        val otaSupported = GattProfile.OTA_SERVICE in services
        if (otaSupported) withBonding(client, address) { client.enableNotifications(GattProfile.OTA_SERVICE, GattProfile.OTA_STATUS) }

        activeClient = client
        _state.value = ConnectionState.Ready(address, info, mtu, otaSupported)
        onReady()

        val status = disconnected.await()
        events.cancel()
        BleError.LinkLost(status)
    }

    private fun dispatch(event: GattEvent.Notification) {
        when (event.characteristic) {
            GattProfile.TELEMETRY -> runCatching { TelemetryCodec.decode(event.value) }.onSuccess { _telemetry.tryEmit(it) }
            GattProfile.MOTION -> runCatching { MotionCodec.decode(event.value) }.onSuccess { batch -> batch.forEach { _motion.tryEmit(it) } }
            GattProfile.TOUCH -> runCatching { TouchCodec.decode(event.value) }.getOrNull()?.let { _touches.tryEmit(it) }
            GattProfile.OTA_STATUS -> OtaProtocol.decodeStatus(event.value)?.let { otaStatusEvents.tryEmit(it) }
        }
    }

    private suspend fun readString(client: GattClient, characteristic: UUID): String? = try {
        client.read(GattProfile.DEVICE_INFO_SERVICE, characteristic).decodeToString().trim().ifEmpty { null }
    } catch (e: BleException) {
        if (e.error is BleError.Gatt) null else throw e
    }

    /**
     * Runs a protected operation. With no bond it pairs first, from the app, so the write never
     * reaches the board unencrypted: on a rejected write Android's own stack would otherwise
     * start pairing behind the app's back (BluetoothGatt.onCharacteristicWrite auth retry), with
     * a notification instead of a dialog and no time budget the app controls. If the board still
     * answers insufficient authentication/encryption (it lost its keys while the phone kept the
     * bond), pairs once and retries.
     */
    private suspend fun <T> withBonding(client: GattClient, address: String?, block: suspend () -> T): T {
        if (!client.isBonded) pairShowingState(client, address)
        return try {
            block()
        } catch (e: BleException) {
            val status = (e.error as? BleError.Gatt)?.status ?: throw e
            if (!GattStatus.needsBonding(status)) throw e
            pairShowingState(client, address)
            block()
        }
    }

    private suspend fun pairShowingState(client: GattClient, address: String?) {
        if (address != null) _state.value = ConnectionState.Bonding(address)
        pair(client)
    }

    /**
     * Runs system pairing. A failed or timed-out wait is not the last word: the bond can still
     * complete (the user types the passkey late, or the link blips while the dialog is open),
     * so the stored bond state decides. Only a device that is really not bonded fails, and that
     * failure is final ([BleError.BondingFailed] is not retryable). A dropped link that leaves
     * a bond behind is caught by the next GATT operation and handled by the normal reconnect.
     */
    private suspend fun pair(client: GattClient) {
        try {
            client.createBond()
        } catch (e: BleException) {
            if (!client.isBonded) throw BleException(BleError.BondingFailed)
        }
    }
}
