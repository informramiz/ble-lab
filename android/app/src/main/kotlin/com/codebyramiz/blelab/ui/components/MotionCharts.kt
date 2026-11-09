package com.codebyramiz.blelab.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.codebyramiz.blelab.R
import com.codebyramiz.blelab.ui.theme.LocalSignalColors
import java.util.Locale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.codebyramiz.blelab.ui.device.Tilt
import kotlin.math.abs
import kotlin.math.min

/** One line per series on a shared, zero-centred axis, newest sample at the right edge. */
@Composable
fun MultiLineChart(series: List<List<Float>>, colors: List<Color>, capacity: Int, extent: Float, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val axis = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    Canvas(modifier) {
        repeat(GRID_LINES) { i ->
            val y = size.height * i / (GRID_LINES - 1)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        drawLine(axis, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
        val step = size.width / (capacity - 1).coerceAtLeast(1)
        series.forEachIndexed { index, values ->
            if (values.size < 2) return@forEachIndexed
            val startX = size.width - step * (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = startX + i * step
                val y = size.height / 2 - v / extent * (size.height / 2)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors[index % colors.size], style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// Odd, so the centre line (zero) lands on a grid line.
private const val GRID_LINES = 5

/**
 * Three-axis chart: X, Y and Z in their own colours on a symmetric y axis labelled with its
 * extent, plus a legend. The same component draws live and recorded motion.
 */
@Composable
fun AxesChart(series: List<List<Float>>, capacity: Int, minExtent: Float, decimals: Int, modifier: Modifier = Modifier) {
    val colors = LocalSignalColors.current.axes
    val extent = ChartScale.symmetricExtent(series, minExtent) * HEADROOM
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        YAxisChart(top = formatAxis(extent, decimals), middle = formatAxis(0f, decimals), bottom = formatAxis(-extent, decimals), modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
            MultiLineChart(series, colors, capacity, extent, Modifier.fillMaxSize())
        }
        AxisLegend(colors)
    }
}

private const val HEADROOM = 1.1f
private val CHART_HEIGHT = 140.dp

fun formatAxis(value: Float, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", value)

/** A chart with its top, middle and bottom y values written in a gutter on the left. */
@Composable
fun YAxisChart(top: String, bottom: String, modifier: Modifier = Modifier, middle: String? = null, chart: @Composable () -> Unit) {
    Row(modifier) {
        Column(Modifier.fillMaxHeight().width(AXIS_GUTTER), verticalArrangement = Arrangement.SpaceBetween) {
            AxisLabel(top)
            if (middle != null) AxisLabel(middle)
            AxisLabel(bottom)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { chart() }
    }
}

private val AXIS_GUTTER = 44.dp

@Composable
private fun AxisLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(end = 6.dp))
}

/** X, Y and Z swatches with their names, in the same colours the lines use. */
@Composable
fun AxisLegend(colors: List<Color>, modifier: Modifier = Modifier) {
    val labels = listOf(R.string.motion_axis_x, R.string.motion_axis_y, R.string.motion_axis_z)
    Row(modifier.padding(start = AXIS_GUTTER).testTag("axisLegend"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        labels.forEachIndexed { i, label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 4.dp).background(colors[i], CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

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
