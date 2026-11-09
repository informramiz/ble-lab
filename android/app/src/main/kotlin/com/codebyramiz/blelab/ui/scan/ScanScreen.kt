package com.codebyramiz.blelab.ui.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codebyramiz.blelab.R
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.ScannedDevice
import com.codebyramiz.blelab.permissions.BlePermissions
import com.codebyramiz.blelab.ui.components.RadarPulse
import com.codebyramiz.blelab.ui.components.RssiBars
import com.codebyramiz.blelab.ui.theme.MonoStyle

@Composable
fun ScanRoute(onOpenDevice: (ScannedDevice) -> Unit, onOpenHistory: () -> Unit, viewModel: ScanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var granted by remember { mutableStateOf(BlePermissions.granted(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.all { it }
        if (granted) viewModel.startScan()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    ScanScreen(
        state = state,
        permissionsGranted = granted,
        usesLocation = BlePermissions.usesLocation(),
        onScan = viewModel::startScan,
        onStop = viewModel::stopScan,
        onToggleFilter = viewModel::setBleLabOnly,
        onRequestPermissions = { permissionLauncher.launch(BlePermissions.required().toTypedArray()) },
        // ACTION_REQUEST_ENABLE needs BLUETOOTH_CONNECT on API 31+; the card only shows once it is granted.
        onEnableBluetooth = @SuppressLint("MissingPermission") { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        onOpenDevice = { device ->
            viewModel.stopScan()
            onOpenDevice(device)
        },
        onOpenHistory = onOpenHistory,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    state: ScanUiState,
    permissionsGranted: Boolean,
    usesLocation: Boolean,
    onScan: () -> Unit,
    onStop: () -> Unit,
    onToggleFilter: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenDevice: (ScannedDevice) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.scan_title))
                        Text(stringResource(R.string.scan_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory, modifier = Modifier.testTag("historyButton")) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.history_open))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ScanHero(state, onScan, onStop, onToggleFilter) }
            if (!permissionsGranted) {
                item {
                    NoticeCard(
                        icon = R.drawable.ic_lock,
                        title = stringResource(R.string.permission_title),
                        body = stringResource(if (usesLocation) R.string.permission_body_legacy else R.string.permission_body),
                        action = stringResource(R.string.permission_grant),
                        onAction = onRequestPermissions,
                        tag = "permissionCard",
                    )
                }
            } else if (!state.bluetoothOn) {
                item {
                    NoticeCard(
                        icon = R.drawable.ic_bluetooth_disabled,
                        title = stringResource(R.string.bluetooth_off_title),
                        body = stringResource(R.string.bluetooth_off_body),
                        action = stringResource(R.string.bluetooth_turn_on),
                        onAction = onEnableBluetooth,
                        tag = "bluetoothOffCard",
                    )
                }
            } else if (state.error != null && state.error !is BleError.MissingPermission && state.error !is BleError.BluetoothOff) {
                item {
                    NoticeCard(
                        icon = R.drawable.ic_link_off,
                        title = state.error.message,
                        body = null,
                        action = stringResource(R.string.retry),
                        onAction = onScan,
                        tag = "scanErrorCard",
                    )
                }
            }
            if (state.devices.isEmpty()) {
                item {
                    Text(
                        stringResource(if (state.scanning) R.string.scan_scanning else R.string.scan_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(state.devices, key = { it.address }) { device ->
                DeviceRow(device, onClick = { onOpenDevice(device) }, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun ScanHero(state: ScanUiState, onScan: () -> Unit, onStop: () -> Unit, onToggleFilter: (Boolean) -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                RadarPulse(active = state.scanning, modifier = Modifier.fillMaxSize())
                Icon(
                    painterResource(if (state.scanning) R.drawable.ic_bluetooth_searching else R.drawable.ic_bluetooth),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (state.scanning) R.string.scan_scanning else R.string.scan_idle),
                    style = MaterialTheme.typography.titleMedium,
                )
                FilterChip(
                    selected = state.bleLabOnly,
                    onClick = { onToggleFilter(!state.bleLabOnly) },
                    label = { Text(stringResource(R.string.scan_filter_lab)) },
                    modifier = Modifier.testTag("filterChip"),
                )
                FilledTonalButton(onClick = if (state.scanning) onStop else onScan, modifier = Modifier.testTag("scanButton")) {
                    Icon(painterResource(R.drawable.ic_radar), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state.scanning) R.string.scan_stop else R.string.scan_start))
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth().testTag("device-${device.address}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_memory), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.name ?: stringResource(R.string.scan_unnamed),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (device.isBleLab) Badge(stringResource(if (device.isSimulated) R.string.scan_demo_badge else R.string.scan_lab_badge))
                }
                Text(device.address, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                RssiBars(device.rssi)
                Text(stringResource(R.string.scan_rssi, device.rssi), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.padding(start = 8.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
internal fun NoticeCard(icon: Int, title: String, body: String?, action: String, onAction: () -> Unit, tag: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = true, modifier = modifier) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(icon), contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall)
                }
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(onClick = onAction) { Text(action) }
            }
        }
    }
}
