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
    private var boardOrigin: Long? = null
    private var phoneOriginForBoard = 0L

    suspend fun start(deviceAddress: String, deviceName: String?) {
        if (_state.value is RecordingState.Recording) return
        val now = clock.nowMillis()
        val id = repository.start(deviceAddress, deviceName, now)
        boardOrigin = null
        _state.value = RecordingState.Recording(id, now, 0, 0)
    }

    suspend fun onTelemetry(reading: Telemetry) {
        val current = _state.value as? RecordingState.Recording ?: return
        telemetryBuffer += TelemetryPoint(clock.nowMillis() - current.startedAtMillis, reading.value, reading.ledOn)
        _state.value = current.copy(telemetryCount = current.telemetryCount + 1)
        flushIfFull(current.sessionId)
    }

    /** Motion arrives in batches that share one phone arrival time, so spacing comes from the board clock. */
    suspend fun onMotion(sample: MotionSample) {
        val current = _state.value as? RecordingState.Recording ?: return
        val origin = boardOrigin ?: sample.boardMillis.also {
            boardOrigin = it
            phoneOriginForBoard = clock.nowMillis() - current.startedAtMillis
        }
        motionBuffer += MotionPoint(phoneOriginForBoard + (sample.boardMillis - origin), sample.ax, sample.ay, sample.az, sample.gx, sample.gy, sample.gz)
        _state.value = current.copy(motionCount = current.motionCount + 1)
        flushIfFull(current.sessionId)
    }

    /** Writes what is buffered and closes the session. Returns its id, or null when nothing was recording. */
    suspend fun stop(): Long? {
        val current = _state.value as? RecordingState.Recording ?: return null
        flush(current.sessionId)
        repository.finish(current.sessionId, clock.nowMillis())
        _state.value = RecordingState.Idle
        return current.sessionId
    }

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
    }
}
