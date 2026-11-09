package com.codebyramiz.blelab.ble.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class OtaProtocolTest {
    @Test
    fun `start command is 37 bytes - command, size LE, hash`() {
        val hash = ByteArray(32) { it.toByte() }
        val bytes = OtaProtocol.encodeStart(0x01020304L, hash)
        assertThat(bytes.size).isEqualTo(37)
        assertThat(bytes.take(5)).containsExactly(0x01.toByte(), 0x04.toByte(), 0x03.toByte(), 0x02.toByte(), 0x01.toByte()).inOrder()
        assertThat(bytes.copyOfRange(5, 37)).isEqualTo(hash)
    }

    @Test
    fun `start rejects an empty image and a wrong hash length`() {
        assertThrows(IllegalArgumentException::class.java) { OtaProtocol.encodeStart(0, ByteArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { OtaProtocol.encodeStart(10, ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { OtaProtocol.encodeStart(0x1_0000_0000L, ByteArray(32)) }
    }

    @Test
    fun `abort and finish are single bytes`() {
        assertThat(OtaProtocol.ABORT).isEqualTo(byteArrayOf(2))
        assertThat(OtaProtocol.FINISH).isEqualTo(byteArrayOf(3))
    }

    @Test
    fun `decodes status`() {
        val status = OtaProtocol.decodeStatus(byteArrayOf(1, 0, 0x00, 0x04, 0x00, 0x00))!!
        assertThat(status).isEqualTo(OtaStatus(OtaState.Receiving, OtaError.None, 1024))
    }

    @Test
    fun `decodes a high offset without sign trouble`() {
        val status = OtaProtocol.decodeStatus(byteArrayOf(1, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))!!
        assertThat(status.offset).isEqualTo(0xFFFF_FFFFL)
    }

    @Test
    fun `decodes an error status`() {
        val status = OtaProtocol.decodeStatus(byteArrayOf(3, 8, 0, 0, 0, 0))!!
        assertThat(status.state).isEqualTo(OtaState.Error)
        assertThat(status.error).isEqualTo(OtaError.Hash)
    }

    @Test
    fun `unknown error code maps to Unknown`() {
        assertThat(OtaProtocol.decodeStatus(byteArrayOf(3, 99, 0, 0, 0, 0))!!.error).isEqualTo(OtaError.Unknown)
    }

    @Test
    fun `malformed status is null`() {
        assertThat(OtaProtocol.decodeStatus(ByteArray(5))).isNull()
        assertThat(OtaProtocol.decodeStatus(byteArrayOf(9, 0, 0, 0, 0, 0))).isNull()
    }

    @Test
    fun `chunk size is MTU minus the ATT header, never below one`() {
        assertThat(OtaProtocol.chunkSize(247)).isEqualTo(244)
        assertThat(OtaProtocol.chunkSize(23)).isEqualTo(20)
        assertThat(OtaProtocol.chunkSize(1)).isEqualTo(1)
    }

    @Test
    fun `every error has a message`() {
        OtaError.entries.forEach { assertThat(it.message).isNotEmpty() }
        assertThat(OtaState.from(2)).isEqualTo(OtaState.Done)
    }
}
