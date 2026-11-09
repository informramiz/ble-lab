package com.codebyramiz.blelab.ble.protocol

import com.codebyramiz.blelab.ble.model.MotionSample
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

class MotionCodecTest {
    private fun sample(ms: Long, ax: Float = 0f) = MotionSample(ms, ax, 0.5f, 1f, 12.3f, -45.6f, 0f)

    @Test
    fun `decodes a hand-built batch`() {
        // t=0x01020304, index 7, 1 sample, 20 ms; ax=1000 mg, ay=-500, az=0, gx=100 (10 dps), gy=-100, gz=1
        val bytes = byteArrayOf(
            4, 3, 2, 1, 7, 0, 1, 20,
            0xE8.toByte(), 0x03, 0x0C, 0xFE.toByte(), 0, 0, 100, 0, 0x9C.toByte(), 0xFF.toByte(), 1, 0,
        )
        val s = MotionCodec.decode(bytes).single()
        assertThat(s.boardMillis).isEqualTo(0x01020304L)
        assertThat(s.ax).isWithin(1e-6f).of(1f)
        assertThat(s.ay).isWithin(1e-6f).of(-0.5f)
        assertThat(s.gx).isWithin(1e-6f).of(10f)
        assertThat(s.gy).isWithin(1e-6f).of(-10f)
        assertThat(s.gz).isWithin(1e-6f).of(0.1f)
    }

    @Test
    fun `spaces samples by the period and round trips`() {
        val batch = List(5) { sample(1_000L + it * 20, ax = it / 10f) }
        val decoded = MotionCodec.decode(MotionCodec.encode(batch, index = 3, periodMillis = 20))
        assertThat(decoded.map { it.boardMillis }).containsExactly(1_000L, 1_020L, 1_040L, 1_060L, 1_080L).inOrder()
        assertThat(decoded[2].ax).isWithin(1e-3f).of(0.2f)
        assertThat(decoded[4].gy).isWithin(0.1f).of(-45.6f)
    }

    @Test
    fun `an empty batch has no samples`() {
        assertThat(MotionCodec.decode(MotionCodec.encode(emptyList(), 0, 20))).isEmpty()
    }

    @Test
    fun `a truncated batch keeps only the complete samples`() {
        val full = MotionCodec.encode(List(3) { sample(it * 20L) }, 0, 20)
        assertThat(MotionCodec.decode(full.copyOf(full.size - 5))).hasSize(2)
    }

    @Test
    fun `clamps out-of-range values instead of wrapping`() {
        val bytes = MotionCodec.encode(listOf(sample(0, ax = 100f)), 0, 20)
        assertThat(MotionCodec.decode(bytes).single().ax).isWithin(1e-3f).of(Short.MAX_VALUE / 1000f)
    }

    @Test
    fun `rejects a payload shorter than the header`() {
        assertFailsWith<IllegalArgumentException> { MotionCodec.decode(ByteArray(7)) }
    }
}
