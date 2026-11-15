package com.codebyramiz.blelab.recording

import com.codebyramiz.blelab.ble.model.MotionSample
import com.codebyramiz.blelab.ble.model.Telemetry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionRecorderTest {
    private val repository = FakeRecordingRepository()
    private var now = 1_000L
    private val recorder = SessionRecorder(repository, { now }, batchSize = 4)

    private fun reading(v: Float) = Telemetry(sequence = 1, value = v, ledOn = true)

    private fun motion(boardMillis: Long) = MotionSample(boardMillis, 0f, 0f, 1f, 0f, 0f, 0f)

    @Test
    fun `ignores samples while idle`() = runTest {
        recorder.onTelemetry(reading(20f))
        recorder.onMotion(motion(5))
        assertThat(recorder.state.value).isEqualTo(RecordingState.Idle)
        assertThat(repository.appendCalls).isEmpty()
        assertThat(recorder.stop()).isNull()
    }

    @Test
    fun `stores telemetry relative to the start time`() = runTest {
        recorder.start("AA", "Board")
        now = 1_250
        recorder.onTelemetry(reading(21.5f))
        val id = recorder.stop()
        val data = repository.load(id!!)!!
        assertThat(data.telemetry).containsExactly(TelemetryPoint(250, 21.5f, true))
        assertThat(data.session.endedAtMillis).isEqualTo(1_250)
        assertThat(data.session.deviceName).isEqualTo("Board")
    }

    @Test
    fun `motion spacing follows the board clock not the arrival time`() = runTest {
        recorder.start("AA", null)
        now = 1_100
        listOf(7_000L, 7_020L, 7_040L).forEach { recorder.onMotion(motion(it)) }
        val data = repository.load(recorder.stop()!!)!!
        assertThat(data.motion.map { it.tMillis }).containsExactly(100L, 120L, 140L).inOrder()
    }

    @Test
    fun `writes in batches and flushes the remainder on stop`() = runTest {
        recorder.start("AA", null)
        repeat(6) { recorder.onTelemetry(reading(it.toFloat())) }
        assertThat(repository.appendCalls).containsExactly(4 to 0)
        val state = recorder.state.value as RecordingState.Recording
        assertThat(state.telemetryCount).isEqualTo(6)
        recorder.stop()
        assertThat(repository.appendCalls).containsExactly(4 to 0, 2 to 0).inOrder()
        assertThat(repository.load(1)!!.session.telemetryCount).isEqualTo(6)
    }

    @Test
    fun `starting twice keeps the first session`() = runTest {
        recorder.start("AA", null)
        recorder.start("BB", null)
        assertThat((recorder.state.value as RecordingState.Recording).sessionId).isEqualTo(1)
        assertThat(repository.sessionsSnapshot()).hasSize(1)
    }

    @Test
    fun `a second recording restarts the board time origin`() = runTest {
        recorder.start("AA", null)
        recorder.onMotion(motion(100))
        recorder.stop()
        now = 5_000
        recorder.start("AA", null)
        now = 5_050
        recorder.onMotion(motion(900))
        val data = repository.load(recorder.stop()!!)!!
        assertThat(data.motion.single().tMillis).isEqualTo(50)
    }

    @Test
    fun `a board reboot during recording re-anchors motion time instead of going backwards`() = runTest {
        recorder.start("AA", null)
        now = 1_100
        recorder.onMotion(motion(60_000))
        recorder.onMotion(motion(60_020))
        now = 1_400 // the board restarted and its clock began again at 40 ms
        recorder.onMotion(motion(40))
        recorder.onMotion(motion(60))
        val data = repository.load(recorder.stop()!!)!!
        assertThat(data.motion.map { it.tMillis }).containsExactly(100L, 120L, 400L, 420L).inOrder()
    }

    @Test
    fun `the 32-bit board clock wrapping is a forward step not a reboot`() = runTest {
        recorder.start("AA", null)
        now = 1_100
        recorder.onMotion(motion(0xFFFF_FFF6L))
        recorder.onMotion(motion(10)) // 20 ms later, across the wrap
        val data = repository.load(recorder.stop()!!)!!
        assertThat(data.motion.map { it.tMillis }).containsExactly(100L, 120L).inOrder()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `samples arriving while stop is writing are ignored and never leak into the next session`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gated = object : RecordingRepository by repository {
            override suspend fun append(sessionId: Long, telemetry: List<TelemetryPoint>, motion: List<MotionPoint>) {
                gate.await()
                repository.append(sessionId, telemetry, motion)
            }
        }
        val gatedRecorder = SessionRecorder(gated, { now }, batchSize = 4)
        gatedRecorder.start("AA", null)
        gatedRecorder.onTelemetry(reading(1f))
        val stopping = launch { gatedRecorder.stop() }
        advanceUntilIdle() // stop is now suspended inside append
        gatedRecorder.onTelemetry(reading(2f))
        gatedRecorder.start("BB", null) // not allowed while stopping
        gate.complete(Unit)
        advanceUntilIdle()
        stopping.join()
        assertThat(gatedRecorder.state.value).isEqualTo(RecordingState.Idle)
        gatedRecorder.start("CC", null)
        val next = repository.load((gatedRecorder.state.value as RecordingState.Recording).sessionId)!!
        assertThat(next.telemetry).isEmpty()
        assertThat(repository.load(1)!!.telemetry).hasSize(1)
    }

    private suspend fun FakeRecordingRepository.sessionsSnapshot() = load(1)?.let { listOf(it) }.orEmpty()
}
