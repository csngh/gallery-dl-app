package app.gallerydl.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gallerydl.GalleryDlApp
import app.gallerydl.R
import app.gallerydl.sync.SyncDevice
import app.gallerydl.sync.SyncManager
import app.gallerydl.sync.SyncOffer
import app.gallerydl.sync.SyncStatus
import app.gallerydl.ui.components.ScreenTitle
import app.gallerydl.ui.components.focusRing
import app.gallerydl.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Sync with nearby devices: find another Gallery DL on the same Wi-Fi, pair with it once, then receive, send or merge links
 * and favorite videos. The device is announced and listening only while this screen is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val manager = (LocalContext.current.applicationContext as GalleryDlApp).container.syncManager
    var choosing by remember { mutableStateOf<SyncDevice?>(null) }
    var counts by remember { mutableStateOf<SyncManager.Counts?>(null) }
    LaunchedEffect(choosing) { counts = if (choosing == null) null else manager.counts() }
    val state by manager.state.collectAsStateWithLifecycle()
    DisposableEffect(manager) {
        manager.start()
        onDispose { manager.stop() }
    }
    var address by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { ScreenTitle(stringResource(R.string.sync_title)) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.focusRing(RoundedCornerShape(50))) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        StatusBanner(state.status, onCancel = manager::cancel, onDismiss = manager::dismissStatus)
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(Spacing.Md),
            verticalArrangement = Arrangement.spacedBy(Spacing.Lg),
        ) {
            Text(stringResource(R.string.sync_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.vpn) Text(stringResource(R.string.sync_vpn_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)

            // This device
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(stringResource(R.string.sync_this_device), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.deviceName,
                    onValueChange = manager::rename,
                    label = { Text(stringResource(R.string.sync_device_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.port > 0) {
                    Text(stringResource(R.string.sync_address_line, state.port), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Nearby devices
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.sync_nearby), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = manager::searchAgain) { Text(stringResource(R.string.sync_search_again)) }
                }
                if (state.devices.isEmpty() && state.problem == null) {
                    Text(stringResource(R.string.sync_no_devices), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.devices.forEach { device ->
                    DeviceCard(device, onSend = { choosing = device })
                }
            }

            // Add by address
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                Text(stringResource(R.string.sync_add_by_address), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Sm)) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        placeholder = { Text(stringResource(R.string.sync_address_field)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            addressError = manager.add(address)
                            if (addressError == null) address = ""
                        }
                    }) { Text(stringResource(R.string.sync_add_action)) }
                }
                addressError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    choosing?.let { device ->
        ChooseDialog(
            device = device,
            counts = counts,
            onConfirm = { links, favorites -> choosing = null; manager.send(device, links, favorites) },
            onDismiss = { choosing = null },
        )
    }
    state.offer?.let { offer -> OfferDialog(offer, onAccept = manager::accept, onDecline = manager::decline) }
}

@Composable
private fun DeviceCard(device: SyncDevice, onSend: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.Md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.Md)) {
            Icon(if (device.platform == "desktop") Icons.Filled.Tv else Icons.Filled.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(device.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onSend) { Text(stringResource(R.string.sync_send)) }
        }
    }
}

/** Progress of what is being moved, and what came of it afterwards. */
@Composable
private fun StatusBanner(status: SyncStatus?, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val current = status ?: return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.Md, vertical = Spacing.Xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
            when (current) {
                is SyncStatus.Running -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(current.title, style = MaterialTheme.typography.titleSmall)
                            if (current.total > 0) {
                                Text(stringResource(R.string.sync_file_of, (current.done + 1).coerceAtMost(current.total), current.total) + (current.file?.let { " · $it" } ?: ""), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    }
                    if (current.total > 0) {
                        // This file's own bar, then the whole transfer's.
                        if (current.fraction != null) {
                            Text("${(current.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(progress = { current.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        LinearProgressIndicator(progress = { current.done.coerceAtMost(current.total).toFloat() / current.total }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondary)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is SyncStatus.Finished -> ResultRow(current.text, current.details, onDismiss)
                is SyncStatus.Failed -> ResultRow(current.text, emptyList(), onDismiss)
            }
        }
    }
}

@Composable
private fun ResultRow(text: String, details: List<String>, onDismiss: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        }
        // What was left out, one line each, so a video that did not make it can be found and its reason read.
        details.take(40).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (details.size > 40) Text("…and ${details.size - 40} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Another device wants to send something: nothing is received until the person agrees. */
@Composable
private fun OfferDialog(offer: SyncOffer, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.sync_offer_title, offer.from), style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.sync_offer_links, offer.links, offer.links).takeIf { offer.links > 0 },
                    pluralStringResource(R.plurals.sync_offer_favorites, offer.videos, offer.videos).takeIf { offer.videos > 0 },
                ).joinToString(" and ") + ".\n" + stringResource(R.string.sync_offer_question, android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, offer.bytes)),
            )
        },
        confirmButton = { Button(onClick = onAccept) { Text(stringResource(R.string.sync_accept)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.sync_decline)) } },
    )
}

/** What to share with [device]: links, favorites, or both. The counts say how much each holds on this device. */
@Composable
private fun ChooseDialog(device: SyncDevice, counts: SyncManager.Counts?, onConfirm: (Boolean, Boolean) -> Unit, onDismiss: () -> Unit) {
    var links by remember { mutableStateOf(true) }
    var favorites by remember { mutableStateOf(true) }
    val linkCount = counts?.links ?: 0
    val favoriteCount = counts?.favorites ?: 0
    val favoriteBytes = counts?.bytes ?: 0L
    val context = androidx.compose.ui.platform.LocalContext.current
    val nothing = counts != null && linkCount == 0 && favoriteCount == 0 && counts.leftOut.isEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_choose_title, device.name), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
                Text(stringResource(R.string.sync_choose_what), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (nothing) {
                    Text(stringResource(R.string.sync_choose_nothing), style = MaterialTheme.typography.bodyMedium)
                } else {
                    ChoiceRow(stringResource(R.string.sync_choose_links, linkCount), links && linkCount > 0, enabled = linkCount > 0) { links = it }
                    ChoiceRow(stringResource(R.string.sync_choose_favorites, favoriteCount, android.text.format.Formatter.formatShortFileSize(context, favoriteBytes)), favorites && favoriteCount > 0, enabled = favoriteCount > 0) { favorites = it }
                    // Favorites that cannot be sent are named here, with the reason, before anything goes.
                    counts?.leftOut?.take(8)?.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if ((counts?.leftOut?.size ?: 0) > 8) Text("…and ${(counts?.leftOut?.size ?: 0) - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(links && linkCount > 0, favorites && favoriteCount > 0) },
                enabled = counts != null && ((links && linkCount > 0) || (favorites && favoriteCount > 0)),
            ) { Text(stringResource(R.string.sync_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(Spacing.Sm))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
