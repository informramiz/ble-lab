package com.codebyramiz.blelab.ble.model

/** One sample streamed by the peripheral. */
data class Telemetry(
    /** Monotonic sample counter; gaps mean dropped notifications. */
    val sequence: Long,
    /** Sensor reading in the peripheral's unit (°C on chips with a sensor, a synthetic wave otherwise). */
    val value: Float,
    val ledOn: Boolean,
    /** The board has a motion sensor, so the motion characteristic will notify (firmware 1.1.0+). */
    val hasImu: Boolean = false,
    /** The board has a touchscreen, so the touch characteristic will notify (firmware 1.1.0+). */
    val hasTouch: Boolean = false,
)
