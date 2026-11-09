package com.codebyramiz.blelab.ui.device

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.codebyramiz.blelab.ui.components.LiveChart
import com.codebyramiz.blelab.ui.components.PipelineStepper
import com.codebyramiz.blelab.ui.components.StatusPill
import com.codebyramiz.blelab.ui.theme.LocalSignalColors
import com.codebyramiz.blelab.ui.theme.MonoStyle

@Composable
fun DeviceRoute(onBack: () -> Unit, viewModel: DeviceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DeviceScreen(state = state, onBack = onBack, onRetry = viewModel::retry, onLedChange = viewModel::setLed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    state: DeviceUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection = state.connection
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
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            PipelineStepper(connection)
            AnimatedVisibility(connection is ConnectionState.Reconnecting || connection is ConnectionState.Failed, enter = expandVertically(), exit = shrinkVertically()) {
                ProblemCard(connection, onRetry)
            }
            AnimatedVisibility(connection is ConnectionState.Connecting || connection is ConnectionState.Discovering || connection is ConnectionState.Bonding) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TelemetryCard(state)
            LedCard(state, enabled = connection is ConnectionState.Ready, onLedChange)
            InfoCard(state.address, (connection as? ConnectionState.Ready)?.info, (connection as? ConnectionState.Ready)?.mtu)
            LogCard(state.log)
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
}

@Composable
private fun SectionTitle(icon: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun ProblemCard(connection: ConnectionState, onRetry: () -> Unit) {
    val (title, failed) = when (connection) {
        is ConnectionState.Reconnecting -> connection.cause.message to false
        is ConnectionState.Failed -> connection.error.message to true
        else -> "" to false
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
        modifier = Modifier.fillMaxWidth().testTag("problemCard"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Icon(painterResource(R.drawable.ic_link_off), contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.reconnect_cause, title), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (failed) {
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
