package com.codebyramiz.blelab.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codebyramiz.blelab.R
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ui.theme.LocalSignalColors

/** Animated pill naming the connection state; colour tracks link health. */
@Composable
fun StatusPill(state: ConnectionState, modifier: Modifier = Modifier) {
    val signal = LocalSignalColors.current
    val target = when (state) {
        is ConnectionState.Ready -> signal.good
        is ConnectionState.Failed -> signal.bad
        ConnectionState.Idle -> MaterialTheme.colorScheme.outline
        else -> signal.warn
    }
    val color by animateColorAsState(target, label = "pill")
    Surface(shape = CircleShape, color = color.copy(alpha = 0.16f), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = stateLabel(state),
                transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                label = "pillText",
            ) { label ->
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
fun stateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> stringResource(R.string.state_idle)
    is ConnectionState.Connecting -> stringResource(R.string.state_connecting, state.attempt)
    is ConnectionState.Discovering -> stringResource(R.string.state_discovering)
    is ConnectionState.Bonding -> stringResource(R.string.state_bonding)
    is ConnectionState.Ready -> stringResource(R.string.state_ready)
    is ConnectionState.Reconnecting -> stringResource(R.string.state_reconnecting, state.delayMillis / 1000f)
    is ConnectionState.Failed -> stringResource(R.string.state_failed)
}

/** Four signal bars from RSSI: >= -60 excellent, -70 good, -80 fair, else weak. */
@Composable
fun RssiBars(rssi: Int, modifier: Modifier = Modifier) {
    val bars = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    val signal = LocalSignalColors.current
    val on = when (bars) {
        4, 3 -> signal.good
        2 -> signal.warn
        else -> signal.bad
    }
    val off = MaterialTheme.colorScheme.outlineVariant
    val label = stringResource(R.string.scan_rssi, rssi)
    Canvas(modifier.size(20.dp, 16.dp).semantics { contentDescription = label }) {
        val gap = size.width / 10
        val barWidth = (size.width - gap * 3) / 4
        repeat(4) { i ->
            val h = size.height * (i + 1) / 4
            drawRoundRect(
                color = if (i < bars) on else off,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 3),
            )
        }
    }
}

/** Expanding rings behind the scan button while scanning. */
@Composable
fun RadarPulse(active: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "radar")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart),
        label = "radarProgress",
    )
    Canvas(modifier) {
        val maxRadius = size.minDimension / 2
        if (active) {
            repeat(3) { ring ->
                val p = (progress + ring / 3f) % 1f
                drawCircle(color.copy(alpha = (1f - p) * 0.35f), radius = maxRadius * p, style = Stroke(width = 2.dp.toPx()))
            }
        }
        drawCircle(color.copy(alpha = 0.10f), radius = maxRadius * 0.42f)
    }
}

/** Steps of the connection pipeline, lit up to where [state] has reached. */
@Composable
fun PipelineStepper(state: ConnectionState, modifier: Modifier = Modifier) {
    val reached = when (state) {
        is ConnectionState.Connecting, is ConnectionState.Reconnecting -> 0
        is ConnectionState.Discovering, is ConnectionState.Bonding -> 2
        is ConnectionState.Ready -> 4
        else -> -1
    }
    val steps = listOf(R.string.step_connect, R.string.step_mtu, R.string.step_services, R.string.step_notify)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, label ->
            val done = index < reached
            val current = index == reached
            val color by animateColorAsState(
                if (done) {
                    active
                } else if (current) {
                    active.copy(alpha = 0.45f)
                } else {
                    inactive
                },
                label = "step$index",
            )
            Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Line chart with a gradient fill and min/max guides, drawn straight to Canvas. */
@Composable
fun LiveChart(samples: List<Float>, modifier: Modifier = Modifier, capacity: Int = 120) {
    val line = LocalSignalColors.current.chart
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Canvas(modifier) {
        repeat(4) { i ->
            val y = size.height * i / 3
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        if (samples.size < 2) return@Canvas
        val min = samples.min() - 0.5f
        val max = samples.max() + 0.5f
        val step = size.width / (capacity - 1)
        val startX = size.width - step * (samples.size - 1)
        fun point(i: Int) = Offset(startX + i * step, size.height - (samples[i] - min) / (max - min) * size.height)

        val path = Path().apply {
            moveTo(point(0).x, point(0).y)
            for (i in 1 until samples.size) {
                val prev = point(i - 1)
                val cur = point(i)
                val midX = (prev.x + cur.x) / 2
                cubicTo(midX, prev.y, midX, cur.y, cur.x, cur.y)
            }
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(point(samples.lastIndex).x, size.height)
            lineTo(startX, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = point(samples.lastIndex)
        drawCircle(line.copy(alpha = 0.25f), radius = 8.dp.toPx(), center = last)
        drawCircle(line, radius = 4.dp.toPx(), center = last)
    }
}
