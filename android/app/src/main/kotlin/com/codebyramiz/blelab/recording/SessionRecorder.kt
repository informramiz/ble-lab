package com.codebyramiz.blelab.recording

import com.codebyramiz.blelab.ble.model.MotionSample
import com.codebyramiz.blelab.ble.model.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecordingState {
    data object Idle : RecordingState

    data class Recording(val sessionId: Long, val startedAtMillis: Long, val telemetryCount: Int, val motionCount: Int) : RecordingState
}

/**
 * Turns the live telemetry and motion streams into stored rows. Rows are buffered and written in
 * batches so a 50 Hz IMU does not mean 50 inserts a second. Call from one coroutine context
 * (the ViewModel's main scope); it holds no locks.
 */
class SessionRecorder(private val repository: RecordingRepository, private val clock: Clock, private val batchSize: Int = BATCH_SIZE) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val telemetryBuffer = mutableListOf<TelemetryPoint>()
    private val motionBuffer = mutableListOf<MotionPoint>()

    /** Board clock of the previous motion sample; null until the first one of a session. */
    private var lastBoardMillis: Long? = null

    /** Board time elapsed since the clock was last anchored to the phone clock. */
    private var boardElapsed = 0L
    private var anchorPhoneMillis = 0L

    /** True from the moment start/stop begin until they end, so samples and a second start cannot slip in while they suspend. */
    private var transitioning = false

    suspend fun start(deviceAddress: String, deviceName: String?) {
        if (_state.value is RecordingState.Recording || transitioning) return
        transitioning = true
        try {
            val now = clock.nowMillis()
            val id = repository.start(deviceAddress, deviceName, now)
            telemetryBuffer.clear()
            motionBuffer.clear()
            lastBoardMillis = null
            _state.value = RecordingState.Recording(id, now, 0, 0)
        } finally {
            transitioning = false
        }
    }

    suspend fun onTelemetry(reading: Telemetry) {
        val current = activeRecording() ?: return
        telemetryBuffer += TelemetryPoint(clock.nowMillis() - current.startedAtMillis, reading.value, reading.ledOn)
        _state.value = current.copy(telemetryCount = current.telemetryCount + 1)
        flushIfFull(current.sessionId)
    }

    /**
     * Motion arrives in batches that share one phone arrival time, so spacing comes from the board
     * clock. The board clock restarts at zero when the board reboots (a firmware update, a crash)
     * and wraps at 2^32 ms; a step backwards by more than half the range is a reboot, which anchors
     * the board clock to the phone clock again, while a wrap is just a small forward step.
     */
    suspend fun onMotion(sample: MotionSample) {
        val current = activeRecording() ?: return
        val last = lastBoardMillis
        val step = if (last == null) null else (sample.boardMillis - last) and BOARD_CLOCK_MASK
        if (step == null || step > BOARD_CLOCK_HALF_RANGE) {
            anchorPhoneMillis = clock.nowMillis() - current.startedAtMillis
            boardElapsed = 0L
        } else {
            boardElapsed += step
        }
        lastBoardMillis = sample.boardMillis
        motionBuffer += MotionPoint(anchorPhoneMillis + boardElapsed, sample.ax, sample.ay, sample.az, sample.gx, sample.gy, sample.gz)
        _state.value = current.copy(motionCount = current.motionCount + 1)
        flushIfFull(current.sessionId)
    }

    /** Writes what is buffered and closes the session. Returns its id, or null when nothing was recording. */
    suspend fun stop(): Long? {
        val current = activeRecording() ?: return null
        // From here on samples are ignored, so the flush below drains everything that was accepted.
        transitioning = true
        try {
            flush(current.sessionId)
            repository.finish(current.sessionId, clock.nowMillis())
            _state.value = RecordingState.Idle
        } finally {
            transitioning = false
        }
        return current.sessionId
    }

    private fun activeRecording(): RecordingState.Recording? = if (transitioning) null else _state.value as? RecordingState.Recording

    private suspend fun flushIfFull(sessionId: Long) {
        if (telemetryBuffer.size + motionBuffer.size >= batchSize) flush(sessionId)
    }

    private suspend fun flush(sessionId: Long) {
        if (telemetryBuffer.isEmpty() && motionBuffer.isEmpty()) return
        val telemetry = telemetryBuffer.toList()
        val motion = motionBuffer.toList()
        telemetryBuffer.clear()
        motionBuffer.clear()
        repository.append(sessionId, telemetry, motion)
    }

    companion object {
        const val BATCH_SIZE = 50
        private const val BOARD_CLOCK_MASK = 0xFFFF_FFFFL
        private const val BOARD_CLOCK_HALF_RANGE = 0x7FFF_FFFFL
    }
}
