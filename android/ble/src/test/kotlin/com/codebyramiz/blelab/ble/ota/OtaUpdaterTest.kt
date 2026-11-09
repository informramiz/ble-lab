@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codebyramiz.blelab.ble.ota

import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.protocol.OtaError
import com.codebyramiz.blelab.ble.protocol.OtaProtocol
import com.codebyramiz.blelab.ble.protocol.OtaState
import com.codebyramiz.blelab.ble.protocol.OtaStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OtaUpdaterTest {
    private fun image(size: Int) = FirmwareImage.parse(ByteArray(size) { (it % 253).toByte() }.also { it[0] = 0xE9.toByte() })!!

    /** A board that acknowledges like the firmware does, with knobs for the ways it can go wrong. */
    private class FakeLink(private val size: Int) : OtaLink {
        val out = MutableSharedFlow<OtaStatus>(extraBufferCapacity = 64)
        override val status: Flow<OtaStatus> = out
        val controls = mutableListOf<ByteArray>()
        var received = 0
        var acked = 0
        var maxAhead = 0
        var failOnStart: OtaError? = null
        var silent = false
        var failFinish: OtaError? = null
        var linkDownOnData = false

        override suspend fun writeControl(value: ByteArray) {
            controls += value
            if (silent) return
            when (value[0]) {
                OtaProtocol.CMD_START -> failOnStart?.let { out.tryEmit(OtaStatus(OtaState.Error, it, 0)) } ?: out.tryEmit(OtaStatus(OtaState.Receiving, OtaError.None, 0))
                OtaProtocol.CMD_FINISH -> out.tryEmit(failFinish?.let { OtaStatus(OtaState.Error, it, received.toLong()) } ?: OtaStatus(OtaState.Done, OtaError.None, received.toLong()))
            }
        }

        override suspend fun writeData(value: ByteArray) {
            if (linkDownOnData) throw BleException(BleError.NotConnected)
            received += value.size
            maxAhead = maxOf(maxAhead, received - acked)
            if (received == size || received - acked >= OtaProtocol.ACK_INTERVAL) {
                acked = received
                out.tryEmit(OtaStatus(OtaState.Receiving, OtaError.None, received.toLong()))
            }
        }
    }

    @Test
    fun `sends the whole image within the window and ends restarting`() = runTest {
        val img = image(20_000)
        val link = FakeLink(img.size)
        val progress = OtaUpdater(link, mtu = 247).update(img).toList()

        assertThat(progress.first()).isEqualTo(OtaProgress.Starting)
        assertThat(progress.last()).isEqualTo(OtaProgress.Restarting)
        assertThat(progress).contains(OtaProgress.Verifying)
        assertThat(progress.filterIsInstance<OtaProgress.Sending>().last()).isEqualTo(OtaProgress.Sending(20_000, 20_000))
        assertThat(link.received).isEqualTo(20_000)
        assertThat(link.maxAhead).isAtMost(OtaProtocol.WINDOW_BYTES)
        assertThat(link.controls.map { it[0] }).containsExactly(OtaProtocol.CMD_START, OtaProtocol.CMD_FINISH).inOrder()
        assertThat(link.controls.first()).isEqualTo(OtaProtocol.encodeStart(20_000, img.sha256))
    }

    @Test
    fun `progress is monotonic and bounded`() = runTest {
        val img = image(9_000)
        val sending = OtaUpdater(FakeLink(img.size), 247).update(img).toList().filterIsInstance<OtaProgress.Sending>()
        assertThat(sending.map { it.sent }).isInOrder()
        assertThat(sending.all { it.fraction in 0f..1f }).isTrue()
    }

    @Test
    fun `works at the default MTU too`() = runTest {
        val img = image(5_000)
        val link = FakeLink(img.size)
        assertThat(OtaUpdater(link, mtu = 23).update(img).toList().last()).isEqualTo(OtaProgress.Restarting)
        assertThat(link.maxAhead).isAtMost(OtaProtocol.WINDOW_BYTES)
    }

    @Test
    fun `a board refusal at start is reported and nothing is sent`() = runTest {
        val img = image(5_000)
        val link = FakeLink(img.size).apply { failOnStart = OtaError.TooLarge }
        val last = OtaUpdater(link, 247).update(img).toList().last()
        assertThat(last).isEqualTo(OtaProgress.Failed(OtaError.TooLarge.message))
        assertThat(link.received).isEqualTo(0)
        assertThat(link.controls.last()).isEqualTo(OtaProtocol.ABORT)
    }

    @Test
    fun `a hash failure at finish is reported`() = runTest {
        val img = image(3_000)
        val link = FakeLink(img.size).apply { failFinish = OtaError.Hash }
        assertThat(OtaUpdater(link, 247).update(img).toList().last()).isEqualTo(OtaProgress.Failed(OtaError.Hash.message))
    }

    @Test
    fun `a silent board stalls out instead of hanging`() = runTest {
        val img = image(3_000)
        val link = FakeLink(img.size).apply { silent = true }
        val last = OtaUpdater(link, 247, stallTimeoutMillis = 1_000).update(img).toList().last()
        assertThat(last).isEqualTo(OtaProgress.Failed("The board stopped responding"))
    }

    @Test
    fun `a link failure while streaming becomes a failed update`() = runTest {
        val img = image(3_000)
        val link = FakeLink(img.size).apply { linkDownOnData = true }
        assertThat(OtaUpdater(link, 247).update(img).toList().last()).isEqualTo(OtaProgress.Failed(BleError.NotConnected.message))
    }

    @Test
    fun `cancelling aborts the transfer on the board`() = runTest {
        val img = image(200_000)
        val link = FakeLink(img.size).apply { }
        // Never acknowledges data, so the updater parks at the window until cancelled.
        val parked = object : OtaLink by link {
            override suspend fun writeData(value: ByteArray) {
                link.received += value.size
            }
        }
        val job = launch { OtaUpdater(parked, 247, stallTimeoutMillis = 60_000).update(img).toList() }
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(link.received).isAtMost(OtaProtocol.WINDOW_BYTES)
        job.cancel()
        runCurrent()
        assertThat(link.controls.last()).isEqualTo(OtaProtocol.ABORT)
    }
}
