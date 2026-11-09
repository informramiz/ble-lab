package com.codebyramiz.blelab.ble.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageCodecTest {
    @Test
    fun `encodes trimmed UTF-8`() {
        assertThat(MessageCodec.encode("  hello  ")!!.decodeToString()).isEqualTo("hello")
    }

    @Test
    fun `blank text is not sendable`() {
        assertThat(MessageCodec.encode("   ")).isNull()
        assertThat(MessageCodec.encode("")).isNull()
    }

    @Test
    fun `long ASCII is cut at the limit`() {
        assertThat(MessageCodec.encode("a".repeat(100))!!.size).isEqualTo(MessageCodec.MAX_BYTES)
    }

    @Test
    fun `never cuts a multi-byte character in half`() {
        // 63 ASCII bytes then a 3-byte character straddling the 64-byte limit.
        val bytes = MessageCodec.encode("a".repeat(63) + "€")!!
        assertThat(bytes.size).isEqualTo(63)
        assertThat(bytes.decodeToString()).isEqualTo("a".repeat(63))
    }

    @Test
    fun `byteLength counts bytes not characters`() {
        assertThat(MessageCodec.byteLength(" h€ ")).isEqualTo(4)
    }
}
