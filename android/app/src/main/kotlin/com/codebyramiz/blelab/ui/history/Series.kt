package com.codebyramiz.blelab.ui.history

import com.codebyramiz.blelab.recording.MotionPoint

/** Chart-ready series derived from stored rows. */
object Series {
    const val MAX_POINTS = 300

    /** Keeps at most [max] points, evenly spread, always including the first and last. */
    fun <T> downsample(items: List<T>, max: Int = MAX_POINTS): List<T> {
        if (items.size <= max || max < 2) return items
        return List(max) { i -> items[(i.toLong() * (items.size - 1) / (max - 1)).toInt()] }
    }

    /** X, Y and Z acceleration (g) as three parallel series. */
    fun acceleration(motion: List<MotionPoint>): List<List<Float>> = listOf(motion.map { it.ax }, motion.map { it.ay }, motion.map { it.az })

    /** X, Y and Z rotation rate (deg/s) as three parallel series. */
    fun rotation(motion: List<MotionPoint>): List<List<Float>> = listOf(motion.map { it.gx }, motion.map { it.gy }, motion.map { it.gz })
}
