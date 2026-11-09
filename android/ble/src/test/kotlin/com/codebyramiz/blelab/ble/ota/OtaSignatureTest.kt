package com.codebyramiz.blelab.ble.ota

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class OtaSignatureTest {
    private val sha = ByteArray(32) { it.toByte() }

    @Test
    fun `manifest binds context, size, version and image hash`() {
        val manifest = OtaSignature.manifest(0x01020304L, 0x0A0B0C0DL, sha)
        assertThat(manifest.size).isEqualTo(5 + 4 + 4 + 32)
        assertThat(manifest.copyOfRange(0, 5)).isEqualTo("BLFW1".encodeToByteArray())
        assertThat(manifest.copyOfRange(5, 9)).isEqualTo(byteArrayOf(4, 3, 2, 1))
        assertThat(manifest.copyOfRange(9, 13)).isEqualTo(byteArrayOf(0x0D, 0x0C, 0x0B, 0x0A))
        assertThat(manifest.copyOfRange(13, 45)).isEqualTo(sha)
    }

    @Test
    fun `version code is major, minor, patch`() {
        assertThat(OtaSignature.versionCode("ble-lab 1.3.0")).isEqualTo(10_300L)
        assertThat(OtaSignature.versionCode("ble-lab 12.34.56")).isEqualTo(123_456L)
        assertThat(OtaSignature.versionCode("garbage")).isNull()
        assertThat(OtaSignature.versionCode(null)).isNull()
    }

    @Test
    fun `a signature verifies for the signer and for nobody else`() {
        val signer = OtaTestSigner()
        val signature = signer.sign(1234, 10_300, sha)
        assertThat(signer.verifier.verify(1234, 10_300, sha, signature)).isTrue()
        assertThat(OtaTestSigner().verifier.verify(1234, 10_300, sha, signature)).isFalse()
    }

    @Test
    fun `changing size, version or hash breaks the signature`() {
        val signer = OtaTestSigner()
        val signature = signer.sign(1234, 10_300, sha)
        assertThat(signer.verifier.verify(1235, 10_300, sha, signature)).isFalse()
        assertThat(signer.verifier.verify(1234, 10_301, sha, signature)).isFalse()
        assertThat(signer.verifier.verify(1234, 10_300, ByteArray(32), signature)).isFalse()
    }

    @Test
    fun `blank and truncated signatures never verify`() {
        val signer = OtaTestSigner()
        assertThat(signer.verifier.verify(1234, 10_300, sha, ByteArray(64))).isFalse()
        assertThat(signer.verifier.verify(1234, 10_300, sha, ByteArray(10))).isFalse()
    }

    @Test
    fun `a signature made by sign_firmware py with the development key verifies against the firmware key`() {
        val signature = "9104a01d911c06d94d5f3107af217379267ff24fae5d793b6ba1a03142e70546285bcfdd8a4e26918397f278ce58412b60e511d7901be38de2988460d5adbbdb"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertThat(OtaSignature.verify(OtaSignature.FIRMWARE_DEV_KEY, 1234, 10_300, sha, signature)).isTrue()
        assertThat(OtaSignature.verify(OtaSignature.FIRMWARE_DEV_KEY, 1234, 10_299, sha, signature)).isFalse()
    }

    @Test
    fun `only an uncompressed P-256 point is a public key`() {
        assertThrows(IllegalArgumentException::class.java) { OtaSignature.publicKey(ByteArray(65)) }
        assertThrows(IllegalArgumentException::class.java) { OtaSignature.publicKey(ByteArray(33)) }
    }
}
