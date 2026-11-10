package com.codebyramiz.blelab.recording

/** A recorded run against one device. [endedAtMillis] is null while the recording is still open. */
data class RecordingSession(
    val id: Long,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val deviceName: String?,
    val deviceAddress: String,
    val telemetryCount: Int,
    val motionCount: Int,
) {
    val durationMillis: Long? get() = endedAtMillis?.let { it - startedAtMillis }
}

/** Temperature-style reading, [tMillis] counted from the start of the session. */
data class TelemetryPoint(val tMillis: Long, val value: Float, val ledOn: Boolean)

/** One IMU sample, [tMillis] counted from the start of the session. Acceleration in g, rate in deg/s. */
data class MotionPoint(val tMillis: Long, val ax: Float, val ay: Float, val az: Float, val gx: Float, val gy: Float, val gz: Float)

/** Everything stored for one session, ordered by time. */
data class SessionData(val session: RecordingSession, val telemetry: List<TelemetryPoint>, val motion: List<MotionPoint>)

/** Wall-clock source, injected so recording timestamps are deterministic in tests. */
fun interface Clock {
    fun nowMillis(): Long
}
