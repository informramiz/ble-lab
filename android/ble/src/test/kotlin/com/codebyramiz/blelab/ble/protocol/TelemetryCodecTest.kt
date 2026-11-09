package com.codebyramiz.blelab.ble.protocol

import com.codebyramiz.blelab.ble.model.Telemetry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.test.assertFailsWith

class TelemetryCodecTest {
    @Test
    fun `round trips a reading`() {
        val reading = Telemetry(sequence = 42, value = 23.57f, ledOn = true)
        assertThat(TelemetryCodec.decode(TelemetryCodec.encode(reading))).isEqualTo(reading)
    }

    @Test
    fun `uses the little-endian layout the firmware sends`() {
        // seq=0x01020304, value=-1.5 (-150 = 0xFF6A), flags=LED
        val bytes = byteArrayOf(0x04, 0x03, 0x02, 0x01, 0x6A, 0xFF.toByte(), 0x01)
        assertThat(TelemetryCodec.decode(bytes)).isEqualTo(Telemetry(0x01020304, -1.5f, ledOn = true))
    }

    @Test
    fun `reads sequence as unsigned`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0)
        assertThat(TelemetryCodec.decode(bytes).sequence).isEqualTo(4_294_967_295L)
    }

    @Test
    fun `clamps values outside int16`() {
        val encoded = TelemetryCodec.encode(Telemetry(1, 1_000f, ledOn = false))
        assertThat(TelemetryCodec.decode(encoded).value).isEqualTo(327.67f)
    }

    @Test
    fun `rejects short payloads`() {
        assertFailsWith<IllegalArgumentException> { TelemetryCodec.decode(ByteArray(6)) }
    }

    @Test
    fun `encodes the LED command`() {
        assertThat(TelemetryCodec.encodeLed(true)).isEqualTo(byteArrayOf(1))
        assertThat(TelemetryCodec.encodeLed(false)).isEqualTo(byteArrayOf(0))
    }

    @Test
    fun `decodes the sensor capability flags`() {
        val bytes = TelemetryCodec.encode(Telemetry(1, 20f, ledOn = false, hasImu = true, hasTouch = true))
        assertThat(bytes.last()).isEqualTo(0x06.toByte())
        assertThat(TelemetryCodec.decode(bytes)).isEqualTo(Telemetry(1, 20f, ledOn = false, hasImu = true, hasTouch = true))
    }

    @Test
    fun `firmware 1_0 flags decode as no sensors`() {
        val decoded = TelemetryCodec.decode(TelemetryCodec.encode(Telemetry(1, 20f, ledOn = true)))
        assertThat(decoded.hasImu).isFalse()
        assertThat(decoded.hasTouch).isFalse()
    }
}
