package app.gallerydl.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import app.gallerydl.download.isFavoritesFolder
import app.gallerydl.download.originalSource
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gallerydl.R
import app.gallerydl.data.FolderSummary
import app.gallerydl.download.folderSubtitle
import app.gallerydl.download.folderTitle
import app.gallerydl.data.LibraryItem
import app.gallerydl.ui.AppViewModelProvider
import app.gallerydl.download.PreviewWorker
import app.gallerydl.ui.components.ConfirmDialog
import app.gallerydl.ui.components.VideoViewer
import app.gallerydl.ui.components.ViewerState
import app.gallerydl.ui.components.ZoomableBox
import app.gallerydl.ui.theme.ActionColors
import app.gallerydl.ui.theme.Spacing
import app.gallerydl.ui.components.EmptyState
import app.gallerydl.ui.components.IconTile
import app.gallerydl.ui.components.ScreenTitle
import app.gallerydl.ui.components.TileSize
import app.gallerydl.ui.theme.Tone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import app.gallerydl.ui.components.CachedThumbnail
import app.gallerydl.ui.components.GalleryImage
import app.gallerydl.ui.components.LocalThumbnailCache
import app.gallerydl.ui.components.LivePreview
import app.gallerydl.ui.components.FavoritesImportBanner
import app.gallerydl.ui.components.focusRing
import app.gallerydl.ui.components.onRemoteLongPress
import app.gallerydl.ui.components.PreviewProgressBanner
import app.gallerydl.ui.components.LocalPreviewHost
import app.gallerydl.ui.components.LocalPreviewStrips
import app.gallerydl.ui.components.rememberPreviewHost
import app.gallerydl.ui.components.ShimmerGrid
import app.gallerydl.ui.components.softScrollEdges
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.unit.Dp
import app.gallerydl.ui.components.TwoPaneMinWidth
import app.gallerydl.ui.components.readableWidth

private data class ViewerReturnTarget(val itemId: Long?, val index: Int)

@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val openFolder by viewModel.openFolder.collectAsStateWithLifecycle()

    val livePreviews by viewModel.livePreviews.collectAsStateWithLifecycle()
    val previewSpeed by viewModel.previewSpeed.collectAsStateWithLifecycle()
    val previewHost = rememberPreviewHost(enabledBySetting = livePreviews, speed = previewSpeed)

    // The preview clips of the whole library are made by a background job, which carries on after the library is left;
    // the bar here shows how far it has got. Opening the library makes sure the job is running.
    val allVideos by viewModel.videosByFolder.collectAsStateWithLifecycle()
    val videoCount = allVideos.values.sumOf { it.size }
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(livePreviews, videoCount) {
        if (livePreviews) PreviewWorker.enqueue(appContext) else PreviewWorker.cancel(appContext)
    }

    // Files can be deleted in other apps while we are in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
        previewHost.refreshSystemState()
        previewHost.appVisible = true
    }
    // Previews stop as soon as the app is no longer in front, and start again when it returns.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { previewHost.appVisible = false }
    val folder = openFolder
    // The folder list's scroll position lives here, above the choice between the list and an open folder, so coming back
    // from a folder lands where you left off instead of at the top.
    val foldersGridState = rememberLazyGridState()
    // The full-screen viewer lives here, above the choice between the one-pane and two-pane layout. Turning a phone to
    // landscape switches that layout and rebuilds the folder screen; the viewer, and the video playing in it, must not.
    val viewerItems by viewModel.items.collectAsStateWithLifecycle()
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var focusOnReturn by remember(folder?.id) { mutableStateOf<ViewerReturnTarget?>(null) }
    val configuration = LocalConfiguration.current
    val remoteOnly = configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH ||
        (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    val viewerOpen = ViewerState.open
    val restoreTarget = focusOnReturn.takeUnless { viewerOpen }
    val openViewer: (Int) -> Unit = { index ->
        focusOnReturn = null
        viewerIndex = index
    }
    BackHandler(enabled = folder != null && !viewerOpen, onBack = viewModel::back)
    // In the wide layout the folder list can be hidden so the open folder fills the whole screen.
    var foldersHidden by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(folder?.id) { viewerIndex = null }
    // Playing previews behind the full-screen viewer would only waste memory.
    LaunchedEffect(viewerIndex) { previewHost.viewerOpen = viewerIndex != null }
    // One picker for the favorites sheet, here above the folder list and the open folder: a picker owned by a button
    // loses its answer if the button leaves the screen while the picker is open.
    val context = LocalContext.current
    val favoritesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFavoriteVideos(context.contentResolver, uri)
    }
    CompositionLocalProvider(
        LocalPreviewHost provides previewHost,
        LocalThumbnailCache provides viewModel.thumbnails,
        LocalPreviewStrips provides viewModel.previewStrips,
        LocalPickFavorites provides { favoritesPicker.launch(arrayOf("*/*")) },
    ) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneMinWidth) {
            // Wide windows: the folders stay on the left and the open folder's files fill the right.
            val importing by viewModel.favoritesImporter.state.collectAsStateWithLifecycle()
            val bannerShown = importing != null
            Column(Modifier.fillMaxSize()) {
            // The banners need the status bar's room themselves; the folder list's own top bar then does not add it again.
            Column(if (bannerShown) Modifier.statusBarsPadding() else Modifier) {
                FavoritesImportBanner(viewModel.favoritesImporter)
            }
            Row(Modifier.weight(1f).fillMaxWidth().then(if (bannerShown) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
                // With no folder open there is nothing to be full screen, so the list is always shown then.
                val listHidden = foldersHidden && folder != null
                if (!listHidden) {
                    FoldersContent(
                        viewModel = viewModel,
                        gridState = foldersGridState,
                        modifier = Modifier.weight(0.2f).fillMaxHeight(),
                        folderMinSize = 140.dp,
                        selectedFolderId = folder?.id,
                        singleColumn = true,
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (folder == null) {
                    Box(Modifier.weight(0.8f).fillMaxHeight()) {
                        EmptyState(
                            R.string.library_select_title,
                            R.string.library_select_body,
                            Icons.Filled.Folder,
                            Tone.Violet,
                        )
                    }
                } else {
                    FolderContent(
                        viewModel, folder, Modifier.weight(if (listHidden) 1f else 0.8f).fillMaxHeight(), showBack = false, onOpenViewer = openViewer,
                        restoreTarget = restoreTarget, onFocusRestored = { focusOnReturn = null },
                        foldersHidden = listHidden, onToggleFolders = { foldersHidden = !foldersHidden },
                    )
                }
            }
            }
        } else if (folder == null) {
            FoldersContent(viewModel, Modifier.readableWidth(), gridState = foldersGridState)
        } else {
            FolderContent(
                viewModel, folder, Modifier.readableWidth(), showBack = true, onOpenViewer = openViewer,
                restoreTarget = restoreTarget, onFocusRestored = { focusOnReturn = null },
            )
        }
    }
    val openIndex = viewerIndex
    val viewable = viewerItems
    // The viewer is shown by the app's root, over everything, so it is handed over rather than drawn here.
    val viewer: (@Composable () -> Unit)? = if (openIndex != null && viewable != null) {
        {
            Viewer(
                items = viewable,
                startIndex = openIndex,
                onDismiss = { target ->
                    if (remoteOnly) focusOnReturn = target
                    viewerIndex = null
                },
                onDelete = viewModel::deleteItem,
                onFavorite = viewModel::addToFavorites,
            )
        }
    } else null
    SideEffect { ViewerState.content = viewer }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ViewerState.content = null } }
    FavoritesPromptDialog(viewModel)
    }
}


// region Favorites import

private val LocalPickFavorites = staticCompositionLocalOf<() -> Unit> { {} }

/** Opens the picker for a favorites sheet saved by Export video links (see [LocalPickFavorites]). */
@Composable
private fun FavoritesImportButton() {
    val pick = LocalPickFavorites.current
    IconButton(onClick = pick) {
        Icon(Icons.Filled.FileUpload, contentDescription = stringResource(R.string.action_import_favorites))
    }
}

/** Right after a favorites sheet is chosen: asks before anything is imported (the import itself runs in the background). */
@Composable
private fun FavoritesPromptDialog(viewModel: LibraryViewModel) {
    val prompt by viewModel.favoritesPrompt.collectAsStateWithLifecycle()
    when (val current = prompt) {
        null -> Unit
        is LibraryViewModel.FavoritesPrompt.Confirm -> AlertDialog(
            onDismissRequest = viewModel::dismissFavoritesPrompt,
            title = { Text(stringResource(R.string.import_favorites_confirm_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(pluralStringResource(R.plurals.import_favorites_confirm_body, current.rows.size, current.rows.size)) },
            confirmButton = { androidx.compose.material3.Button(onClick = viewModel::confirmFavoritesImport) { Text(stringResource(R.string.action_import_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissFavoritesPrompt) { Text(stringResource(R.string.action_cancel)) } },
        )
        LibraryViewModel.FavoritesPrompt.NothingFound -> AlertDialog(
            onDismissRequest = viewModel::dismissFavoritesPrompt,
            text = { Text(stringResource(R.string.import_favorites_none)) },
            confirmButton = { TextButton(onClick = viewModel::dismissFavoritesPrompt) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

// endregion

// region Folder list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoldersContent(
    viewModel: LibraryViewModel,
    modifier: Modifier,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    folderMinSize: Dp = 150.dp,
    selectedFolderId: Long? = null,
    /** The side list of the wide layout shows one folder per row. */
    singleColumn: Boolean = false,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val videosByFolder by viewModel.videosByFolder.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val listColumns by viewModel.libraryColumns.collectAsStateWithLifecycle()
    var folderToDelete by remember { mutableStateOf<FolderSummary?>(null) }
    var folderActions by remember { mutableStateOf<FolderSummary?>(null) }
    var folderToTag by remember { mutableStateOf<FolderSummary?>(null) }
    val foldersGridState = gridState

    Column(modifier) {
        TopAppBar(
            // The narrow folder list of the wide layout has no room for a title; the navigation already names the screen.
            title = { if (!singleColumn) ScreenTitle(stringResource(R.string.nav_library)) },
            actions = {
                FavoritesImportButton()
                val newestFirst by viewModel.libraryNewestFirst.collectAsStateWithLifecycle()
                // The side list of the wide layout is always one column, but it can still be sorted.
                ColumnsMenu(
                    columns = listColumns,
                    onSelect = viewModel::setLibraryColumns,
                    options = if (singleColumn) IntRange.EMPTY else 2..3,
                    newestFirst = newestFirst,
                    onSortChange = viewModel::setLibraryNewestFirst,
                )
            },
        )
        PreviewProgressBanner(viewModel.previewStrips)
        // In the wide layout these sit above both panes instead (the folder list is too narrow for them).
        if (!singleColumn) {
            FavoritesImportBanner(viewModel.favoritesImporter)
        }
        // The search box is hidden for now; SearchField and the view-model search stay for when it comes back.
        if (SEARCH_VISIBLE) SearchField(query, viewModel::onQueryChange)

        val current = folders
        when {
            current == null -> ShimmerGrid(minSize = folderMinSize, count = 4, shape = MaterialTheme.shapes.medium)
            current.isEmpty() -> {
                if (query.isNotBlank()) {
                    EmptyState(R.string.library_no_results, R.string.library_no_results_body, Icons.Filled.Search, Tone.Violet)
                } else {
                    EmptyState(R.string.library_empty_title, R.string.library_empty_body, Icons.Filled.PhotoLibrary, Tone.Violet)
                }
            }
            else -> LazyVerticalGrid(
                state = foldersGridState,
                modifier = Modifier.softScrollEdges(foldersGridState),
                columns = GridCells.Fixed(if (singleColumn) 1 else listColumns),
                contentPadding = PaddingValues(Spacing.Md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Md),
                verticalArrangement = Arrangement.spacedBy(Spacing.Md),
            ) {
                items(current, key = { it.id }) { folder ->
                    FolderCard(
                        folder = folder,
                        videos = videosByFolder[folder.id].orEmpty(),
                        selected = folder.id == selectedFolderId,
                        onClick = { viewModel.open(folder) },
                        onLongClick = { folderActions = folder },
                    )
                }
            }
        }
    }

    folderActions?.let { folder ->
        FolderActionsDialog(
            title = folder.displayName,
            onTag = {
                folderToTag = folder
                folderActions = null
            },
            onDelete = {
                folderToDelete = folder
                folderActions = null
            },
            onDismiss = { folderActions = null },
        )
    }

    folderToTag?.let { folder ->
        TagDialog(
            initial = folder.tag,
            onSave = { tag ->
                viewModel.setFolderTag(folder.id, tag)
                folderToTag = null
            },
            onDismiss = { folderToTag = null },
        )
    }

    folderToDelete?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.delete_folder_title),
            confirmLabel = stringResource(R.string.action_delete),
            message = pluralStringResource(R.plurals.delete_folder_message, folder.itemCount, folder.itemCount),
            onConfirm = {
                viewModel.deleteFolder(folder.id)
                folderToDelete = null
            },
            onDismiss = { folderToDelete = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: FolderSummary,
    videos: List<LibraryItem>,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.medium)
            .onRemoteLongPress(onLongClick)
            .focusRing(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box {
            val cover = folder.coverUri
            val coverId = folder.coverId
            if (cover != null && coverId != null && folder.coverMime.isVisualMime()) {
                val coverItem = remember(coverId, cover) {
                    LibraryItem(id = coverId, contentUri = cover, displayName = "", mimeType = folder.coverMime.orEmpty(), sourceUrl = "", taskId = null, addedAt = 0)
                }
                CachedThumbnail(coverItem, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                IconTile(Icons.Filled.Folder, Tone.Violet, Modifier.align(Alignment.Center), size = TileSize.Hero)
            }
            // A folder with videos plays short pieces of a few of them, picked at random, over its cover.
            LivePreview(key = "folder-${folder.id}", clips = videos, modifier = Modifier.fillMaxSize())
            // Name and count on the bottom strip. Long-pressing the card tags or deletes the folder.
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                    .padding(start = Spacing.Md, end = Spacing.Md, top = Spacing.Lg, bottom = Spacing.Md),
            ) {
                Text(
                    folder.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // With a tag as the title, the folder's own name follows the count.
                val count = pluralStringResource(R.plurals.folder_items, folder.itemCount, folder.itemCount)
                Text(
                    folderSubtitle(folder.displayName, folder.tag)?.let { "$count · $it" } ?: count,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// endregion

// region Folder contents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderContent(
    viewModel: LibraryViewModel,
    folder: OpenFolder,
    modifier: Modifier,
    showBack: Boolean,
    onOpenViewer: (Int) -> Unit,
    restoreTarget: ViewerReturnTarget?,
    onFocusRestored: () -> Unit,
    foldersHidden: Boolean = false,
    onToggleFolders: (() -> Unit)? = null,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.itemQuery.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var detailsOf by remember { mutableStateOf<LibraryItem?>(null) }
    val itemsGridState = rememberLazyGridState()
    val tileFocus = remember { FocusRequester() }
    val restoreIndex = restoreTarget?.let { target ->
        items?.takeIf { it.isNotEmpty() }?.let { files ->
            files.indexOfFirst { it.id == target.itemId }.takeIf { it >= 0 }
                ?: target.index.coerceIn(0, files.lastIndex)
        }
    }
    val restoreId = restoreIndex?.let { items?.getOrNull(it)?.id }
    LaunchedEffect(restoreTarget, restoreIndex, restoreId) {
        if (restoreIndex == null || restoreId == null) return@LaunchedEffect
        // The viewer can advance farther than the grid's viewport. Keep the old scroll position if the tile is still visible.
        fun tileVisible() = itemsGridState.layoutInfo.visibleItemsInfo.any { it.key == restoreId }
        if (!tileVisible()) itemsGridState.scrollToItem(restoreIndex)
        snapshotFlow { tileVisible() }.first { it }
        withFrameNanos { }
        tileFocus.requestFocus()
        onFocusRestored()
    }
    val selecting = selection.isNotEmpty()
    val columns by viewModel.folderColumns.collectAsStateWithLifecycle()
    val tag by viewModel.openFolderTag.collectAsStateWithLifecycle()
    var tagging by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val saveScope = rememberCoroutineScope()
    // The selected files are copied into a folder of the person's choice (Files, a memory card, a cloud folder).
    val saveFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            val chosen = items.orEmpty().filter { it.id in selection }
            saveScope.launch {
                val saved = saveItemsToFolder(context, tree, chosen)
                val text = if (saved > 0) context.resources.getQuantityString(R.plurals.saved_files, saved, saved) else context.getString(R.string.save_failed)
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val favoritesExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            viewModel.exportFavoriteVideos(context.contentResolver, uri, folder.id) { count ->
                val text = if (count == null) context.getString(R.string.transfer_failed) else context.resources.getQuantityString(R.plurals.export_favorites_done, count, count)
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        if (selecting) stringResource(R.string.selected_count, selection.size) else folderTitle(folder.name, tag),
                        // One line, whatever buttons sit beside it: "3 selected" is set a little smaller, a long folder name is cut.
                        style = if (selecting) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The folder's own name sits under its tag.
                    val subtitle = if (selecting) null else folderSubtitle(folder.name, tag)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                if (selecting) {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                } else if (showBack) {
                    IconButton(onClick = viewModel::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                } else if (onToggleFolders != null) {
                    IconButton(onClick = onToggleFolders) {
                        Icon(
                            if (foldersHidden) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = stringResource(if (foldersHidden) R.string.action_show_folders else R.string.action_hide_folders),
                        )
                    }
                }
            },
            actions = {
                if (selecting) {
                    // Details is about one file, so the i appears only while exactly one is selected.
                    if (selection.size == 1) {
                        IconButton(onClick = { detailsOf = items?.firstOrNull { it.id in selection } }) {
                            Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.action_details))
                        }
                    }
                    // The files in Favorites are already copies kept in the app, so there is nothing to add them to.
                    if (!isFavoritesFolder(folder.name)) {
                        IconButton(onClick = {
                            viewModel.addSelectedToFavorites { added ->
                                val text = if (added > 0) context.resources.getQuantityString(R.plurals.favorites_added, added, added) else context.getString(R.string.favorites_none_added)
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Filled.Star, contentDescription = stringResource(R.string.action_favorite))
                        }
                    }
                    IconButton(onClick = { shareItems(context, items.orEmpty().filter { it.id in selection }) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(onClick = { saveFolderPicker.launch(null) }) {
                        Icon(Icons.Filled.SaveAlt, contentDescription = stringResource(R.string.action_save_to_folder))
                    }
                    IconButton(onClick = { confirmDelete = true }, colors = ActionColors.negativeIcon()) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                } else {
                    // Favorites can be exported as a sheet of its videos' parent links and video links.
                    if (isFavoritesFolder(folder.name)) {
                        FavoritesImportButton()
                        IconButton(onClick = {
                            if (items.orEmpty().none { it.mimeType.startsWith("video/") }) {
                                Toast.makeText(context, R.string.export_favorites_empty, Toast.LENGTH_SHORT).show()
                            } else {
                                favoritesExport.launch(context.getString(R.string.export_favorites_file_name))
                            }
                        }) {
                            Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.action_export_favorites))
                        }
                    }
                    IconButton(onClick = { tagging = true }) {
                        Icon(Icons.AutoMirrored.Filled.Label, contentDescription = stringResource(R.string.action_tag_folder))
                    }
                    val newestFirst by viewModel.folderNewestFirst.collectAsStateWithLifecycle()
                    ColumnsMenu(
                        columns = columns,
                        onSelect = viewModel::setFolderColumns,
                        newestFirst = newestFirst,
                        onSortChange = viewModel::setFolderNewestFirst,
                    )
                }
            },
        )
        PreviewProgressBanner(viewModel.previewStrips)
        if (showBack) {
            FavoritesImportBanner(viewModel.favoritesImporter)
        }
        if (SEARCH_VISIBLE && !selecting) SearchField(query, viewModel::onItemQueryChange)

        val current = items
        when {
            current == null -> ShimmerGrid(minSize = 110.dp, count = 12, shape = MaterialTheme.shapes.small, gap = Spacing.Sm)
            current.isEmpty() -> EmptyState(R.string.library_no_results, R.string.library_no_results_body, Icons.Filled.Search, Tone.Violet)
            else -> LazyVerticalGrid(
                state = itemsGridState,
                modifier = Modifier.softScrollEdges(itemsGridState),
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(Spacing.Md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.Sm),
            ) {
                items(current, key = { it.id }) { item ->
                    Thumbnail(
                        item = item,
                        selected = item.id in selection,
                        modifier = if (item.id == restoreId) Modifier.focusRequester(tileFocus) else Modifier,
                        onClick = {
                            if (selecting) viewModel.toggleSelected(item.id) else onOpenViewer(current.indexOf(item))
                        },
                        onLongClick = { viewModel.toggleSelected(item.id) },
                    )
                }
            }
        }
    }

    if (tagging) {
        TagDialog(
            initial = tag,
            onSave = {
                viewModel.setFolderTag(folder.id, it)
                tagging = false
            },
            onDismiss = { tagging = false },
        )
    }

    detailsOf?.let { item -> ItemDetailsDialog(item, onDismiss = { detailsOf = null }) }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_selected_title),
            confirmLabel = stringResource(R.string.action_delete),
            message = pluralStringResource(R.plurals.delete_selected_message, selection.size, selection.size),
            onConfirm = {
                viewModel.deleteSelected()
                confirmDelete = false
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Where a file came from: the link the user added, and the file's own direct address, each with a Copy button. */
@Composable
private fun ItemDetailsDialog(item: LibraryItem, onDismiss: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    fun copy(text: String) {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
        // Android 13 and later confirms a copy by itself.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.Md)) {
                // A favourite keeps the link it was copied from, so it can still be traced after that link is gone.
                val link = originalSource(item.sourceUrl)
                DetailsLink(stringResource(R.string.details_source_link), link, onCopy = { copy(link) })
                DetailsLink(
                    stringResource(R.string.details_file_address),
                    item.fileUrl,
                    onCopy = { item.fileUrl?.let(::copy) },
                    note = stringResource(if (item.fileUrl == null) R.string.details_file_address_missing else R.string.details_file_address_note),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun DetailsLink(label: String, url: String?, onCopy: () -> Unit, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url != null) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(url, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onCopy) { Text(stringResource(R.string.action_copy)) }
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What long-pressing a folder offers: give it a tag, or delete it. */
@Composable
private fun FolderActionsDialog(title: String, onTag: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                TextButton(onClick = onTag) { Text(stringResource(R.string.action_tag_folder)) }
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = ActionColors.Negative)) {
                    Text(stringResource(R.string.action_delete_folder))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Type the tag of a folder; it shows as "name / tag". Saving an empty tag takes the tag off. */
@Composable
private fun TagDialog(initial: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tag_folder_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tag_label)) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(text) },
                colors = ButtonDefaults.buttonColors(containerColor = ActionColors.Positive, contentColor = ActionColors.OnPositive),
            ) { Text(stringResource(if (text.isBlank() && !initial.isNullOrBlank()) R.string.action_remove_tag else R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** The grid button in a folder's top bar: choose how many columns the files are shown in (2 to 4). */
@Composable
private fun ColumnsMenu(columns: Int, onSelect: (Int) -> Unit, options: IntRange = 2..4, newestFirst: Boolean? = null, onSortChange: (Boolean) -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.GridView, contentDescription = stringResource(if (newestFirst != null) R.string.action_view_options else R.string.action_columns))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { count ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.columns_option, count)) },
                    trailingIcon = {
                        if (count == columns) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {
                        expanded = false
                        onSelect(count)
                    },
                )
            }
            // The folder list can also be put in date order.
            if (newestFirst != null) {
                if (!options.isEmpty()) HorizontalDivider()
                listOf(true to R.string.sort_newest, false to R.string.sort_oldest).forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        trailingIcon = {
                            if (value == newestFirst) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = {
                            expanded = false
                            onSortChange(value)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumbnail(item: LibraryItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .onRemoteLongPress(onLongClick)
            .focusRing(MaterialTheme.shapes.small)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (item.mimeType.isVisualMime()) {
                CachedThumbnail(item, item.displayName, Modifier.fillMaxSize())
                if (item.mimeType.startsWith("video/")) {
                    // Every video plays a silent live preview when it is on screen and has a turn.
                    LivePreview(key = "item-${item.id}", clips = listOf(item), modifier = Modifier.fillMaxSize())
                    if (LocalPreviewHost.current?.enabledBySetting == true) {
                        Icon(
                            Icons.Filled.PlayCircle,
                            contentDescription = null,
                            modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.Xs).size(20.dp),
                            tint = Color.White.copy(alpha = 0.9f),
                        )
                    } else {
                        Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = Color.White)
                    }
                }
            } else {
                IconTile(Icons.AutoMirrored.Filled.InsertDriveFile, Tone.Blue, size = TileSize.Large, contentDescription = item.displayName)
            }
            if (selected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.Sm),
                    tint = Color.White,
                )
            }
        }
    }
}

// endregion

// region Full-screen viewer

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Viewer(items: List<LibraryItem>, startIndex: Int, onDismiss: (ViewerReturnTarget?) -> Unit, onDelete: (Long) -> Unit, onFavorite: (LibraryItem, (Int) -> Unit) -> Unit) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss(null) }
        return
    }
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    // The file on screen is known by its key, which the pager keeps right when files are added or removed (a download saves
    // one now and then), and not by an index: that lags behind the list for a moment, and a page that looked inactive then
    // lost its player, so the video started over from 0:00.
    val keyOnScreen by remember(pagerState) {
        derivedStateOf { pagerState.layoutInfo.visiblePagesInfo.firstOrNull { it.index == pagerState.currentPage }?.key }
    }
    val idOnScreen = keyOnScreen ?: items.getOrNull(pagerState.currentPage)?.id
    val current = items.firstOrNull { it.id == idOnScreen }
    val dismiss = { onDismiss(ViewerReturnTarget(current?.id, pagerState.currentPage)) }
    val scope = rememberCoroutineScope()
    // "Save to Files" for the file on screen: the person names where it goes.
    val saveFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { target ->
        val item = current
        if (target != null && item != null) {
            scope.launch {
                val ok = saveItemToDocument(context, target, item)
                Toast.makeText(context, if (ok) context.resources.getQuantityString(R.plurals.saved_files, 1, 1) else context.getString(R.string.save_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
    // Which way the last edge swipe went (+1 to the next file, -1 to the previous), so the new picture slides in from there.
    var slide by remember { mutableIntStateOf(0) }
    // When the last slide to another file started, so the next one cannot follow at once (see VideoViewer's slideReady).
    var lastSlideAt by remember { mutableLongStateOf(0L) }
    // Files added to Favorites from here, so the star fills in at once.
    var favorited by remember { mutableStateOf(setOf<Long>()) }
    var itemToDelete by remember { mutableStateOf<LibraryItem?>(null) }
    var itemDetails by remember { mutableStateOf<LibraryItem?>(null) }
    // A zoomed-in video is dragged around, and a video is scrubbed by swiping, so the pager stays put on those pages.
    var zoomed by remember { mutableStateOf(false) }
    // The touch lock of the video player: while on, the back button and the top bar are off as well.
    var locked by remember { mutableStateOf(false) }
    var controlsFocusRequest by remember { mutableIntStateOf(0) }
    val onPlayablePage = current?.mimeType?.let { it.startsWith("video/") || it.startsWith("audio/") } == true
    // Closing the viewer gives the screen back to the system's own rotation (a video may have turned it to landscape).
    androidx.compose.runtime.DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        onDispose { activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // Drawn over the whole app (see MainActivity) instead of in a dialog window, whose size and position differ between
    // devices and left the library showing above and below the picture.
    // Always handled here, even while locked: if it were only switched off, the back press would fall through to the
    // library screen behind and close the folder (and with it the viewer).
    androidx.activity.compose.BackHandler { if (!locked) dismiss() }
    Box(
        Modifier
            .fillMaxSize()
            // Nor may the focus leave the viewer: the screen under it is still there, and a D-pad press would otherwise
            // move to a folder behind the picture.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .pointerInput(Unit) {
            // Nothing under the viewer may be touched through it.
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // A video page must sit exactly in the middle of the screen. Focus moving inside it can still nudge the pager
            // sideways although touch scrolling is off, so a nudge that is left over is undone at once.
            LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress, pagerState.currentPageOffsetFraction, onPlayablePage) {
                if (onPlayablePage && !pagerState.isScrollInProgress && pagerState.currentPageOffsetFraction != 0f) {
                    pagerState.scrollToPage(pagerState.currentPage)
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(
                app.gallerydl.ui.components.LocalPagerInfo provides {
                    "pager: page=${pagerState.currentPage}/${items.size}  offset=${"%.3f".format(pagerState.currentPageOffsetFraction)}  scrolling=${pagerState.isScrollInProgress}"
                },
            ) {
            HorizontalPager(
                pagerState,
                Modifier.fillMaxSize(),
                userScrollEnabled = !zoomed && !onPlayablePage,
                key = { items[it].id },
            ) { page ->
                ViewerPage(
                    items[page],
                    active = items[page].id == idOnScreen,
                    onZoomedChange = { zoomed = it },
                    onOpen = { openExternally(context, items[page]) },
                    locked = locked,
                    onLockedChange = { locked = it },
                    controlsFocusRequest = controlsFocusRequest,
                    canGoPrevious = page > 0,
                    canGoNext = page < items.lastIndex,
                    onStep = { step -> scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + step).coerceIn(0, items.lastIndex)) } },
                    enterFrom = slide,
                    slideReady = { android.os.SystemClock.elapsedRealtime() - lastSlideAt > 1500 },
                    onEdgeSwipe = { step ->
                        // The picture has already slid out; the next file takes the page and its picture slides in.
                        lastSlideAt = android.os.SystemClock.elapsedRealtime()
                        slide = step
                        scope.launch {
                            pagerState.scrollToPage((pagerState.currentPage + step).coerceIn(0, items.lastIndex))
                            delay(600)
                            slide = 0
                        }
                    },
                )
            }
            }
            itemDetails?.let { item -> ItemDetailsDialog(item, onDismiss = { itemDetails = null }) }
            itemToDelete?.let { item ->
                ConfirmDialog(
                    title = stringResource(R.string.delete_item_title),
                    message = stringResource(R.string.delete_item_message),
                    confirmLabel = stringResource(R.string.action_delete),
                    onConfirm = {
                        onDelete(item.id)
                        itemToDelete = null
                    },
                    onDismiss = { itemToDelete = null },
                )
            }
            // Zoomed in, the picture has the screen to itself: no title bar over it.
            if (!locked && !zoomed) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .padding(horizontal = Spacing.Xs, vertical = Spacing.Xs)
                        .onPreviewKeyEvent { event ->
                            if (!onPlayablePage || event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                            // The header is outside the pager. Send Down to the current player instead of relying on focus search.
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) controlsFocusRequest++
                            true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tint = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    IconButton(onClick = dismiss, colors = tint, modifier = Modifier.focusRing(CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                    Text(
                        current?.displayName.orEmpty(),
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (current != null) {
                        val inFavorites = current.sourceUrl.startsWith("favorite:") || current.id in favorited
                        IconButton(
                            onClick = {
                                if (inFavorites) {
                                    Toast.makeText(context, R.string.favorites_none_added, Toast.LENGTH_SHORT).show()
                                } else {
                                    onFavorite(current) { added ->
                                        favorited = favorited + current.id
                                        val text = if (added > 0) context.resources.getQuantityString(R.plurals.favorites_added, added, added) else context.getString(R.string.favorites_none_added)
                                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = tint,
                            modifier = Modifier.focusRing(CircleShape),
                        ) {
                            Icon(
                                if (inFavorites) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(R.string.action_favorite),
                            )
                        }
                    }
                    if (current != null) {
                        // Open and Delete share one menu: deleting a file is not a one-tap icon beside a harmless one.
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuOpen = true }, colors = tint, modifier = Modifier.focusRing(CircleShape)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_details)) },
                                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        itemDetails = current
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share)) },
                                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        shareItems(context, listOf(current))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_save_to_files)) },
                                    leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        saveFilePicker.launch(current.displayName)
                                    },
                                )
                                if (app.gallerydl.CrashLog.file(context).isFile) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_crash_share)) },
                                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            shareFile(context, app.gallerydl.CrashLog.file(context), "text/plain")
                                        },
                                    )
                                }
                                if (current.mimeType.startsWith("video/")) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (app.gallerydl.ui.components.PlayerMonitor.enabled) R.string.action_monitor_off else R.string.action_monitor_on)) },
                                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            app.gallerydl.ui.components.PlayerMonitor.set(context, !app.gallerydl.ui.components.PlayerMonitor.enabled)
                                        },
                                    )
                                    if (app.gallerydl.ui.components.PlayerMonitor.enabled) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_monitor_share)) },
                                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                            onClick = {
                                                menuOpen = false
                                                shareFile(context, app.gallerydl.ui.components.PlayerMonitor.logFile(context), "text/plain")
                                            },
                                        )
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_open_with)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        openExternally(context, current)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete), color = ActionColors.Negative) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = ActionColors.Negative) },
                                    onClick = {
                                        menuOpen = false
                                        itemToDelete = current
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerPage(item: LibraryItem, active: Boolean, onZoomedChange: (Boolean) -> Unit, onOpen: () -> Unit, locked: Boolean, onLockedChange: (Boolean) -> Unit, controlsFocusRequest: Int, canGoPrevious: Boolean, canGoNext: Boolean, enterFrom: Int, onEdgeSwipe: (Int) -> Unit, onStep: (Int) -> Unit, slideReady: () -> Boolean) {
    if (item.mimeType.startsWith("video/") || item.mimeType.startsWith("audio/")) {
        VideoViewer(Uri.parse(item.contentUri), isAudio = item.mimeType.startsWith("audio/"), active = active, onZoomedChange = onZoomedChange, onOpen = onOpen, locked = locked, onLockedChange = onLockedChange, controlsFocusRequest = controlsFocusRequest,
            canGoPrevious = canGoPrevious, canGoNext = canGoNext, enterFrom = enterFrom, onEdgeSwipe = onEdgeSwipe, slideReady = slideReady)
        return
    }
    // The page takes the focus, so a remote's left and right keys step through the files.
    val focus = remember { FocusRequester() }
    LaunchedEffect(active) {
        if (active) {
            delay(150)
            runCatching { focus.requestFocus() }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.MediaPrevious, Key.PageUp, Key.ChannelUp -> { if (canGoPrevious) onStep(-1); true }
                    Key.DirectionRight, Key.MediaNext, Key.PageDown, Key.ChannelDown -> { if (canGoNext) onStep(1); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        if (item.mimeType.isVisualMime()) {
            val picture: @Composable () -> Unit = {
                GalleryImage(
                    Uri.parse(item.contentUri),
                    item.displayName,
                    Modifier.fillMaxSize(),
                    ContentScale.Fit,
                    showShimmer = false,
                )
            }
            if (item.mimeType.startsWith("image/")) ZoomableBox(onZoomedChange, content = picture) else picture()
        }
        // Videos and other files are played or opened by the user's own apps.
        if (!item.mimeType.startsWith("image/")) {
            IconButton(onClick = onOpen, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (item.mimeType.startsWith("video/")) Icons.Filled.PlayCircle else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = stringResource(R.string.action_open),
                    modifier = Modifier.size(64.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

// endregion

private const val SEARCH_VISIBLE = false

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Md, vertical = Spacing.Xs),
        label = { Text(stringResource(R.string.search_label)) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
    )
}

private fun String?.isVisualMime() = this != null && (startsWith("image/") || startsWith("video/"))

private fun openExternally(context: Context, item: LibraryItem) {
    var uri = Uri.parse(item.contentUri)
    // A favourite is a file inside the app, which other apps can only be given through the file provider.
    if (uri.scheme == "file") uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", java.io.File(requireNotNull(uri.path)))
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, item.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}
