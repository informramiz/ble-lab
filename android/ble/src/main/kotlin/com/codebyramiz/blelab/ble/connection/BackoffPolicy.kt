package com.codebyramiz.blelab.ble.connection

import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter, so a fleet of phones does not hammer a rebooting
 * peripheral in lockstep. Attempt numbers start at 1.
 */
class BackoffPolicy(
    private val initialDelayMillis: Long = 500,
    private val maxDelayMillis: Long = 16_000,
    private val factor: Double = 2.0,
    private val jitterRatio: Double = 0.2,
    val maxAttempts: Int = 6,
    private val random: Random = Random.Default,
) {
    init {
        require(initialDelayMillis > 0 && maxDelayMillis >= initialDelayMillis) { "Invalid delay bounds" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio must be in 0..1" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    fun delayFor(attempt: Int): Long {
        require(attempt >= 1) { "attempt starts at 1" }
        val base = (initialDelayMillis * factor.pow(attempt - 1)).coerceAtMost(maxDelayMillis.toDouble())
        val jitter = if (jitterRatio == 0.0) 0.0 else base * jitterRatio * (random.nextDouble() * 2 - 1)
        return (base + jitter).toLong().coerceIn(0, maxDelayMillis)
    }
}
