package com.codebyramiz.blelab.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class SessionCsvTest {
    private val session = RecordingSession(7, 0, 1_000, "Board", "AA", 2, 1)
    private val data = SessionData(
        session,
        telemetry = listOf(TelemetryPoint(0, 24.5f, false), TelemetryPoint(500, 24.75f, true)),
        motion = listOf(MotionPoint(250, 0.01f, -0.02f, 0.98f, 1.5f, -2.5f, 0f)),
    )

    @Test
    fun `rows are merged in time order under one header`() {
        val lines = SessionCsv.format(data).lines().filter { it.isNotEmpty() }
        assertThat(lines).containsExactly(
            SessionCsv.HEADER,
            "telemetry,0,24.500,0,,,,,,",
            "motion,250,,,0.010,-0.020,0.980,1.500,-2.500,0.000",
            "telemetry,500,24.750,1,,,,,,",
        ).inOrder()
    }

    @Test
    fun `every row has as many columns as the header`() {
        val columns = SessionCsv.HEADER.count { it == ',' }
        SessionCsv.format(data).lines().filter { it.isNotEmpty() }.forEach { assertThat(it.count { c -> c == ',' }).isEqualTo(columns) }
    }

    @Test
    fun `decimal separator is a dot in comma locales`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertThat(SessionCsv.format(data)).contains("24.500")
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `file name carries the session id`() {
        assertThat(SessionCsv.fileName(session)).isEqualTo("ble-lab-session-7.csv")
    }

    @Test
    fun `an empty session still has a header`() {
        assertThat(SessionCsv.format(SessionData(session, emptyList(), emptyList())).trim()).isEqualTo(SessionCsv.HEADER)
    }
}
