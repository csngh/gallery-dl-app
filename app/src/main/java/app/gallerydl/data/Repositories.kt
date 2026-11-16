package app.gallerydl.data

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.gallerydl.download.DownloadWorker
import app.gallerydl.download.FAVORITES_FOLDER
import app.gallerydl.download.favoriteSource
import app.gallerydl.download.originalSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first

/** In-memory set of running tasks the user asked to cancel; polled by the Python listener. */
class CancelRegistry {
    private val ids = ConcurrentHashMap.newKeySet<Long>()
    fun request(id: Long) = ids.add(id)
    fun isRequested(id: Long) = id in ids
    fun clear(id: Long) = ids.remove(id)
}

class DownloadRepository(
    private val dao: TaskDao,
    private val workManager: WorkManager,
    private val cancelRegistry: CancelRegistry,
    private val archiveDir: File,
    private val settings: SettingsRepository,
) {
    fun observeTasks(): Flow<List<DownloadTask>> = dao.observeAll()

    /** Saves a link as "ready to start". Nothing downloads until [start] or [startAll] is called. */
    /** True when this link is already on Home, whatever state it is in. */
    suspend fun hasLink(url: String): Boolean = dao.observeAll().first().any { it.url == url }

    suspend fun enqueue(url: String, site: String?) {
        dao.insert(DownloadTask(url = url, site = site, status = TaskStatus.PENDING, createdAt = System.currentTimeMillis()))
    }

    /** [folderName] is the folder the user chose for the link; null keeps the folder gallery-dl names. */
    suspend fun start(id: Long, folderName: String? = null) {
        if (folderName != null) dao.setFolderName(id, folderName)
        if (dao.start(id) > 0) scheduleDrain()
    }

    suspend fun startAll() {
        if (dao.startAll() > 0) scheduleDrain()
    }

    /**
     * Stops everything at once: the links waiting for their turn are cancelled first, so the queue cannot move on to
     * the next one, and then the download that is running. Each link keeps its record of what was saved, so a retry only
     * fetches the rest.
     */
    suspend fun cancelAll() {
        val ids = dao.activeIds()
        val now = System.currentTimeMillis()
        // A waiting link is cancelled directly; whatever is left is the one that is running.
        ids.filter { dao.cancelIfQueued(it, now) == 0 }.forEach { cancelRegistry.request(it) }
    }

    suspend fun cancel(id: Long) {
        if (dao.cancelIfQueued(id, System.currentTimeMillis()) == 0) cancelRegistry.request(id)
    }

    suspend fun retry(id: Long) {
        dao.requeue(id)
        scheduleDrain()
    }

    /** Replaces the link of a failed or cancelled task and queues it again. The old archive no longer applies. */
    suspend fun editLink(id: Long, url: String, site: String?) {
        // A link that has not started just changes; it keeps waiting for the user to start it.
        if (dao.replacePendingLink(id, url, site) > 0) return
        deleteArchive(id)
        if (dao.replaceLink(id, url, site) > 0) scheduleDrain()
    }

    /** The Renew button: every link checks for new files, and links not yet started begin. */
    suspend fun renewAll() {
        if (dao.renewAll() > 0) scheduleDrain()
    }

    /** The Renew button of one website: only its links check for new files, and the ones not yet started begin. */
    suspend fun renew(ids: List<Long>) {
        if (ids.isNotEmpty() && dao.renewIds(ids) > 0) scheduleDrain()
    }

    suspend fun retryAllFailed() {
        dao.requeueFailed()
        scheduleDrain()
    }

    suspend fun remove(id: Long) {
        deleteArchive(id)
        dao.delete(id)
    }

    /**
     * The Queue's "Clear finished": the links leave the Queue but stay on Home, which is the collection of links to
     * refresh. A later Renew fetches only what the library does not have.
     */
    suspend fun clearFinished() = dao.hideFinished()

    /** Settings > Clear history: really deletes the finished links, and the memory of what each had saved. */
    private suspend fun deleteFinishedLinks() {
        dao.finishedIds().forEach { deleteArchive(it) }
        dao.deleteFinished()
    }

    private suspend fun deleteArchive(id: Long) = withContext(Dispatchers.IO) {
        archiveDir.listFiles { f -> f.name.startsWith("$id.sqlite") }?.forEach { it.delete() }
    }

    /**
     * One worker drains the queue sequentially. APPEND_OR_REPLACE guarantees a task inserted just as a
     * worker is finishing still gets picked up by the next run.
     */
    private suspend fun scheduleDrain(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        // Wi-Fi only waits for an unmetered connection; otherwise any connection will do.
        val network = if (settings.options.first().wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        workManager.enqueueUniqueWork(DownloadWorker.UNIQUE_NAME, policy, request)
    }

    /**
     * Applies a change of the Wi-Fi only setting to work that is already waiting. A download in progress is
     * paused and carries on from the files the library already has, so no file is fetched twice.
     */
    suspend fun applyNetworkSetting() {
        if (dao.nextQueued() != null) scheduleDrain(ExistingWorkPolicy.REPLACE)
    }

    /** Starts the queue again after it was paused for lack of storage. */
    suspend fun resume() {
        if (dao.nextQueued() != null) scheduleDrain()
    }

    suspend fun clearFinishedHistory() = deleteFinishedLinks()
}

private const val MAX_REDIRECTS = 5
private const val MAX_FAILURES = 30

class LibraryRepository(
    private val dao: LibraryDao,
    private val resolver: ContentResolver,
    private val thumbnails: ThumbnailCache,
    private val previews: PreviewStripCache,
    /** Where favourites are kept: inside the app, not in shared storage. */
    private val favoritesDir: File,
) {
    fun observeFolders(query: String) = dao.observeFolders(query)
    fun observeItems(folderId: Long, query: String) = dao.observeItems(folderId, query)
    fun observeVideos() = dao.observeVideos()

    suspend fun addItem(item: LibraryItem) = dao.insertItem(item)

    suspend fun fileNamesFor(sourceUrl: String) = dao.fileNamesFor(sourceUrl)

    suspend fun fileUrlsFor(sourceUrl: String) = dao.fileUrlsFor(sourceUrl)

    /**
     * True when this link already has a file of this name in this folder. A last check before saving: whatever made the
     * download fetch a known file again (an address that changes on every run, say), the same video is never saved twice.
     */
    suspend fun hasFileIn(sourceUrl: String, name: String, folder: String): Boolean {
        val folderId = dao.folderByName(folder)?.id ?: return false
        val wanted = Normalizer.normalize(name, Normalizer.Form.NFC)
        return dao.itemsOfLink(sourceUrl).any {
            it.folderId == folderId && Normalizer.normalize(it.displayName, Normalizer.Form.NFC) == wanted
        }
    }

    /**
     * A file the link already had is not fetched again: it gets the address it has now and, if it was saved by an earlier
     * download of the same link (the link was removed and added again), it now belongs to this one. Returns true when
     * it was handed over, so the download can count it.
     */
    suspend fun adoptFile(sourceUrl: String, name: String, fileUrl: String, taskId: Long): Boolean {
        var handedOver = false
        for (item in dao.itemsOfLink(sourceUrl).filter { isSameFile(it, name, fileUrl) }) {
            // A site that gives no address this time must not wipe the one the file already has.
            if (fileUrl.isNotBlank() && item.fileUrl != fileUrl) dao.setItemFileUrl(item.id, fileUrl)
            if (item.taskId != taskId) {
                dao.setItemTask(item.id, taskId)
                handedOver = true
            }
        }
        return handedOver
    }

    /**
     * The same file of the same link: by its name (in one fixed spelling, so accents compare equal however they were stored)
     * or by its direct address, which stays the same when a name that carries a position number changes.
     */
    private fun isSameFile(item: LibraryItem, name: String, fileUrl: String): Boolean =
        Normalizer.normalize(item.displayName, Normalizer.Form.NFC) == Normalizer.normalize(name, Normalizer.Form.NFC) ||
            (fileUrl.isNotBlank() && item.fileUrl == fileUrl)

    /**
     * A file the library already has, met again by a refreshed link: it moves into the folder gallery-dl names for it now.
     * [moveFile] moves the file itself where that is possible (it is best effort); the library entry moves either way. A
     * folder this empties is removed, and its tag goes to the new folder when that has none.
     */
    suspend fun moveToFolder(sourceUrl: String, name: String, fileUrl: String, folder: String, moveFile: (LibraryItem) -> Unit) {
        val items = dao.itemsOfLink(sourceUrl).filter { isSameFile(it, name, fileUrl) }
        val existing = dao.folderByName(folder)
        val pending = items.filter { it.folderId != existing?.id }
        if (pending.isEmpty()) return
        val target = ensureFolder(folder)
        for (item in pending) {
            val old = item.folderId
            runCatching { moveFile(item) }
            dao.setItemFolder(item.id, target)
            if (old != null && dao.countInFolder(old) == 0) {
                val tag = dao.folderById(old)?.tag
                if (tag != null && dao.folderById(target)?.tag == null) dao.setFolderTag(target, tag)
                dao.deleteFolder(old)
            }
        }
    }

    fun observeFolderTag(folderId: Long) = dao.observeFolderTag(folderId)

    /** Gives a folder a tag, or takes it off when [tag] is blank. Long tags are cut to 40 characters. */
    suspend fun setFolderTag(folderId: Long, tag: String) = dao.setFolderTag(folderId, tag.trim().take(40).ifEmpty { null })

    /**
     * Copies files into the Favorites folder. The copies live inside the app, so they stay when the link or the folder
     * they came from is deleted, and go only when deleted here. Each copy keeps the link and the file's own address.
     * Returns how many were added; a file already in Favorites is skipped.
     */
    suspend fun addFavorites(items: List<LibraryItem>): Int = withContext(Dispatchers.IO) {
        favoritesDir.mkdirs()
        var added = 0
        for (item in items) {
            if (isFavoriteSource(item)) continue
            val source = favoriteSource(item.sourceUrl)
            val have = dao.itemsOfLink(source)
            if (have.any { isSameFile(it, item.displayName, item.fileUrl.orEmpty()) }) continue
            val target = uniqueFile(item.displayName)
            val copied = runCatching {
                resolver.openInputStream(Uri.parse(item.contentUri))?.use { input -> target.outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
            if (!copied) {
                target.delete()
                continue
            }
            val copy = LibraryItem(
                contentUri = Uri.fromFile(target).toString(),
                displayName = item.displayName,
                mimeType = item.mimeType,
                sourceUrl = source,
                taskId = null,
                folderId = ensureFolder(FAVORITES_FOLDER),
                addedAt = System.currentTimeMillis(),
                fileUrl = item.fileUrl,
            )
            val id = dao.insertItem(copy)
            previews.requestMake(copy.copy(id = id))
            added++
        }
        added
    }

    /**
     * Adds the videos of an exported favorites sheet to Favorites. A video already in Favorites is left alone; one the library
     * has (by its name under the same parent link, or its address) is copied from there; the rest are fetched from their
     * video links. An address that no longer works (for example a signature that ran out) is asked for again from the
     * parent link through [resolve], which gives the current address of each named file. [onProgress] gets the number of
     * rows handled so far.
     */
    suspend fun importFavoriteVideos(
        rows: List<FavoriteRow>,
        resolve: suspend (parent: String, names: List<String>) -> Map<String, String>,
        onProgress: (done: Int, result: FavoritesImportResult) -> Unit,
        onStep: (FavoriteStep) -> Unit = {},
    ): FavoritesImportResult = withContext(Dispatchers.IO) {
        // A long import must not take time from what the person is watching: on a weak TV chip a video played meanwhile
        // would stutter. The thread goes back to normal when the import is over, as the pool's threads are reused.
        val tid = android.os.Process.myTid()
        val normal = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try {
        favoritesDir.mkdirs()
        val all = dao.allItems()
        val favorites = all.filter { isFavoriteSource(it) }
        val library = all.filter { !isFavoriteSource(it) && it.folderId != null }
        var result = FavoritesImportResult()
        var handled = 0
        fun failed(name: String, why: String) {
            result = result.copy(failed = result.failed + 1, failures = (result.failures + (name to why)).takeLast(MAX_FAILURES))
        }
        // Rows whose own address did not work, by parent link: they get a second try through the parent.
        class Retry(val name: String, val firstError: String)
        val retry = LinkedHashMap<String, MutableList<Retry>>()
        for (row in rows) {
            // The user can stop the import: each row is a point where that takes effect.
            currentCoroutineContext().ensureActive()
            val name = row.name.ifBlank { nameFromUrl(row.video) }
            val same = { item: LibraryItem, link: String ->
                (row.video.isNotBlank() && item.fileUrl == row.video) || (link == row.parent && nfc(item.displayName) == nfc(name))
            }
            when {
                favorites.any { same(it, originalSource(it.sourceUrl)) } -> result = result.copy(duplicates = result.duplicates + 1)
                else -> {
                    val local = library.firstOrNull { same(it, it.sourceUrl) }
                    if (local != null) {
                        result = if (addFavorites(listOf(local)) > 0) result.copy(copied = result.copied + 1) else result.copy(duplicates = result.duplicates + 1)
                    } else {
                        val fetched = if (row.video.isNotBlank()) fetchFavorite(row.parent, row.video, name, onStep) else Fetched(error = "no saved address")
                        when {
                            fetched.item != null -> result = result.copy(downloaded = result.downloaded + 1)
                            row.parent.startsWith("http") -> {
                                retry.getOrPut(row.parent) { mutableListOf() }.add(Retry(name, fetched.error))
                                // Counted now, so the progress keeps moving; its second try comes after the others.
                                onProgress(++handled, result)
                                continue
                            }
                            else -> failed(name, fetched.error + ", and there is no link to look it up from")
                        }
                    }
                }
            }
            onProgress(++handled, result)
        }
        for ((parent, pending) in retry) {
            currentCoroutineContext().ensureActive()
            onStep(FavoriteStep(pending.first().name, 0, -1, lookingUp = true))
            val current = try {
                resolve(parent, pending.map { it.name })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
            for (item in pending) {
                currentCoroutineContext().ensureActive()
                val address = current[nfc(item.name)]
                if (address == null) {
                    failed(item.name, "${item.firstError}; the link does not list a file by this name any more")
                } else {
                    val fetched = fetchFavorite(parent, address, item.name, onStep)
                    if (fetched.item != null) result = result.copy(downloaded = result.downloaded + 1) else failed(item.name, fetched.error)
                }
                onProgress(handled, result)
            }
        }
        result
        } finally {
            android.os.Process.setThreadPriority(tid, normal)
        }
    }

    /** What came of fetching one video: the item saved, or why that did not work. */
    private class Fetched(val item: LibraryItem? = null, val error: String = "")

    /** Downloads [video] into the favourites folder and records it; the result says why not when it could not. */
    private suspend fun fetchFavorite(parent: String, video: String, name: String, onStep: (FavoriteStep) -> Unit = {}): Fetched {
        var target: File? = null
        var failure = "could not be reached"
        val item = runCatching {
            var address = java.net.URL(video)
            var connection: java.net.HttpURLConnection? = null
            // Redirects are followed here, one by one: the system follows none that change between http and https.
            for (hop in 0..MAX_REDIRECTS) {
                connection?.disconnect()
                connection = (address.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                    setRequestProperty("Accept", "*/*")
                    if (parent.startsWith("http")) setRequestProperty("Referer", parent)
                }
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val next = connection.getHeaderField("Location") ?: break
                    address = java.net.URL(address, next)
                    continue
                }
                break
            }
            val response = connection ?: return@runCatching null
            try {
                if (response.responseCode !in 200..299) {
                    failure = "the site answered ${response.responseCode}"
                    return@runCatching null
                }
                val type = response.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase()
                val byName = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                val mime = when {
                    type.startsWith("video/") -> type
                    // A page, a data file or a playlist is not the video, whatever the address looks like.
                    type.startsWith("text/") || type.contains("json") || type.contains("xml") || type.contains("mpegurl") -> {
                        failure = "the site sent a page, not a video ($type)"
                        return@runCatching null
                    }
                    // Anything else (a stream, "octet-stream", a wrong label) is taken as the file the name says it is.
                    else -> byName?.takeIf { it.startsWith("video/") } ?: if (ext.isEmpty() || byName == null) "video/mp4" else {
                        failure = "not a video ($type)"
                        return@runCatching null
                    }
                }
                // A name with no ending gets the one of its type, so other apps know what the file is.
                val fileName = if (ext.isEmpty()) name + "." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4") else name
                val file = uniqueFile(fileName).also { target = it }
                response.inputStream.use { input ->
                    file.outputStream().use { out ->
                        // Chunk by chunk, so stopping the import takes effect within moments instead of after the whole video.
                        val buffer = ByteArray(64 * 1024)
                        val length = response.contentLengthLong
                        var read = 0L
                        var last = 0L
                        onStep(FavoriteStep(name, 0, length))
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - last >= 250) { last = now; onStep(FavoriteStep(name, read, length)) }
                        }
                        onStep(FavoriteStep(name, read, length))
                    }
                }
                if (file.length() == 0L) {
                    failure = "the site sent an empty file"
                    return@runCatching null
                }
                val saved = LibraryItem(
                    contentUri = Uri.fromFile(file).toString(),
                    displayName = file.name,
                    mimeType = mime,
                    sourceUrl = favoriteSource(parent.ifBlank { video }),
                    taskId = null,
                    folderId = ensureFolder(FAVORITES_FOLDER),
                    addedAt = System.currentTimeMillis(),
                    fileUrl = video,
                )
                val id = dao.insertItem(saved)
                saved.copy(id = id).also { previews.requestMake(it) }
            } finally {
                response.disconnect()
            }
        }.onFailure { failure = it.message?.take(80)?.let { m -> "failed: $m" } ?: "could not be reached" }.getOrNull()
        if (item == null) target?.delete()
        // runCatching also caught a stop; it must end the import, not count as one more failed video.
        currentCoroutineContext().ensureActive()
        return Fetched(item, if (item == null) failure else "")
    }

    /** The file name a direct video address ends in, without its query. */
    private fun nameFromUrl(url: String): String =
        Uri.decode(url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')).ifBlank { "video" }

    private fun nfc(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC)

    // region Sync

    /** Every file in Favorites. */
    suspend fun favoriteItems(): List<LibraryItem> = dao.allItems().filter { isFavoriteSource(it) }

    /** True when Favorites already has this video: by its address, or by its name under the same parent link. */
    suspend fun hasFavorite(parent: String, video: String, name: String): Boolean = favoriteMatch(parent, video, name) != null

    /** Why Favorites counts this video as already there (the name of the one it matches and how), or null when it is new. */
    suspend fun favoriteMatch(parent: String, video: String, name: String): String? {
        for (it in favoriteItems()) {
            if (video.isNotBlank() && it.fileUrl == video) return "same video address as \"${it.displayName}\""
            if (originalSource(it.sourceUrl) == parent && nfc(it.displayName) == nfc(name)) return "same name and link as \"${it.displayName}\""
        }
        return null
    }

    /** Saves a file that came from another device into Favorites; [write] fills it. False when nothing could be saved. */
    suspend fun saveFavoriteFile(parent: String, video: String, name: String, mime: String, moveFrom: File? = null, write: (java.io.OutputStream) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            favoritesDir.mkdirs()
            val target = uniqueFile(name)
            // A failure carries its real reason up to the sync report instead of being turned into a bare "no".
            try {
                // A file that is already on this device's storage is moved, not copied: a copy needs room for it twice.
                if (moveFrom != null && !target.exists() && moveFrom.renameTo(target)) Unit else target.outputStream().use(write)
                if (target.length() == 0L) throw java.io.IOException("the saved file is empty")
            } catch (e: Exception) {
                target.delete()
                throw java.io.IOException("could not write \"${target.name}\" (${target.name.toByteArray().size} bytes long name): ${e.message ?: e.javaClass.simpleName}", e)
            }
            val saved = LibraryItem(
                contentUri = Uri.fromFile(target).toString(),
                displayName = target.name,
                mimeType = mime,
                sourceUrl = favoriteSource(parent.ifBlank { video }),
                taskId = null,
                folderId = ensureFolder(FAVORITES_FOLDER),
                addedAt = System.currentTimeMillis(),
                fileUrl = video.ifBlank { null },
            )
            val id = dao.insertItem(saved)
            previews.requestMake(saved.copy(id = id))
            true
        }

    // endregion

    private fun isFavoriteSource(item: LibraryItem) = item.sourceUrl.startsWith("favorite:")

    /** A free file name in the favourites folder: the original name, or with a number when it is taken. */
    private fun uniqueFile(name: String): File {
        val whole = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val extRaw = whole.substringAfterLast('.', "").takeIf { it.length in 1..8 }
        val ext = if (extRaw == null) "" else ".$extRaw"
        // A long title (common on the iPhone) can pass 255 bytes, which the file system refuses: cut it, keeping the ending.
        var base = if (extRaw == null) whole else whole.removeSuffix(ext)
        while (base.toByteArray().size > 150) base = base.dropLast(1)
        val clean = base + ext
        var file = File(favoritesDir, clean)
        var counter = 1
        while (file.exists()) file = File(favoritesDir, "$base ($counter)$ext").also { counter++ }
        return file
    }

    /** Returns the id of the folder called [name], creating it if needed. */
    suspend fun ensureFolder(name: String): Long {
        val inserted = dao.insertFolder(Folder(name = name))
        return if (inserted != -1L) inserted else dao.folderByName(name)!!.id
    }

    suspend fun deleteItems(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (ids.isNotEmpty()) dao.itemsByIds(ids).forEach { remove(it) }
    }

    /** Deletes every file saved for a link, then the folder itself. */
    suspend fun deleteFolder(folderId: Long) = withContext(Dispatchers.IO) {
        dao.itemsInFolder(folderId).forEach { remove(it) }
        dao.deleteFolder(folderId)
    }

    /** Drops library entries whose file was deleted outside the app. */
    suspend fun pruneMissing() = withContext(Dispatchers.IO) {
        val all = dao.allItems()
        all.filterNot { exists(Uri.parse(it.contentUri)) }.forEach {
            thumbnails.delete(listOf(it.id))
            previews.delete(listOf(it.id))
            dao.deleteItem(it)
        }
        // Thumbnails whose item is gone (for example after a reinstall) are cleaned up here too.
        val remaining = dao.allItems().map { it.id }.toSet()
        thumbnails.prune(remaining)
        previews.prune(remaining)
    }

    /** Emits whenever MediaStore reports a change, e.g. a file removed in another app. */
    fun mediaChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(MediaStore.AUTHORITY_URI, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    /** Removes the file from shared storage as well as the library entry. */
    private suspend fun remove(item: LibraryItem) {
        runCatching {
            val uri = Uri.parse(item.contentUri)
            if (uri.scheme == "file") {
                // A favourite: the copy kept inside the app.
                File(requireNotNull(uri.path)).delete()
            } else if (uri.authority == MediaStore.AUTHORITY) {
                resolver.delete(uri, null, null)
            } else {
                DocumentsContract.deleteDocument(resolver, uri)
            }
        }
        thumbnails.delete(listOf(item.id))
        previews.delete(listOf(item.id))
        dao.deleteItem(item)
    }

    /**
     * A lost folder permission is not evidence the file is gone, so only a definite
     * "not found" counts as missing.
     */
    private fun exists(uri: Uri): Boolean = try {
        resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: FileNotFoundException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: Exception) {
        true
    }
}
