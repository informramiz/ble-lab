package com.codebyramiz.blelab.ui.device

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codebyramiz.blelab.R
import com.codebyramiz.blelab.ble.model.ConnectionState
import com.codebyramiz.blelab.ble.model.DeviceInfo
import com.codebyramiz.blelab.ble.model.TouchKind
import com.codebyramiz.blelab.ble.protocol.MessageCodec
import com.codebyramiz.blelab.recording.RecordingState
import com.codebyramiz.blelab.ui.components.LiveChart
import com.codebyramiz.blelab.ui.components.MultiLineChart
import com.codebyramiz.blelab.ui.components.PipelineStepper
import com.codebyramiz.blelab.ui.components.SectionCard
import com.codebyramiz.blelab.ui.components.SectionTitle
import com.codebyramiz.blelab.ui.components.StatusPill
import com.codebyramiz.blelab.ui.components.TiltVisual
import com.codebyramiz.blelab.ui.theme.LocalSignalColors
import com.codebyramiz.blelab.ui.theme.MonoStyle

enum class DeviceTab(val label: Int, val icon: Int) {
    Live(R.string.tab_live, R.drawable.ic_sensors),
    Motion(R.string.tab_motion, R.drawable.ic_motion),
    Board(R.string.tab_control, R.drawable.ic_touch),
}

@Composable
fun DeviceRoute(onBack: () -> Unit, viewModel: DeviceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recording = state.recording is RecordingState.Recording
    // Leaving the screen closes an open recording so the session gets its end time.
    val leave = {
        viewModel.stopRecording()
        onBack()
    }
    BackHandler(enabled = recording, onBack = leave)
    DeviceScreen(
        state = state,
        onBack = leave,
        onRetry = viewModel::retry,
        onLedChange = viewModel::setLed,
        onToggleRecording = viewModel::toggleRecording,
        onSendMessage = viewModel::sendMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceScreen(
    state: DeviceUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLedChange: (Boolean) -> Unit,
    onToggleRecording: () -> Unit,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialTab: DeviceTab = DeviceTab.Live,
) {
    val connection = state.connection
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val scroll = rememberScrollState()
    // The message field is the last card of the Board tab: when the keyboard opens, scroll it and
    // the Send button into the space the keyboard left.
    val keyboardOpen = WindowInsets.isImeVisible
    LaunchedEffect(keyboardOpen, tab) { if (keyboardOpen && tab == DeviceTab.Board) scroll.animateScrollTo(scroll.maxValue) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.name ?: stringResource(R.string.scan_unnamed), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.address, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back)) }
                },
                actions = { StatusPill(connection, Modifier.padding(end = 12.dp).testTag("statusPill")) },
            )
        },
    ) { padding ->
        // Scaffold consumes the system bars; imePadding adds only what the keyboard takes beyond them.
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                DeviceTab.entries.forEach { entry ->
                    Tab(
                        selected = entry == tab,
                        onClick = { tab = entry },
                        text = { Text(stringResource(entry.label)) },
                        icon = { Icon(painterResource(entry.icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.testTag("tab-${entry.name}"),
                    )
                }
            }
            BannerHost(connection, onRetry, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            AnimatedVisibility(connection is ConnectionState.Connecting || connection is ConnectionState.Discovering || connection is ConnectionState.Bonding) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                when (tab) {
                    DeviceTab.Live -> LiveTab(state, onToggleRecording)
                    DeviceTab.Motion -> MotionTab(state)
                    DeviceTab.Board -> BoardTab(state, onLedChange, onSendMessage)
                }
            }
        }
    }
}

@Composable
private fun LiveTab(state: DeviceUiState, onToggleRecording: () -> Unit) {
    val ready = state.connection as? ConnectionState.Ready
    PipelineStepper(state.connection)
    RecordCard(state.recording, enabled = ready != null || state.recording is RecordingState.Recording, onToggle = onToggleRecording)
    TelemetryCard(state)
    InfoCard(state.address, ready?.info, ready?.mtu)
    LogCard(state.log)
}

@Composable
private fun RecordCard(recording: RecordingState, enabled: Boolean, onToggle: () -> Unit) {
    val active = recording as? RecordingState.Recording
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (active == null) stringResource(R.string.record_start) else pluralStringResource(R.plurals.record_progress, active.telemetryCount + active.motionCount, active.telemetryCount + active.motionCount),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (active == null) {
                    Text(stringResource(R.string.record_idle_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            if (active == null) {
                FilledTonalButton(onClick = onToggle, enabled = enabled, modifier = Modifier.testTag("recordButton")) {
                    Icon(painterResource(R.drawable.ic_record), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.record_start))
                }
            } else {
                Button(onClick = onToggle, modifier = Modifier.testTag("recordButton")) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.record_stop))
                }
            }
        }
    }
}

@Composable
private fun MotionTab(state: DeviceUiState) {
    if (state.latest != null && !state.hasImu) {
        SectionCard(Modifier.testTag("motionUnavailable")) {
            SectionTitle(R.drawable.ic_motion, stringResource(R.string.motion_unavailable_title))
            Text(stringResource(R.string.motion_unavailable_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    SectionCard(Modifier.testTag("tiltCard")) {
        SectionTitle(R.drawable.ic_motion, stringResource(R.string.motion_title_tilt))
        if (state.motion.isEmpty()) {
            Text(stringResource(R.string.motion_waiting), style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TiltVisual(state.tilt)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(stringResource(R.string.motion_roll, state.tilt.rollDegrees), style = MonoStyle)
                Text(stringResource(R.string.motion_pitch, state.tilt.pitchDegrees), style = MonoStyle)
            }
        }
    }
    AxesCard(R.string.motion_title_accel, state.motion.map { listOf(it.ax, it.ay, it.az) }, "accelCard")
    AxesCard(R.string.motion_title_gyro, state.motion.map { listOf(it.gx, it.gy, it.gz) }, "gyroCard")
}

@Composable
private fun AxesCard(title: Int, samples: List<List<Float>>, tag: String) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val labels = listOf(R.string.motion_axis_x, R.string.motion_axis_y, R.string.motion_axis_z)
    SectionCard(Modifier.testTag(tag)) {
        SectionTitle(R.drawable.ic_motion, stringResource(title))
        MultiLineChart(
            series = List(AXES) { axis -> samples.map { it[axis] } },
            colors = colors,
            capacity = DeviceViewModel.MOTION_SIZE,
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            labels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(colors[i], CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private const val AXES = 3

@Composable
private fun BoardTab(state: DeviceUiState, onLedChange: (Boolean) -> Unit, onSendMessage: (String) -> Unit) {
    val ready = state.connection is ConnectionState.Ready
    LedCard(state, enabled = ready, onLedChange)
    TouchCard(state)
    MessageCard(state.message, enabled = ready, onSend = onSendMessage)
}

@Composable
private fun TouchCard(state: DeviceUiState) {
    SectionCard(Modifier.testTag("touchCard")) {
        SectionTitle(R.drawable.ic_touch, stringResource(R.string.touch_title))
        if (state.latest != null && !state.hasTouch) {
            Text(stringResource(R.string.touch_unavailable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        if (state.touches.isEmpty()) {
            Text(stringResource(R.string.touch_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.touches.takeLast(TOUCH_ROWS).reversed().forEachIndexed { index, touch ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val kind = stringResource(if (touch.kind == TouchKind.Tap) R.string.touch_tap else R.string.touch_long_press)
                Text(if (touch.injected) "$kind (${stringResource(R.string.touch_injected)})" else kind, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.touch_position, touch.x, touch.y), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val TOUCH_ROWS = 5

@Composable
private fun MessageCard(status: MessageStatus, enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val bytes = MessageCodec.byteLength(text)
    val tooLong = bytes > MessageCodec.MAX_BYTES
    SectionCard(Modifier.testTag("messageCard")) {
        SectionTitle(R.drawable.ic_send, stringResource(R.string.message_title))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.message_label)) },
            singleLine = true,
            isError = tooLong,
            supportingText = { Text(stringResource(R.string.message_counter, bytes, MessageCodec.MAX_BYTES)) },
            modifier = Modifier.fillMaxWidth().testTag("messageField"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (status) {
                    MessageStatus.None -> ""
                    MessageStatus.Sending -> stringResource(R.string.message_sending)
                    is MessageStatus.Sent -> stringResource(R.string.message_sent, status.text)
                    is MessageStatus.Failed -> stringResource(R.string.message_failed, status.reason)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status is MessageStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).testTag("messageStatus"),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { onSend(text) },
                enabled = enabled && bytes > 0 && !tooLong && status != MessageStatus.Sending,
                modifier = Modifier.testTag("sendButton"),
            ) {
                Icon(painterResource(R.drawable.ic_send), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.message_send))
            }
        }
    }
}

@Composable
private fun TelemetryCard(state: DeviceUiState) {
    SectionCard(Modifier.testTag("telemetryCard")) {
        SectionTitle(R.drawable.ic_sensors, stringResource(R.string.telemetry_title))
        val latest = state.latest
        if (latest == null) {
            Text(stringResource(R.string.telemetry_waiting), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(R.string.telemetry_value, latest.value),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(
                pluralStringResource(R.plurals.telemetry_stats, state.packets, state.samples.min(), state.samples.max(), state.packets),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LiveChart(state.samples, Modifier.fillMaxWidth().height(160.dp), capacity = DeviceViewModel.CHART_SIZE)
    }
}

@Composable
private fun LedCard(state: DeviceUiState, enabled: Boolean, onLedChange: (Boolean) -> Unit) {
    val glow = LocalSignalColors.current.glow
    val intensity by animateFloatAsState(if (state.ledOn) 1f else 0f, label = "glow")
    val bulb by animateColorAsState(if (state.ledOn) glow else MaterialTheme.colorScheme.outline, label = "bulb")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f * intensity), Color.Transparent), center = Offset(size.width / 2, size.height / 2)))
                }
                Icon(painterResource(R.drawable.ic_lightbulb), contentDescription = null, tint = bulb, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.led_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(if (state.ledOn) R.string.led_on else R.string.led_off), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.ledOn, onCheckedChange = onLedChange, enabled = enabled && !state.ledPending, modifier = Modifier.testTag("ledSwitch"))
        }
        Text(stringResource(R.string.led_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(address: String, info: DeviceInfo?, mtu: Int?) {
    val unknown = stringResource(R.string.info_unknown)
    SectionCard {
        SectionTitle(R.drawable.ic_memory, stringResource(R.string.info_title))
        InfoRow(stringResource(R.string.info_manufacturer), info?.manufacturer ?: unknown)
        InfoRow(stringResource(R.string.info_model), info?.model ?: unknown)
        InfoRow(stringResource(R.string.info_firmware), info?.firmware ?: unknown)
        InfoRow(stringResource(R.string.info_mtu), mtu?.toString() ?: unknown)
        InfoRow(stringResource(R.string.info_address), address)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoStyle)
    }
}

private enum class BannerKind { Problem, Progress }

private data class Banner(val text: String, val kind: BannerKind, val retryable: Boolean = false)

@Composable
private fun bannerFor(connection: ConnectionState): Banner? = when (connection) {
    is ConnectionState.Reconnecting -> Banner(stringResource(R.string.banner_reconnecting, connection.cause.message), BannerKind.Problem)
    is ConnectionState.Failed -> Banner(stringResource(R.string.banner_failed, connection.error.message), BannerKind.Problem, retryable = true)
    is ConnectionState.Bonding -> Banner(stringResource(R.string.banner_pairing), BannerKind.Progress)
    else -> null
}

/**
 * Connection problems and the pairing prompt. The last banner is kept while the card animates
 * out, otherwise the exit animation recomposes it with no state to describe and shows a bare icon.
 */
@Composable
private fun BannerHost(connection: ConnectionState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val current = bannerFor(connection)
    var last by remember { mutableStateOf<Banner?>(null) }
    LaunchedEffect(current) { if (current != null) last = current }
    AnimatedVisibility(current != null, enter = expandVertically(), exit = shrinkVertically()) {
        (current ?: last)?.let { BannerCard(it, onRetry, modifier) }
    }
}

@Composable
private fun BannerCard(banner: Banner, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val problem = banner.kind == BannerKind.Problem
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = if (problem) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        },
        modifier = modifier.fillMaxWidth().testTag(if (problem) "problemCard" else "pairingCard"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(painterResource(if (problem) R.drawable.ic_link_off else R.drawable.ic_lock), contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(banner.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("bannerText"))
            if (banner.retryable) {
                FilledTonalButton(onClick = onRetry, modifier = Modifier.testTag("retryButton")) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun LogCard(log: List<String>) {
    SectionCard {
        Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        log.takeLast(6).reversed().forEachIndexed { index, line ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Text(line, style = MonoStyle, color = if (index == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
