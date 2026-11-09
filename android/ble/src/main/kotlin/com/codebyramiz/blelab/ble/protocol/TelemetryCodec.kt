package com.codebyramiz.blelab.ble.protocol

import com.codebyramiz.blelab.ble.model.Telemetry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format of the telemetry characteristic, little-endian, 7 bytes:
 *
 * | offset | type   | field                         |
 * |--------|--------|-------------------------------|
 * | 0      | uint32 | sequence                      |
 * | 4      | int16  | value x 100 (centi-units)     |
 * | 6      | uint8  | flags, bit 0 = LED on         |
 */
object TelemetryCodec {
    const val SIZE = 7
    private const val FLAG_LED = 0x01

    fun decode(bytes: ByteArray): Telemetry {
        require(bytes.size >= SIZE) { "Telemetry payload must be at least $SIZE bytes, was ${bytes.size}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = buffer.int.toLong() and 0xFFFF_FFFFL
        val centi = buffer.short
        val flags = buffer.get().toInt()
        return Telemetry(sequence = sequence, value = centi / 100f, ledOn = flags and FLAG_LED != 0)
    }

    fun encode(telemetry: Telemetry): ByteArray = ByteBuffer.allocate(SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(telemetry.sequence.toInt())
        .putShort(Math.round(telemetry.value * 100).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        .put(if (telemetry.ledOn) FLAG_LED.toByte() else 0)
        .array()

    fun encodeLed(on: Boolean): ByteArray = byteArrayOf(if (on) 1 else 0)
}
