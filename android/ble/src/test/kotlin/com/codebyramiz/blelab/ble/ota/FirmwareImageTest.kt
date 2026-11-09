package com.codebyramiz.blelab.ble.ota

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class FirmwareImageTest {
    private fun bytes(size: Int, revision: String? = "ble-lab 1.2.0"): ByteArray {
        val out = ByteArray(size) { (it % 251).toByte() }
        out[0] = 0xE9.toByte()
        revision?.encodeToByteArray()?.copyInto(out, destinationOffset = 100)
        return out
    }

    @Test
    fun `computes SHA-256 and finds the revision`() {
        val data = bytes(1000)
        val image = FirmwareImage.parse(data)!!
        assertThat(image.sha256).isEqualTo(MessageDigest.getInstance("SHA-256").digest(data))
        assertThat(image.version).isEqualTo("ble-lab 1.2.0")
        assertThat(image.size).isEqualTo(1000)
    }

    @Test
    fun `version is null when the image carries none`() {
        assertThat(FirmwareImage.parse(bytes(1000, revision = null))!!.version).isNull()
    }

    @Test
    fun `rejects empty files and files that are not ESP32 images`() {
        assertThat(FirmwareImage.parse(ByteArray(0))).isNull()
        assertThat(FirmwareImage.parse("hello".encodeToByteArray())).isNull()
    }

    @Test
    fun `chunks cover the image in order with a short last chunk`() {
        val data = bytes(1000)
        val chunks = FirmwareImage.parse(data)!!.chunks(244).toList()
        assertThat(chunks.map { it.size }).containsExactly(244, 244, 244, 244, 24).inOrder()
        assertThat(chunks.fold(ByteArray(0)) { acc, c -> acc + c }).isEqualTo(data)
    }

    @Test
    fun `an exact multiple has no empty trailing chunk`() {
        assertThat(FirmwareImage.parse(bytes(488))!!.chunks(244).toList().map { it.size }).containsExactly(244, 244)
    }

    @Test
    fun `chunk size must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { FirmwareImage.parse(bytes(200))!!.chunks(0) }
    }
}
