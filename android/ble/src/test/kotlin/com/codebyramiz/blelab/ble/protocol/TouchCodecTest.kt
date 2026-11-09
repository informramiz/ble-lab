package com.codebyramiz.blelab.ble.protocol

import com.codebyramiz.blelab.ble.model.TouchEvent
import com.codebyramiz.blelab.ble.model.TouchKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

class TouchCodecTest {
    @Test
    fun `decodes a hand-built tap`() {
        val bytes = byteArrayOf(0x34, 0x12, 1, 0, 0x40, 0x01, 0xEF.toByte(), 0)
        assertThat(TouchCodec.decode(bytes)).isEqualTo(TouchEvent(0x1234, TouchKind.Tap, injected = false, x = 320, y = 239))
    }

    @Test
    fun `round trips a long press from the serial console`() {
        val event = TouchEvent(9, TouchKind.LongPress, injected = true, x = 10, y = 20)
        assertThat(TouchCodec.decode(TouchCodec.encode(event))).isEqualTo(event)
    }

    @Test
    fun `an unknown gesture is ignored rather than crashing`() {
        assertThat(TouchCodec.decode(byteArrayOf(0, 0, 9, 0, 0, 0, 0, 0))).isNull()
    }

    @Test
    fun `rejects a short payload`() {
        assertFailsWith<IllegalArgumentException> { TouchCodec.decode(ByteArray(5)) }
    }
}
