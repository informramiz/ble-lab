package com.codebyramiz.blelab.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codebyramiz.blelab.R
import com.codebyramiz.blelab.ui.components.LiveChart
import com.codebyramiz.blelab.ui.components.SectionCard
import com.codebyramiz.blelab.ui.components.SectionTitle
import com.codebyramiz.blelab.ui.theme.MonoStyle
import kotlinx.coroutines.launch

@Composable
fun SessionDetailRoute(onBack: () -> Unit, viewModel: SessionDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SessionDetailScreen(
        state = state,
        onBack = onBack,
        onExport = {
            viewModel.csv()?.let { (name, csv) -> scope.launch { CsvShare.share(context, name, csv) } }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(state: SessionDetailUiState, onBack: () -> Unit, onExport: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.session?.deviceName ?: stringResource(R.string.session_title), maxLines = 1)
                        state.session?.let { Text(it.deviceAddress, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = onExport, enabled = state.session != null, modifier = Modifier.testTag("exportButton")) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.session_export))
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.session_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            state.session == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.session_missing), modifier = Modifier.testTag("sessionMissing"))
            }

            else -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(sessionSummary(state.session), style = MaterialTheme.typography.titleMedium)
                SeriesCard(R.drawable.ic_sensors, R.string.telemetry_title, state.temperature, R.string.session_no_telemetry, "telemetryChart")
                SeriesCard(R.drawable.ic_motion, R.string.motion_title_accel, state.acceleration, R.string.session_no_motion, "accelerationChart")
                SeriesCard(R.drawable.ic_motion, R.string.motion_title_gyro, state.rotation, R.string.session_no_motion, "rotationChart")
            }
        }
    }
}

@Composable
private fun SeriesCard(icon: Int, title: Int, values: List<Float>, emptyText: Int, tag: String) {
    SectionCard(Modifier.testTag(tag)) {
        SectionTitle(icon, stringResource(title))
        if (values.size < 2) {
            Text(stringResource(emptyText), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LiveChart(values, Modifier.fillMaxWidth().height(140.dp), capacity = values.size)
        }
    }
}
