package com.codebyramiz.blelab.ble.ota

import com.codebyramiz.blelab.ble.connection.BackoffPolicy
import com.codebyramiz.blelab.ble.connection.DeviceConnectionManager
import com.codebyramiz.blelab.ble.protocol.OtaError
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The accept and reject paths of the signed update, end to end through the connection manager and the simulated board. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedOtaSecurityTest {
    private val signer = OtaTestSigner()
    private val peripheral = SimulatedPeripheral(otaVerifier = signer.verifier)

    private suspend fun TestScope.update(file: ByteArray): OtaProgress {
        val manager = DeviceConnectionManager({ peripheral.newClient() }, backgroundScope, BackoffPolicy(500, jitterRatio = 0.0, maxAttempts = 4))
        manager.connect(peripheral.address)
        advanceTimeBy(5_000)
        return manager.updateFirmware(FirmwareImage.parse(file)!!).toList().last()
    }

    @Test
    fun `an image signed by the trusted key is accepted`() = runTest {
        assertThat(update(signer.pack(OtaTestSigner.image(), 10_300))).isEqualTo(OtaProgress.Restarting)
    }

    @Test
    fun `an unsigned image is refused before any data is sent`() = runTest {
        val result = update(OtaTestSigner.image())
        assertThat(result).isEqualTo(OtaProgress.Failed(OtaError.BadSignature.message))
        assertThat(peripheral.otaMaxBytesAhead).isEqualTo(0)
    }

    @Test
    fun `an image signed by another key is refused`() = runTest {
        val result = update(OtaTestSigner().pack(OtaTestSigner.image(), 10_300))
        assertThat(result).isEqualTo(OtaProgress.Failed(OtaError.BadSignature.message))
    }

    @Test
    fun `a version code changed after signing is refused`() = runTest {
        val bundle = signer.pack(OtaTestSigner.image(), 10_300)
        bundle[4] = (bundle[4] + 1).toByte()
        assertThat(update(bundle)).isEqualTo(OtaProgress.Failed(OtaError.BadSignature.message))
    }

    @Test
    fun `a validly signed image older than the running firmware is refused as a downgrade`() = runTest {
        peripheral.firmwareRevision = "ble-lab 1.4.0"
        val result = update(signer.pack(OtaTestSigner.image(revision = "ble-lab 1.3.0"), 10_300))
        assertThat(result).isEqualTo(OtaProgress.Failed(OtaError.Downgrade.message))
    }

    @Test
    fun `reinstalling the running version is allowed`() = runTest {
        peripheral.firmwareRevision = "ble-lab 1.3.0"
        assertThat(update(signer.pack(OtaTestSigner.image(), 10_300))).isEqualTo(OtaProgress.Restarting)
    }
}
