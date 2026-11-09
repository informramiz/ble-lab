package com.codebyramiz.blelab.ble.protocol

import com.codebyramiz.blelab.ble.model.TouchEvent
import com.codebyramiz.blelab.ble.model.TouchKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format of the touch characteristic, 8 bytes little-endian: `uint16 index`, `uint8 kind`
 * (1 = tap, 2 = long press), `uint8 flags` (bit 0 = injected from the serial console),
 * `uint16 x`, `uint16 y` in screen pixels.
 */
object TouchCodec {
    const val SIZE = 8
    private const val FLAG_INJECTED = 0x01

    /** Returns null for an unknown kind, so a newer firmware adding gestures cannot crash an older app. */
    fun decode(bytes: ByteArray): TouchEvent? {
        require(bytes.size >= SIZE) { "Touch payload must be at least $SIZE bytes, was ${bytes.size}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val index = buffer.short.toInt() and 0xFFFF
        val kind = when (buffer.get().toInt()) {
            1 -> TouchKind.Tap
            2 -> TouchKind.LongPress
            else -> return null
        }
        val flags = buffer.get().toInt()
        val x = buffer.short.toInt() and 0xFFFF
        val y = buffer.short.toInt() and 0xFFFF
        return TouchEvent(index, kind, injected = flags and FLAG_INJECTED != 0, x = x, y = y)
    }

    fun encode(event: TouchEvent): ByteArray = ByteBuffer.allocate(SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(event.index.toShort())
        .put(if (event.kind == TouchKind.Tap) 1 else 2)
        .put(if (event.injected) FLAG_INJECTED.toByte() else 0)
        .putShort(event.x.toShort())
        .putShort(event.y.toShort())
        .array()
}
