package com.codebyramiz.blelab.data

import com.codebyramiz.blelab.recording.MotionPoint
import com.codebyramiz.blelab.recording.RecordingRepository
import com.codebyramiz.blelab.recording.RecordingSession
import com.codebyramiz.blelab.recording.SessionData
import com.codebyramiz.blelab.recording.TelemetryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [RecordingRepository] backed by Room. Room runs suspend queries off the main thread itself. */
class RoomRecordingRepository(private val dao: RecordingDao) : RecordingRepository {
    override suspend fun start(deviceAddress: String, deviceName: String?, startedAtMillis: Long): Long = dao.insertSession(SessionEntity(startedAtMillis = startedAtMillis, endedAtMillis = null, deviceName = deviceName, deviceAddress = deviceAddress))

    override suspend fun append(sessionId: Long, telemetry: List<TelemetryPoint>, motion: List<MotionPoint>) {
        if (telemetry.isNotEmpty()) dao.insertTelemetry(telemetry.map { TelemetryEntity(sessionId = sessionId, tMillis = it.tMillis, value = it.value, ledOn = it.ledOn) })
        if (motion.isNotEmpty()) dao.insertMotion(motion.map { MotionEntity(sessionId = sessionId, tMillis = it.tMillis, ax = it.ax, ay = it.ay, az = it.az, gx = it.gx, gy = it.gy, gz = it.gz) })
    }

    override suspend fun finish(sessionId: Long, endedAtMillis: Long) = dao.finish(sessionId, endedAtMillis)

    override suspend fun closeOrphans() = dao.closeOrphans()

    override fun sessions(): Flow<List<RecordingSession>> = dao.observeSessions().map { rows -> rows.map { it.toModel() } }

    override suspend fun load(sessionId: Long): SessionData? {
        val session = dao.session(sessionId)?.toModel() ?: return null
        return SessionData(
            session = session,
            telemetry = dao.telemetry(sessionId).map { TelemetryPoint(it.tMillis, it.value, it.ledOn) },
            motion = dao.motion(sessionId).map { MotionPoint(it.tMillis, it.ax, it.ay, it.az, it.gx, it.gy, it.gz) },
        )
    }

    override suspend fun delete(sessionId: Long) = dao.delete(sessionId)

    private fun SessionSummaryRow.toModel() = RecordingSession(id, startedAtMillis, endedAtMillis, deviceName, deviceAddress, telemetryCount, motionCount)
}
