package com.codebyramiz.blelab.recording

import java.util.Locale

/** CSV export of a session: one row per sample, telemetry and motion share the time column. */
object SessionCsv {
    const val HEADER = "kind,t_ms,value,led,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps"

    fun format(data: SessionData): String = buildString {
        appendLine(HEADER)
        val rows = data.telemetry.map { it.tMillis to telemetryRow(it) } + data.motion.map { it.tMillis to motionRow(it) }
        rows.sortedBy { it.first }.forEach { appendLine(it.second) }
    }

    fun fileName(session: RecordingSession): String = "ble-lab-session-${session.id}.csv"

    private fun telemetryRow(p: TelemetryPoint) = "telemetry,${p.tMillis},${num(p.value)},${if (p.ledOn) 1 else 0},,,,,,"

    private fun motionRow(p: MotionPoint) = "motion,${p.tMillis},,,${num(p.ax)},${num(p.ay)},${num(p.az)},${num(p.gx)},${num(p.gy)},${num(p.gz)}"

    // Locale.ROOT so a comma-decimal locale cannot corrupt the CSV.
    private fun num(v: Float) = String.format(Locale.ROOT, "%.3f", v)
}
