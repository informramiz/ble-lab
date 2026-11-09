package com.codebyramiz.blelab.ui.device

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.codebyramiz.blelab.R
import com.codebyramiz.blelab.ble.ota.OtaProgress
import com.codebyramiz.blelab.ui.components.SectionCard
import com.codebyramiz.blelab.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the firmware card can ask of its host. */
data class FirmwareActions(
    val onPicked: (ByteArray?) -> Unit = {},
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismiss: () -> Unit = {},
)

@Composable
fun FirmwareCard(state: DeviceUiState, actions: FirmwareActions) {
    val update = state.firmware
    val busy = update is FirmwareUpdate.Running
    val picker = rememberFirmwarePicker(actions.onPicked)
    SectionCard(Modifier.testTag("firmwareCard")) {
        SectionTitle(R.drawable.ic_memory, stringResource(R.string.fw_title))
        when {
            !state.canUpdateFirmware && !busy -> Note(R.string.fw_unsupported)

            update is FirmwareUpdate.None -> {
                Note(R.string.fw_hint)
                Button(onClick = picker, modifier = Modifier.testTag("fwChoose")) { Text(stringResource(R.string.fw_choose)) }
            }

            update is FirmwareUpdate.Picked -> {
                val version = update.version ?: stringResource(R.string.fw_version_unknown)
                Text(stringResource(R.string.fw_file, version, formatBytes(update.sizeBytes)), modifier = Modifier.testTag("fwPicked"))
                Text(
                    stringResource(if (update.signed) R.string.fw_signed else R.string.fw_unsigned),
                    color = if (update.signed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("fwSigned"),
                )
                Actions {
                    Button(onClick = actions.onStart, modifier = Modifier.testTag("fwStart")) { Text(stringResource(R.string.fw_start)) }
                    OutlinedButton(onClick = picker) { Text(stringResource(R.string.fw_choose_other)) }
                }
            }

            update is FirmwareUpdate.Running -> Running(update.progress, actions.onCancel)

            update is FirmwareUpdate.Finished -> {
                Text(stringResource(R.string.fw_finished, update.version ?: "?"), modifier = Modifier.testTag("fwFinished"))
                OutlinedButton(onClick = actions.onDismiss) { Text(stringResource(R.string.fw_dismiss)) }
            }

            update is FirmwareUpdate.Failed -> {
                Text(
                    stringResource(R.string.fw_failed, update.reason),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("fwFailed"),
                )
                Actions {
                    Button(onClick = picker) { Text(stringResource(R.string.fw_choose)) }
                    OutlinedButton(onClick = actions.onDismiss) { Text(stringResource(R.string.fw_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun Note(text: Int) = Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun Running(progress: OtaProgress, onCancel: () -> Unit) {
    val label = when (progress) {
        OtaProgress.Starting -> stringResource(R.string.fw_starting)
        is OtaProgress.Sending -> stringResource(R.string.fw_sending, (progress.fraction * 100).toInt(), progress.sent, progress.total)
        OtaProgress.Verifying -> stringResource(R.string.fw_verifying)
        OtaProgress.Restarting -> stringResource(R.string.fw_restarting)
        is OtaProgress.Failed -> progress.reason
    }
    Text(label, modifier = Modifier.testTag("fwStatus"))
    if (progress is OtaProgress.Sending) {
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().testTag("fwProgress"))
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (progress != OtaProgress.Restarting) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("fwCancel")) { Text(stringResource(R.string.fw_cancel)) }
    }
}

private fun formatBytes(bytes: Int): String = if (bytes >= 1024 * 1024) "%.2f MB".format(bytes / (1024f * 1024f)) else "${bytes / 1024} KB"

/** Opens the system document picker (no storage permission needed) and hands the chosen file's bytes on, or null if unreadable. */
@Composable
private fun rememberFirmwarePicker(onPicked: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { onPicked(readCapped(context, uri)) }
    }
    // Some providers label a .bin as octet-stream, others as nothing useful; let the user see everything.
    return { launcher.launch(arrayOf("*/*")) }
}

private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

private suspend fun readCapped(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_IMAGE_BYTES + 1) } }
        .getOrNull()
        ?.takeIf { it.size <= MAX_IMAGE_BYTES }
}
