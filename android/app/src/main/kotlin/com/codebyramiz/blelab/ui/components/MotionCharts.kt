package com.codebyramiz.blelab.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.codebyramiz.blelab.ui.device.Tilt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One line per series on a shared, zero-centred axis, newest sample at the right edge. */
@Composable
fun MultiLineChart(series: List<List<Float>>, colors: List<Color>, capacity: Int, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val axis = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    Canvas(modifier) {
        repeat(4) { i ->
            val y = size.height * i / 3
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        drawLine(axis, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
        val extent = max(series.maxOfOrNull { s -> s.maxOfOrNull { abs(it) } ?: 0f } ?: 0f, MIN_EXTENT)
        val step = size.width / (capacity - 1).coerceAtLeast(1)
        series.forEachIndexed { index, values ->
            if (values.size < 2) return@forEachIndexed
            val startX = size.width - step * (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = startX + i * step
                val y = size.height / 2 - v / extent * (size.height / 2) * 0.9f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[index % colors.size], style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private const val MIN_EXTENT = 0.25f

/** Bubble level: the dot sits where the board's tilt points, clamped to [MAX_TILT] degrees. */
@Composable
fun TiltVisual(tilt: Tilt, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val guide = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val bubble = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(180.dp).aspectRatio(1f)) {
        val c = center
        val radius = size.minDimension / 2 - 4.dp.toPx()
        drawCircle(ring.copy(alpha = 0.25f), radius)
        listOf(1f, 0.66f, 0.33f).forEach { drawCircle(ring, radius * it, style = Stroke(1.dp.toPx())) }
        drawLine(guide, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), 1.dp.toPx())
        drawLine(guide, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), 1.dp.toPx())
        // Pitch moves the dot left and right, roll moves it up and down.
        val dx = min(abs(tilt.pitchDegrees), MAX_TILT) * (if (tilt.pitchDegrees < 0) -1 else 1) / MAX_TILT * radius
        val dy = min(abs(tilt.rollDegrees), MAX_TILT) * (if (tilt.rollDegrees < 0) -1 else 1) / MAX_TILT * radius
        val dot = Offset(c.x + dx, c.y + dy)
        drawCircle(bubble.copy(alpha = 0.22f), 20.dp.toPx(), dot)
        drawCircle(bubble, 11.dp.toPx(), dot)
    }
}

private const val MAX_TILT = 45f
