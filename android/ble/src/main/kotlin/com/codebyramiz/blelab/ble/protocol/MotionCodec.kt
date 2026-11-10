package com.codebyramiz.blelab.ble.protocol

import com.codebyramiz.blelab.ble.model.MotionSample
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format of the motion characteristic: a batch of samples per notification, little-endian.
 *
 * Header, 8 bytes: `uint32 t_ms` (board clock at the first sample), `uint16 first index`,
 * `uint8 count`, `uint8 period_ms`. Then `count` x 12 bytes: `int16 ax, ay, az` in milli-g and
 * `int16 gx, gy, gz` in 0.1 deg/s. Sample `i` is taken at `t_ms + i * period_ms`.
 */
object MotionCodec {
    const val HEADER_SIZE = 8
    const val SAMPLE_SIZE = 12

    /** Returns the samples of one notification; a truncated or empty batch yields only the complete samples. */
    fun decode(bytes: ByteArray): List<MotionSample> {
        require(bytes.size >= HEADER_SIZE) { "Motion payload must be at least $HEADER_SIZE bytes, was ${bytes.size}" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val startMillis = buffer.int.toLong() and 0xFFFF_FFFFL
        buffer.short // first sample index: consumers detect loss from the timestamps instead
        val count = buffer.get().toInt() and 0xFF
        val period = buffer.get().toInt() and 0xFF
        val complete = minOf(count, (bytes.size - HEADER_SIZE) / SAMPLE_SIZE)
        return List(complete) { i ->
            MotionSample(
                boardMillis = (startMillis + i.toLong() * period) and 0xFFFF_FFFFL,
                ax = buffer.short / 1000f,
                ay = buffer.short / 1000f,
                az = buffer.short / 1000f,
                gx = buffer.short / 10f,
                gy = buffer.short / 10f,
                gz = buffer.short / 10f,
            )
        }
    }

    /** Inverse of [decode], used by the simulated board and tests. All samples share [periodMillis]. */
    fun encode(samples: List<MotionSample>, index: Int, periodMillis: Int): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + samples.size * SAMPLE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt((samples.firstOrNull()?.boardMillis ?: 0L).toInt())
        buffer.putShort(index.toShort())
        buffer.put(samples.size.toByte())
        buffer.put(periodMillis.toByte())
        for (s in samples) {
            buffer.putShort(milli(s.ax)).putShort(milli(s.ay)).putShort(milli(s.az))
            buffer.putShort(deci(s.gx)).putShort(deci(s.gy)).putShort(deci(s.gz))
        }
        return buffer.array()
    }

    private fun milli(g: Float): Short = Math.round(g * 1000).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun deci(dps: Float): Short = Math.round(dps * 10).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
