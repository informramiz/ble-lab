package com.codebyramiz.blelab.ui.history

import com.codebyramiz.blelab.recording.MotionPoint
import kotlin.math.sqrt

/** Chart-ready series derived from stored rows. */
object Series {
    const val MAX_POINTS = 300

    /** Keeps at most [max] points, evenly spread, always including the first and last. */
    fun <T> downsample(items: List<T>, max: Int = MAX_POINTS): List<T> {
        if (items.size <= max || max < 2) return items
        return List(max) { i -> items[(i.toLong() * (items.size - 1) / (max - 1)).toInt()] }
    }

    fun accelerationMagnitude(motion: List<MotionPoint>): List<Float> = motion.map { magnitude(it.ax, it.ay, it.az) }

    fun rotationMagnitude(motion: List<MotionPoint>): List<Float> = motion.map { magnitude(it.gx, it.gy, it.gz) }

    private fun magnitude(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
}
