package com.codebyramiz.blelab.ble.ota

import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.protocol.OtaError
import com.codebyramiz.blelab.ble.protocol.OtaProtocol
import com.codebyramiz.blelab.ble.protocol.OtaState
import com.codebyramiz.blelab.ble.protocol.OtaStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The three OTA characteristics, as the updater needs them. */
interface OtaLink {
    /** Status notifications from the board. Must be hot: a notification is lost if nobody collects. */
    val status: Flow<OtaStatus>

    /** Write with response to the control characteristic. */
    suspend fun writeControl(value: ByteArray)

    /** Write without response to the data characteristic. */
    suspend fun writeData(value: ByteArray)
}

sealed interface OtaProgress {
    data object Starting : OtaProgress

    /** [sent] bytes are confirmed by the board, of [total]. */
    data class Sending(val sent: Long, val total: Long) : OtaProgress {
        val fraction: Float get() = if (total == 0L) 0f else (sent.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Every byte is in; the board is checking the SHA-256 and activating the image. */
    data object Verifying : OtaProgress

    /** The board accepted the image and is restarting into it. */
    data object Restarting : OtaProgress

    data class Failed(val reason: String) : OtaProgress
}

/**
 * Pushes a [FirmwareImage] to the board over the OTA service with flow control: it never
 * runs more than [OtaProtocol.WINDOW_BYTES] ahead of the offset the board last reported,
 * so the board's receive path is never overrun. The flow ends after [OtaProgress.Restarting]
 * or [OtaProgress.Failed]; cancelling it aborts the transfer on the board.
 */
class OtaUpdater(
    private val link: OtaLink,
    private val mtu: Int,
    private val stallTimeoutMillis: Long = STALL_TIMEOUT_MILLIS,
) {
    private class Failure(val reason: String) : Exception(reason)

    fun update(image: FirmwareImage): Flow<OtaProgress> = flow {
        emit(OtaProgress.Starting)
        var finished = false
        try {
            coroutineScope {
                val latest = MutableStateFlow<OtaStatus?>(null)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { link.status.collect { latest.value = it } }
                transfer(image, latest) { emit(it) }
                finished = true
                collector.cancel()
            }
            emit(OtaProgress.Restarting)
        } catch (e: Failure) {
            emit(OtaProgress.Failed(e.reason))
        } catch (e: BleException) {
            emit(OtaProgress.Failed(e.error.message))
        } finally {
            if (!finished) withContext(NonCancellable) { runCatching { link.writeControl(OtaProtocol.ABORT) } }
        }
    }

    private suspend fun transfer(image: FirmwareImage, latest: MutableStateFlow<OtaStatus?>, emit: suspend (OtaProgress) -> Unit) {
        val total = image.size.toLong()
        link.writeControl(OtaProtocol.encodeStart(total, image.sha256, image.versionCode, image.signatureOrBlank))
        await(latest, START_TIMEOUT_FACTOR * stallTimeoutMillis) { it.state == OtaState.Receiving }

        var sent = 0L
        var acked = 0L
        emit(OtaProgress.Sending(0, total))
        for (chunk in image.chunks(OtaProtocol.chunkSize(mtu))) {
            while (sent + chunk.size - acked > OtaProtocol.WINDOW_BYTES) {
                val status = await(latest, stallTimeoutMillis) { it.offset > acked }
                acked = status.offset
                emit(OtaProgress.Sending(acked, total))
            }
            link.writeData(chunk)
            sent += chunk.size
            latest.value?.let { failIfError(it) }
        }
        if (acked < total) {
            val status = await(latest, stallTimeoutMillis) { it.offset >= total }
            acked = status.offset
        }
        emit(OtaProgress.Sending(acked, total))
        emit(OtaProgress.Verifying)
        link.writeControl(OtaProtocol.FINISH)
        await(latest, stallTimeoutMillis) { it.state == OtaState.Done }
    }

    /** Suspends until a status satisfying [wanted] arrives; a board error or [timeoutMillis] without one fails the update. */
    private suspend fun await(latest: MutableStateFlow<OtaStatus?>, timeoutMillis: Long, wanted: (OtaStatus) -> Boolean): OtaStatus {
        val status = withTimeoutOrNull(timeoutMillis) {
            latest.filterNotNull().first { it.state == OtaState.Error || wanted(it) }
        } ?: throw Failure("The board stopped responding")
        failIfError(status)
        return status
    }

    private fun failIfError(status: OtaStatus) {
        if (status.state == OtaState.Error) throw Failure(status.error.takeIf { it != OtaError.None }?.message ?: "The board reported an error")
    }

    private companion object {
        const val STALL_TIMEOUT_MILLIS = 10_000L

        /** The board erases flash while starting, which takes longer than a normal acknowledgement. */
        const val START_TIMEOUT_FACTOR = 2L
    }
}
