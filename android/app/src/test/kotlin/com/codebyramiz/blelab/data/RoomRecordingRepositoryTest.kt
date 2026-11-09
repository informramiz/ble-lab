package com.codebyramiz.blelab.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codebyramiz.blelab.recording.MotionPoint
import com.codebyramiz.blelab.recording.TelemetryPoint
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRecordingRepositoryTest {
    private lateinit var database: RecordingDatabase
    private lateinit var repository: RoomRecordingRepository

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder<RecordingDatabase>(context).setDriver(AndroidSQLiteDriver()).build()
        repository = RoomRecordingRepository(database.recordingDao())
    }

    @After
    fun close() = database.close()

    @Test
    fun `round trips a session with both sample kinds`() = runBlocking {
        val id = repository.start("AA:BB", "Board", 1_000)
        repository.append(id, listOf(TelemetryPoint(10, 24f, true), TelemetryPoint(5, 23f, false)), listOf(MotionPoint(7, 0.1f, 0.2f, 0.9f, 1f, 2f, 3f)))
        repository.finish(id, 2_000)

        val data = repository.load(id)!!
        assertThat(data.session.endedAtMillis).isEqualTo(2_000)
        assertThat(data.session.durationMillis).isEqualTo(1_000)
        assertThat(data.session.telemetryCount).isEqualTo(2)
        assertThat(data.session.motionCount).isEqualTo(1)
        assertThat(data.telemetry.map { it.tMillis }).containsExactly(5L, 10L).inOrder()
        assertThat(data.motion.single().gz).isEqualTo(3f)
    }

    @Test
    fun `sessions are listed newest first with counts`() = runBlocking {
        val old = repository.start("AA", null, 100)
        val recent = repository.start("BB", "Two", 900)
        repository.append(old, listOf(TelemetryPoint(0, 1f, false)), emptyList())

        val list = repository.sessions().first()
        assertThat(list.map { it.id }).containsExactly(recent, old).inOrder()
        assertThat(list.last().telemetryCount).isEqualTo(1)
        assertThat(list.first().endedAtMillis).isNull()
    }

    @Test
    fun `empty append writes nothing`() = runBlocking {
        val id = repository.start("AA", null, 0)
        repository.append(id, emptyList(), emptyList())
        assertThat(repository.load(id)!!.telemetry).isEmpty()
    }

    @Test
    fun `deleting a session removes its samples`() = runBlocking {
        val id = repository.start("AA", null, 0)
        repository.append(id, listOf(TelemetryPoint(0, 1f, false)), listOf(MotionPoint(0, 0f, 0f, 1f, 0f, 0f, 0f)))
        repository.delete(id)

        assertThat(repository.load(id)).isNull()
        assertThat(database.recordingDao().telemetry(id)).isEmpty()
        assertThat(database.recordingDao().motion(id)).isEmpty()
        assertThat(repository.sessions().first()).isEmpty()
    }

    @Test
    fun `loading an unknown session returns null`() = runBlocking {
        assertThat(repository.load(99)).isNull()
    }

    @Test
    fun `closeOrphans ends open sessions at their last sample and leaves finished ones`() = runBlocking {
        val open = repository.start("AA", null, 1_000)
        repository.append(open, listOf(TelemetryPoint(300, 20f, false)), listOf(MotionPoint(900, 0f, 0f, 1f, 0f, 0f, 0f)))
        val empty = repository.start("BB", null, 5_000)
        val done = repository.start("CC", null, 7_000)
        repository.append(done, listOf(TelemetryPoint(50, 20f, false)), emptyList())
        repository.finish(done, 8_000)

        repository.closeOrphans()

        assertThat(repository.load(open)!!.session.endedAtMillis).isEqualTo(1_900)
        assertThat(repository.load(empty)!!.session.endedAtMillis).isEqualTo(5_000)
        assertThat(repository.load(done)!!.session.endedAtMillis).isEqualTo(8_000)
    }
}
