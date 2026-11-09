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
import com.codebyramiz.blelab.ble.protocol.GattProfile
import com.codebyramiz.blelab.ble.protocol.GattStatus
import com.codebyramiz.blelab.ble.protocol.MessageCodec
import com.codebyramiz.blelab.ble.protocol.MotionCodec
import com.codebyramiz.blelab.ble.protocol.TelemetryCodec
import com.codebyramiz.blelab.ble.protocol.TouchCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

        activeClient = client
        _state.value = ConnectionState.Ready(address, info, mtu)
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
        }
    }

    private suspend fun readString(client: GattClient, characteristic: UUID): String? = try {
        client.read(GattProfile.DEVICE_INFO_SERVICE, characteristic).decodeToString().trim().ifEmpty { null }
    } catch (e: BleException) {
        if (e.error is BleError.Gatt) null else throw e
    }

    /** Runs [block]; on insufficient authentication/encryption, pairs once and retries. */
    private suspend fun <T> withBonding(client: GattClient, address: String?, block: suspend () -> T): T = try {
        block()
    } catch (e: BleException) {
        val status = (e.error as? BleError.Gatt)?.status ?: throw e
        if (!GattStatus.needsBonding(status)) throw e
        if (address != null) _state.value = ConnectionState.Bonding(address)
        try {
            client.createBond()
        } catch (bondFailure: BleException) {
            throw BleException(BleError.BondingFailed)
        }
        block()
    }
}
