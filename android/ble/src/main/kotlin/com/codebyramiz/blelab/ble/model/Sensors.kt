package com.codebyramiz.blelab.ble.model

/** One IMU reading. Acceleration in g, angular rate in degrees per second. */
data class MotionSample(
    /** Board clock in milliseconds (wraps every 49 days); only differences are meaningful. */
    val boardMillis: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float,
)

enum class TouchKind { Tap, LongPress }

/** A touch on the board's screen (320 x 240 px on the CoreS3). */
data class TouchEvent(
    /** Wrapping event counter; gaps mean missed notifications. */
    val index: Int,
    val kind: TouchKind,
    /** True when the firmware's serial test console produced it instead of a finger. */
    val injected: Boolean,
    val x: Int,
    val y: Int,
)
