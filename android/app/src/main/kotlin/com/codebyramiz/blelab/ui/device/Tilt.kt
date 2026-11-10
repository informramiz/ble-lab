package com.codebyramiz.blelab.ui.device

import com.codebyramiz.blelab.ble.model.MotionSample
import kotlin.math.atan2
import kotlin.math.sqrt

/** Board orientation from gravity, in degrees. Roll is about the X axis, pitch about Y. */
data class Tilt(val rollDegrees: Float, val pitchDegrees: Float) {
    companion object {
        val Level = Tilt(0f, 0f)

        fun from(sample: MotionSample): Tilt {
            val roll = atan2(sample.ay, sample.az)
            val pitch = atan2(-sample.ax, sqrt(sample.ay * sample.ay + sample.az * sample.az))
            return Tilt(Math.toDegrees(roll.toDouble()).toFloat(), Math.toDegrees(pitch.toDouble()).toFloat())
        }
    }
}
