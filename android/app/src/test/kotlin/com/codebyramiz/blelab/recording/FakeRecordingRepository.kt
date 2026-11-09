package com.codebyramiz.blelab.recording

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [RecordingRepository] for ViewModel and recorder tests. */
class FakeRecordingRepository : RecordingRepository {
    private class Stored(var session: RecordingSession, val telemetry: MutableList<TelemetryPoint> = mutableListOf(), val motion: MutableList<MotionPoint> = mutableListOf())

    private val store = MutableStateFlow<Map<Long, Stored>>(emptyMap())
    private var nextId = 1L
    val appendCalls = mutableListOf<Pair<Int, Int>>()

    private fun Stored.summary() = session.copy(telemetryCount = telemetry.size, motionCount = motion.size)

    override suspend fun start(deviceAddress: String, deviceName: String?, startedAtMillis: Long): Long {
        val id = nextId++
        store.value = store.value + (id to Stored(RecordingSession(id, startedAtMillis, null, deviceName, deviceAddress, 0, 0)))
        return id
    }

    override suspend fun append(sessionId: Long, telemetry: List<TelemetryPoint>, motion: List<MotionPoint>) {
        appendCalls += telemetry.size to motion.size
        val stored = checkNotNull(store.value[sessionId])
        stored.telemetry += telemetry
        stored.motion += motion
        store.value = store.value.toMap()
    }

    override suspend fun finish(sessionId: Long, endedAtMillis: Long) {
        val stored = checkNotNull(store.value[sessionId])
        stored.session = stored.session.copy(endedAtMillis = endedAtMillis)
        store.value = store.value.toMap()
    }

    override suspend fun closeOrphans() {
        store.value.values.filter { it.session.endedAtMillis == null }.forEach {
            val last = maxOf(it.telemetry.maxOfOrNull { p -> p.tMillis } ?: 0L, it.motion.maxOfOrNull { p -> p.tMillis } ?: 0L)
            it.session = it.session.copy(endedAtMillis = it.session.startedAtMillis + last)
        }
        store.value = store.value.toMap()
    }

    override fun sessions(): Flow<List<RecordingSession>> = store.map { map -> map.values.map { it.summary() }.sortedByDescending { it.startedAtMillis } }

    override suspend fun load(sessionId: Long): SessionData? = store.value[sessionId]?.let { SessionData(it.summary(), it.telemetry.toList(), it.motion.toList()) }

    override suspend fun delete(sessionId: Long) {
        store.value = store.value - sessionId
    }
}
