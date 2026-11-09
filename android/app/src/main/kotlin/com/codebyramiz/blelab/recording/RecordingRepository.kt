package com.codebyramiz.blelab.recording

import kotlinx.coroutines.flow.Flow

/** Local store for recorded sessions; the app depends on this port, Room is one adapter. */
interface RecordingRepository {
    suspend fun start(deviceAddress: String, deviceName: String?, startedAtMillis: Long): Long

    suspend fun append(sessionId: Long, telemetry: List<TelemetryPoint>, motion: List<MotionPoint>)

    suspend fun finish(sessionId: Long, endedAtMillis: Long)

    fun sessions(): Flow<List<RecordingSession>>

    suspend fun load(sessionId: Long): SessionData?

    suspend fun delete(sessionId: Long)
}
