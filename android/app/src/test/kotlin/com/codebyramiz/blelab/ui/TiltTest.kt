package com.codebyramiz.blelab.ui

import com.codebyramiz.blelab.ble.model.MotionSample
import com.codebyramiz.blelab.ui.device.Tilt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TiltTest {
    private fun at(ax: Float, ay: Float, az: Float) = MotionSample(0, ax, ay, az, 0f, 0f, 0f)

    @Test
    fun `flat board is level`() {
        val tilt = Tilt.from(at(0f, 0f, 1f))
        assertThat(tilt.rollDegrees).isWithin(0.01f).of(0f)
        assertThat(tilt.pitchDegrees).isWithin(0.01f).of(0f)
    }

    @Test
    fun `gravity on Y axis is ninety degrees of roll`() {
        assertThat(Tilt.from(at(0f, 1f, 0f)).rollDegrees).isWithin(0.01f).of(90f)
    }

    @Test
    fun `gravity on negative X axis is positive pitch`() {
        assertThat(Tilt.from(at(-0.5f, 0f, 0.866f)).pitchDegrees).isWithin(0.1f).of(30f)
    }
}
