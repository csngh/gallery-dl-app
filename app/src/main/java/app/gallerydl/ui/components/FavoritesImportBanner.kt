package app.gallerydl.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gallerydl.R
import app.gallerydl.data.FavoritesImporter
import app.gallerydl.ui.theme.Spacing

/**
 * Where a favorites import shows up while it runs in the background, and what it did afterwards. It sits on Library and
 * Home alike, and the import can be stopped from here at any time.
 */
@Composable
fun FavoritesImportBanner(importer: FavoritesImporter, modifier: Modifier = Modifier) {
    val state by importer.state.collectAsStateWithLifecycle()
    val current = state ?: return
    Card(
        modifier.fillMaxWidth().padding(horizontal = Spacing.Md, vertical = Spacing.Xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = Spacing.Md, vertical = Spacing.Sm), verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
            when (current) {
                is FavoritesImporter.State.Running -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.import_favorites_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (current.stopping) stringResource(R.string.import_favorites_stopping)
                                else stringResource(R.string.import_progress, current.done.coerceAtMost(current.total), current.total),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = importer::cancel, enabled = !current.stopping) { Text(stringResource(R.string.action_cancel)) }
                    }
                    LinearProgressIndicator(
                        progress = { if (current.total == 0) 1f else current.done.coerceAtMost(current.total).toFloat() / current.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    current.step?.let { step ->
                        val known = !step.lookingUp && step.length > 0
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                if (step.lookingUp) stringResource(R.string.import_looking_up) else step.name,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            if (!step.lookingUp) {
                                Text(
                                    if (known) "${(step.read * 100 / step.length).coerceIn(0, 100)}%"
                                    else android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, step.read),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (known) LinearProgressIndicator(progress = { (step.read.toFloat() / step.length).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is FavoritesImporter.State.Finished -> {
                    val result = current.result
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(if (current.stopped) R.string.import_favorites_stopped else R.string.import_done_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (result.added > 0) Text(pluralStringResource(R.plurals.import_favorites_added, result.added, result.added), style = MaterialTheme.typography.bodySmall)
                            if (result.duplicates > 0) Text(stringResource(R.string.import_favorites_duplicates, result.duplicates), style = MaterialTheme.typography.bodySmall)
                            if (result.failed > 0) Text(stringResource(R.string.import_favorites_failed, result.failed), style = MaterialTheme.typography.bodySmall)
                            // Why each of the first few failed, so a file that "clearly exists" can be told apart from a dead link.
                            result.failures.take(3).forEach { (name, why) ->
                                Text(stringResource(R.string.import_favorites_failure_line, name, why), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (result.failed > 3) Text(stringResource(R.string.import_favorites_failure_more, result.failed - 3), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (result.found == 0) Text(stringResource(R.string.import_favorites_nothing_added), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = importer::dismiss) { Text(stringResource(R.string.action_ok)) }
                    }
                }
                FavoritesImporter.State.Failed -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.transfer_failed), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = importer::dismiss) { Text(stringResource(R.string.action_ok)) }
                }
            }
        }
    }
}
