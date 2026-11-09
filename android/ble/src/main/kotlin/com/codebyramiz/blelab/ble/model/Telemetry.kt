package com.codebyramiz.blelab.ble.model

/** One sample streamed by the peripheral. */
data class Telemetry(
    /** Monotonic sample counter; gaps mean dropped notifications. */
    val sequence: Long,
    /** Sensor reading in the peripheral's unit (°C on chips with a sensor, a synthetic wave otherwise). */
    val value: Float,
    val ledOn: Boolean,
)
