package com.codebyramiz.blelab.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Insert
    suspend fun insertTelemetry(rows: List<TelemetryEntity>)

    @Insert
    suspend fun insertMotion(rows: List<MotionEntity>)

    @Query("UPDATE sessions SET endedAtMillis = :endedAtMillis WHERE id = :sessionId")
    suspend fun finish(sessionId: Long, endedAtMillis: Long)

    /** Sample times are relative to the session start, so the end is start plus the latest sample time. */
    @Query(
        """
        UPDATE sessions SET endedAtMillis = startedAtMillis + MAX(
            COALESCE((SELECT MAX(tMillis) FROM telemetry t WHERE t.sessionId = sessions.id), 0),
            COALESCE((SELECT MAX(tMillis) FROM motion m WHERE m.sessionId = sessions.id), 0)
        ) WHERE endedAtMillis IS NULL
        """,
    )
    suspend fun closeOrphans()

    @Query(
        """
        SELECT s.id, s.startedAtMillis, s.endedAtMillis, s.deviceName, s.deviceAddress,
               (SELECT COUNT(*) FROM telemetry t WHERE t.sessionId = s.id) AS telemetryCount,
               (SELECT COUNT(*) FROM motion m WHERE m.sessionId = s.id) AS motionCount
        FROM sessions s ORDER BY s.startedAtMillis DESC
        """,
    )
    fun observeSessions(): Flow<List<SessionSummaryRow>>

    @Query(
        """
        SELECT s.id, s.startedAtMillis, s.endedAtMillis, s.deviceName, s.deviceAddress,
               (SELECT COUNT(*) FROM telemetry t WHERE t.sessionId = s.id) AS telemetryCount,
               (SELECT COUNT(*) FROM motion m WHERE m.sessionId = s.id) AS motionCount
        FROM sessions s WHERE s.id = :sessionId
        """,
    )
    suspend fun session(sessionId: Long): SessionSummaryRow?

    @Query("SELECT * FROM telemetry WHERE sessionId = :sessionId ORDER BY tMillis, id")
    suspend fun telemetry(sessionId: Long): List<TelemetryEntity>

    @Query("SELECT * FROM motion WHERE sessionId = :sessionId ORDER BY tMillis, id")
    suspend fun motion(sessionId: Long): List<MotionEntity>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: Long)
}
