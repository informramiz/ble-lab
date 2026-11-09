package com.codebyramiz.blelab.ui.components

import kotlin.math.abs
import kotlin.math.max

/** Visible value range of a chart's y axis. */
data class YRange(val min: Float, val max: Float) {
    val span: Float get() = max - min

    /** 0 at [min], 1 at [max]. */
    fun fraction(value: Float): Float = (value - min) / span
}

/** Picks y ranges that stay honest: a flat signal must not be stretched to fill the chart. */
object ChartScale {
    const val DEFAULT_MIN_SPAN = 4f
    private const val PADDING_FRACTION = 0.1f

    /** Range around [samples], at least [minSpan] tall and centred on the data, with headroom. */
    fun fit(samples: List<Float>, minSpan: Float = DEFAULT_MIN_SPAN): YRange {
        if (samples.isEmpty()) return YRange(0f, minSpan)
        val low = samples.min()
        val high = samples.max()
        val span = max(high - low, minSpan)
        val centre = (low + high) / 2
        val pad = span * PADDING_FRACTION
        return YRange(centre - span / 2 - pad, centre + span / 2 + pad)
    }

    /** Largest absolute value over all [series], at least [minExtent]; the axis runs from -extent to +extent. */
    fun symmetricExtent(series: List<List<Float>>, minExtent: Float): Float = max(series.maxOfOrNull { s -> s.maxOfOrNull { abs(it) } ?: 0f } ?: 0f, minExtent)
}
