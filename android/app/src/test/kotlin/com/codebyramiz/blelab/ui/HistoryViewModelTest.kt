@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codebyramiz.blelab.ui

import androidx.lifecycle.SavedStateHandle
import com.codebyramiz.blelab.MainDispatcherRule
import com.codebyramiz.blelab.recording.FakeRecordingRepository
import com.codebyramiz.blelab.recording.MotionPoint
import com.codebyramiz.blelab.recording.TelemetryPoint
import com.codebyramiz.blelab.ui.history.HistoryViewModel
import com.codebyramiz.blelab.ui.history.Series
import com.codebyramiz.blelab.ui.history.SessionDetailViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val repository = FakeRecordingRepository()

    @Test
    fun `lists sessions newest first and drops deleted ones`() = runTest(main.dispatcher) {
        val older = repository.start("AA", "Old", 1_000)
        val newer = repository.start("BB", "New", 2_000)
        val vm = HistoryViewModel(repository)
        val job = launch { vm.state.collect {} }
        assertThat(vm.state.value.loaded).isTrue()
        assertThat(vm.state.value.sessions.map { it.id }).containsExactly(newer, older).inOrder()
        vm.delete(newer)
        assertThat(vm.state.value.sessions.map { it.id }).containsExactly(older)
        job.cancel()
    }

    @Test
    fun `empty repository is loaded with no sessions`() = runTest(main.dispatcher) {
        val vm = HistoryViewModel(repository)
        val job = launch { vm.state.collect {} }
        assertThat(vm.state.value.loaded).isTrue()
        assertThat(vm.state.value.sessions).isEmpty()
        job.cancel()
    }

    @Test
    fun `session detail builds chart series and a csv`() = runTest(main.dispatcher) {
        val id = repository.start("AA", "Board", 1_000)
        repository.append(id, listOf(TelemetryPoint(0, 20f, false), TelemetryPoint(100, 21f, true)), listOf(MotionPoint(0, 3f, 4f, 0f, 0f, 0f, 2f)))
        repository.finish(id, 1_500)
        val vm = SessionDetailViewModel(SavedStateHandle(mapOf("sessionId" to id)), repository)
        runCurrent()
        val state = vm.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.notFound).isFalse()
        assertThat(state.temperature).containsExactly(20f, 21f).inOrder()
        assertThat(state.acceleration).containsExactly(listOf(3f), listOf(4f), listOf(0f)).inOrder()
        assertThat(state.rotation).containsExactly(2f)
        val (name, csv) = checkNotNull(vm.csv())
        assertThat(name).isEqualTo("ble-lab-session-$id.csv")
        assertThat(csv.lines().first()).startsWith("kind,t_ms")
    }

    @Test
    fun `session detail reports a missing session`() = runTest(main.dispatcher) {
        val vm = SessionDetailViewModel(SavedStateHandle(mapOf("sessionId" to 99L)), repository)
        runCurrent()
        assertThat(vm.state.value.notFound).isTrue()
        assertThat(vm.csv()).isNull()
    }

    @Test
    fun `downsample keeps the ends and the requested count`() {
        val out = Series.downsample((0..999).toList(), max = 10)
        assertThat(out).hasSize(10)
        assertThat(out.first()).isEqualTo(0)
        assertThat(out.last()).isEqualTo(999)
        assertThat(Series.downsample(listOf(1, 2, 3), max = 10)).containsExactly(1, 2, 3).inOrder()
    }
}
