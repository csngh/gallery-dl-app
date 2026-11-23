package app.gallerydl.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gallerydl.R
import app.gallerydl.download.ExtractorPlugin
import app.gallerydl.ui.components.focusRing
import app.gallerydl.ui.theme.Spacing

@Composable
internal fun ExtractorPluginsSettings(viewModel: SettingsViewModel) {
    val state by viewModel.plugins.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.updatePlugins("import", uri = uri)
    }
    val fallbackPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.updatePlugins("import", uri = uri)
    }
    var noPicker by remember { mutableStateOf(false) }
    var enable by remember { mutableStateOf<ExtractorPlugin?>(null) }
    var remove by remember { mutableStateOf<ExtractorPlugin?>(null) }
    LaunchedEffect(Unit) { viewModel.updatePlugins("list") }

    Text(stringResource(R.string.plugins_trust), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.snapshot?.restartRequired == true) {
        Text(stringResource(R.string.plugins_restart_android), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (noPicker) {
        Text(stringResource(R.string.plugins_no_picker), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (state.snapshot?.plugins?.isEmpty() == true) {
        Text(stringResource(R.string.plugins_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val buttonShape = MaterialTheme.shapes.extraLarge
    state.snapshot?.plugins.orEmpty().forEach { plugin ->
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
            Text(plugin.name, style = MaterialTheme.typography.titleSmall)
            val status = stringResource(when {
                plugin.enabled && plugin.active -> R.string.plugins_active
                plugin.enabled && plugin.error != null -> R.string.plugins_not_loaded
                plugin.enabled -> R.string.plugins_enable_pending
                plugin.active -> R.string.plugins_disable_pending
                else -> R.string.plugins_disabled
            })
            Text(listOf(plugin.version, status).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (plugin.domains.isNotEmpty()) {
                Text(plugin.domains.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            plugin.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                OutlinedButton(
                    modifier = Modifier.focusRing(buttonShape),
                    enabled = !state.busy && (plugin.enabled || plugin.error == null),
                    onClick = { if (plugin.enabled) viewModel.updatePlugins("disable", plugin.id) else enable = plugin },
                ) { Text(stringResource(if (plugin.enabled) R.string.plugins_disable else R.string.plugins_enable)) }
                TextButton(modifier = Modifier.focusRing(buttonShape), enabled = !state.busy, onClick = { remove = plugin }) {
                    Text(stringResource(R.string.action_remove))
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Sm), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            modifier = Modifier.focusRing(buttonShape),
            enabled = !state.busy,
            onClick = {
                noPicker = false
                try {
                    picker.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    try {
                        fallbackPicker.launch("*/*")
                    } catch (_: ActivityNotFoundException) {
                        noPicker = true
                    }
                }
            },
        ) { Text(stringResource(R.string.plugins_import)) }
        if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    enable?.let { plugin ->
        PluginConfirmation(
            title = stringResource(R.string.plugins_enable_title, plugin.name),
            message = stringResource(R.string.plugins_enable_message),
            label = stringResource(R.string.plugins_enable),
            onDismiss = { enable = null },
            onConfirm = { enable = null; viewModel.updatePlugins("enable", plugin.id) },
        )
    }
    remove?.let { plugin ->
        PluginConfirmation(
            title = stringResource(R.string.plugins_remove_title, plugin.name),
            message = stringResource(R.string.plugins_remove_message),
            label = stringResource(R.string.action_remove),
            onDismiss = { remove = null },
            onConfirm = { remove = null; viewModel.updatePlugins("remove", plugin.id, plugin.enabled) },
        )
    }
}

@Composable
private fun PluginConfirmation(title: String, message: String, label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val shape = MaterialTheme.shapes.extraLarge
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        dismissButton = { TextButton(modifier = Modifier.focusRing(shape), onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = { TextButton(modifier = Modifier.focusRing(shape), onClick = onConfirm) { Text(label) } },
    )
}
