package app.gallerydl.sync

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import app.gallerydl.R
import app.gallerydl.data.DownloadRepository
import app.gallerydl.data.LibraryRepository
import app.gallerydl.download.originalSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A copy of the app on the network. */
data class SyncDevice(val id: String, val name: String, val platform: String, val host: String, val port: Int)

/** Another device asking to send this one its links and favorite videos (the videos themselves are sent, [bytes] is the total size). */
data class SyncOffer(val from: String, val links: Int, val videos: Int, val bytes: Long)

sealed interface SyncStatus {
    /** [file] and [fraction] describe the file in flight right now (fraction 0..1, null when unknown). */
    data class Running(val title: String, val done: Int, val total: Int, val file: String? = null, val fraction: Float? = null) : SyncStatus
    /** [details] lists what was left out and why, one line each. */
    data class Finished(val text: String, val details: List<String> = emptyList()) : SyncStatus
    data class Failed(val text: String) : SyncStatus
}

data class SyncUiState(
    val devices: List<SyncDevice> = emptyList(),
    val status: SyncStatus? = null,
    val offer: SyncOffer? = null,
    val problem: String? = null,
    val deviceName: String = "",
    val port: Int = 0,
    /** A VPN is on: it can swallow what other devices send to this one. */
    val vpn: Boolean = false,
)

/**
 * Runs device sync while the Sync screen is open, over LocalSend's protocol (see LocalSend.kt): the other copies of the app are
 * found by themselves, a send is offered to the other device's person, who accepts or declines, and nothing is stored before
 * that. There is no pairing.
 */
class SyncManager(
    private val context: Context,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
    private val tv = !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    private val _state = MutableStateFlow(SyncUiState(deviceName = prefs.getString("deviceName", null) ?: android.os.Build.MODEL ?: "Android"))
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private var server: HttpServer? = null
    private var receiver: LocalSendReceiver? = null
    private var discovery: SyncDiscovery? = null
    private var collector: Job? = null
    private var announceJob: Job? = null
    private var job: Job? = null
    private var added = HashMap<String, FoundDevice>()
    @Volatile private var openSocket: Socket? = null
    @Volatile private var answer: CompletableFuture<Boolean>? = null
    @Volatile private var receivingFrom = ""
    private val alreadyHere = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun info() = DeviceInfo(_state.value.deviceName, deviceId, _state.value.port.takeIf { it > 0 } ?: LOCALSEND_PORT, deviceType = if (tv) "desktop" else "mobile")
    private val client = LocalSendClient { info() }

    private val listener = object : ReceiveListener {
        override fun myInfo() = info()

        override fun registered(device: DeviceInfo, address: String) {
            discovery?.note(device, address)
        }

        override fun wanted(files: List<FileInfo>): List<FileInfo> {
            // Each video carries its address and name, so what is already in Favorites is left out before anything is uploaded.
            alreadyHere.clear()
            val keep = ArrayList<FileInfo>()
            for (f in files) {
                val key = f.preview?.takeIf { f.id.startsWith("fav-") }?.let { runCatching { JSONObject(it) }.getOrNull() }
                val same = key?.let { runBlocking { library.favoriteMatch(it.optString("p"), it.optString("v"), it.optString("n")) } }
                if (same != null) alreadyHere.add(f.id) else keep += f
            }
            val videos = keep.count { it.id.startsWith("fav-") }
            val links = keep.any { it.fileName == LINKS_FILE }
            if (videos == 0 && !links) {
                _state.update { it.copy(status = SyncStatus.Finished(context.getString(R.string.sync_all_here, alreadyHere.size))) }
                return emptyList()
            }
            // The list of videos is only worth reading when some of them are new.
            return if (videos == 0) keep.filter { it.fileName != FAVORITES_FILE } else keep
        }

        override fun offered(from: DeviceInfo, address: String, files: List<FileInfo>): Boolean {
            val links = files.firstOrNull { it.fileName == LINKS_FILE }?.preview?.toIntOrNull() ?: 0
            val videos = files.count { it.id.startsWith("fav-") }
            val pending = CompletableFuture<Boolean>()
            answer = pending
            receivingFrom = from.alias
            _state.update { it.copy(offer = SyncOffer(from.alias, links, videos, files.sumOf { f -> f.size })) }
            return try { pending.get(90, TimeUnit.SECONDS) } catch (_: Exception) { false } finally {
                answer = null
                _state.update { it.copy(offer = null) }
            }
        }

        override fun fileProgress(done: Int, total: Int, name: String, fraction: Float) {
            _state.update { it.copy(status = SyncStatus.Running(context.getString(R.string.sync_receiving, receivingFrom), done, total, name, fraction)) }
        }

        override fun progress(received: Int, total: Int) {
            _state.update { it.copy(status = SyncStatus.Running(context.getString(R.string.sync_receiving, receivingFrom), received, total)) }
        }

        override fun finished(from: DeviceInfo, dir: File, files: List<FileInfo>) {
            scope.launch(Dispatchers.IO) {
                val status = try { apply(dir, files).let { SyncStatus.Finished(it.text, it.details) } } catch (e: Exception) { SyncStatus.Finished(context.getString(R.string.sync_could_not_save_all), listOf(why(e))) }
                dir.deleteRecursively()
                _state.update { it.copy(status = status) }
            }
        }

        override fun cancelled() {
            _state.update { it.copy(status = SyncStatus.Finished(context.getString(R.string.sync_stopped))) }
        }
    }

    // region Starting and stopping

    @Synchronized
    fun start() {
        if (server != null) return
        _state.update { it.copy(problem = null, vpn = vpnActive()) }
        val receiver = LocalSendReceiver(context.cacheDir, listener)
        val server = HttpServer(receiver)
        val port = try { server.start(LOCALSEND_PORT) } catch (_: Exception) {
            _state.update { it.copy(problem = context.getString(R.string.sync_cannot_listen)) }
            return
        }
        this.server = server
        this.receiver = receiver
        _state.update { it.copy(port = port) }
        val discovery = SyncDiscovery(context, ::info, client)
        this.discovery = discovery
        discovery.start()
        collector = scope.launch {
            discovery.found.collect { refresh(it) }
        }
    }

    @Synchronized
    fun stop() {
        collector?.cancel()
        announceJob?.cancel()
        answer?.complete(false)
        discovery?.stop()
        receiver?.stop()
        server?.close()
        discovery = null
        receiver = null
        server = null
        added.clear()
        _state.update { it.copy(port = 0, devices = emptyList()) }
    }

    private fun refresh(found: Map<String, FoundDevice>) {
        val all = HashMap(found)
        for ((id, device) in added) all.putIfAbsent(id, device)
        val devices = all.values.map { SyncDevice(it.info.fingerprint, it.info.alias, it.info.deviceType, it.host, it.info.port) }.sortedBy { it.name.lowercase() }
        _state.update { it.copy(devices = devices) }
    }

    fun rename(name: String) {
        prefs.edit().putString("deviceName", name).apply()
        _state.update { it.copy(deviceName = name) }
        // Typing a name is announced once it pauses, not at every letter.
        announceJob?.cancel()
        announceJob = scope.launch {
            delay(700)
            discovery?.scanNow()
        }
    }

    @Suppress("DEPRECATION")
    private fun vpnActive(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    fun searchAgain() {
        _state.update { it.copy(vpn = vpnActive()) }
        discovery?.scanNow()
    }

    /** Adds a device by its address (for a network that hides devices from each other), as shown on its own Sync screen. */
    suspend fun add(text: String): String? = withContext(Dispatchers.IO) {
        val parts = text.trim().split(":")
        val port = if (parts.size == 2) parts[1].trim().toIntOrNull()?.takeIf { it in 1..65535 } else LOCALSEND_PORT
        if (parts.isEmpty() || parts[0].isBlank() || parts.size > 2 || port == null) return@withContext context.getString(R.string.sync_bad_address)
        // The port that was typed is the one that answered, whatever port the device says it uses.
        val who = (client.register(parts[0], port, connectTimeout = 3000) ?: return@withContext context.getString(R.string.sync_could_not_reach)).copy(port = port)
        added[who.fingerprint] = FoundDevice(who, parts[0], System.currentTimeMillis())
        discovery?.note(who, parts[0])
        refresh(discovery?.found?.value.orEmpty())
        null
    }

    // endregion

    // region Receiving

    fun accept() { answer?.complete(true) }

    fun decline() { answer?.complete(false) }

    /** What a sync did, for the person to read: a sentence, and one line for each thing that was left out. */
    private class Outcome(val text: String, val details: List<String> = emptyList())

    private suspend fun apply(dir: File, files: List<FileInfo>): Outcome {
        val toSave = files.count { it.id.startsWith("fav-") }
        var savedSoFar = 0
        var linksAdded = 0
        var favoritesAdded = 0
        val left = ArrayList<String>()
        val byName = files.associateBy { it.fileName }
        byName[LINKS_FILE]?.let { f ->
            val array = JSONObject(File(dir, safe(f.id)).readText()).optJSONArray("links") ?: org.json.JSONArray()
            for (i in 0 until array.length()) {
                val link = array.getJSONObject(i)
                val url = link.optString("url")
                // Only web links: what another device sends is still checked before it reaches Home.
                val web = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
                if (web && !downloads.hasLink(url)) {
                    downloads.enqueue(url, link.optString("site").ifBlank { null })
                    linksAdded++
                }
            }
        }
        byName[FAVORITES_FILE]?.let { f ->
            val array = JSONObject(File(dir, safe(f.id)).readText()).optJSONArray("items") ?: org.json.JSONArray()
            val web = { u: String -> u.isEmpty() || u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true) }
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: run { left += "item ${i + 1}: unreadable entry in the list"; null } ?: continue
                val name = item.optString("name").ifBlank { "(no name)" }
              try {
                val received = files.firstOrNull { it.id == item.optString("id") }
                if (received != null) _state.update { it.copy(status = SyncStatus.Running(context.getString(R.string.sync_saving), savedSoFar++, toSave, name, null)) }
                if (received == null) { if (item.optString("id") !in alreadyHere) left += "$name: the video did not arrive"; continue }
                val file = File(dir, safe(received.id))
                val parent = item.optString("parent")
                val video = item.optString("video")
                val mime = item.optString("mime").ifBlank { received.fileType }
                when {
                    !file.isFile || file.length() == 0L -> { left += "$name: the file arrived empty"; continue }
                    !web(parent) || !web(video) -> { left += "$name: its saved address is not a web address, so it was not kept"; continue }
                }
                // A video Favorites already has is left alone.
                val same = library.favoriteMatch(parent, video, name)
                if (same != null) { left += "$name: already in Favorites ($same)"; continue }
                val saved = library.saveFavoriteFile(parent, video, name, mime, moveFrom = file) { out -> file.inputStream().use { it.copyTo(out) } }
                if (saved) favoritesAdded++ else left += "$name: the file could not be written on this device (is the storage full?)"
              } catch (e: Exception) { left += "$name: ${why(e)}" }
            }
        }
        val resources = context.resources
        val here = alreadyHere.size
        val text = (if (here > 0 && linksAdded + favoritesAdded > 0) summary(linksAdded, favoritesAdded) + " " + context.getString(R.string.sync_already_here, here)
            else if (here > 0 && left.isEmpty()) context.getString(R.string.sync_all_here, here) else summary(linksAdded, favoritesAdded)) + if (left.isNotEmpty()) " " + resources.getQuantityString(R.plurals.sync_left_out, left.size, left.size) else ""
        return Outcome(text, left)
    }

    /** The real reason, with where it happened, so a failure is never just "could not be saved". */
    private fun why(e: Throwable): String {
        val at = e.stackTrace.firstOrNull { it.className.startsWith("app.gallerydl") }?.let { " at ${it.fileName}:${it.lineNumber}" }.orEmpty()
        return "${e.javaClass.simpleName}: ${e.message ?: "no message"}$at"
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun summary(links: Int, favorites: Int): String {
        if (links == 0 && favorites == 0) return context.getString(R.string.sync_nothing_new)
        val parts = ArrayList<String>()
        val resources = context.resources
        if (links > 0) parts += resources.getQuantityString(R.plurals.sync_links_added, links, links)
        if (favorites > 0) parts += resources.getQuantityString(R.plurals.sync_favorites_added, favorites, favorites)
        return parts.joinToString(", ") + "."
    }

    // endregion

    // region Sending

    /** Offers this device's links and favorite videos to [device]; its person decides. */
    fun send(device: SyncDevice, withLinks: Boolean = true, withFavorites: Boolean = true) {
        if (_state.value.status is SyncStatus.Running) return
        _state.update { it.copy(status = SyncStatus.Running(context.getString(R.string.sync_sending, device.name), 0, 0)) }
        job = scope.launch(Dispatchers.IO) {
            val status = try {
                sendTo(device, withLinks, withFavorites).let { SyncStatus.Finished(it.text, it.details) }
            } catch (_: CancellationException) {
                SyncStatus.Finished(context.getString(R.string.sync_stopped))
            } catch (e: Exception) {
                if (job?.isCancelled == true) SyncStatus.Finished(context.getString(R.string.sync_stopped))
                else SyncStatus.Failed(context.getString(R.string.sync_could_not_reach) + " (" + (e.message?.take(120) ?: e.javaClass.simpleName) + ")" +
                    if (e is java.net.SocketTimeoutException || e is java.net.ConnectException) " " + context.getString(R.string.sync_timeout_hint) else "")
            }
            _state.update { it.copy(status = status) }
        }
    }

    /** What this device has to offer, and which favorites cannot be sent and why. */
    class Counts(val links: Int, val favorites: Int, val bytes: Long, val leftOut: List<String>)

    suspend fun counts(): Counts {
        val plan = favoritePlan()
        return Counts(links().size, plan.send.size, plan.send.sumOf { f -> f.info.size }, plan.leftOut)
    }

    private suspend fun links() = downloads.observeTasks().first().sortedBy { it.id }.map { SyncLink(it.url, it.site.orEmpty(), it.folderName.orEmpty()) }

    /** A favorite video to send: where it lives, how big it is, and the file to read. */
    private class OutgoingFavorite(val info: SyncFavorite, val uri: Uri)

    private class FavoritePlan(val send: List<OutgoingFavorite>, val leftOut: List<String>)

    /** Every favorite, sorted into what will be sent and what cannot be (with the reason), so nothing goes missing silently. */
    private suspend fun favoritePlan(): FavoritePlan {
        val send = ArrayList<OutgoingFavorite>()
        val left = ArrayList<String>()
        val seen = HashSet<String>()
        for (item in library.favoriteItems()) {
            val uri = Uri.parse(item.contentUri)
            val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L }.getOrDefault(0L)
            val parent = originalSource(item.sourceUrl)
            val video = item.fileUrl.orEmpty()
            when {
                size <= 0 -> left += "${item.displayName}: the file is missing or empty on this device"
                !seen.add(parent + "\n" + video + "\n" + item.displayName) -> left += "${item.displayName}: the same video is in Favorites twice here (same address and name)"
                else -> send += OutgoingFavorite(SyncFavorite("", parent, video, item.displayName, item.mimeType, size), uri)
            }
        }
        return FavoritePlan(send, left)
    }

    private suspend fun sendTo(device: SyncDevice, withLinks: Boolean, withFavorites: Boolean): Outcome {
        val links = if (withLinks) links() else emptyList()
        val plan = if (withFavorites) favoritePlan() else FavoritePlan(emptyList(), emptyList())
        val favorites = plan.send
        if (links.isEmpty() && favorites.isEmpty()) return Outcome(context.getString(R.string.sync_nothing_to_send), plan.leftOut)
        val outgoing = ArrayList<OutgoingFile>()
        if (links.isNotEmpty()) {
            val bytes = linksJson(links)
            outgoing += OutgoingFile(FileInfo("links", LINKS_FILE, bytes.size.toLong(), "application/json", links.size.toString())) { bytes.inputStream() }
        }
        if (favorites.isNotEmpty()) {
            val ids = favorites.mapIndexed { index, f -> "fav-$index" to f }
            val bytes = favoritesJson(ids.map { (id, f) -> id to f.info })
            outgoing += OutgoingFile(FileInfo("favorites", FAVORITES_FILE, bytes.size.toLong(), "application/json", favorites.size.toString())) { bytes.inputStream() }
            // Each video goes as a file of its own, named as the manifest says.
            for ((id, f) in ids) {
                outgoing += OutgoingFile(FileInfo(id, f.info.name, f.info.size, f.info.mime, JSONObject().put("p", f.info.parent).put("v", f.info.video).put("n", f.info.name).toString())) { requireNotNull(context.contentResolver.openInputStream(f.uri)) }
            }
        }
        val outcome = client.send(
            device.host, device.port, outgoing,
            progress = { done, total -> _state.update { it.copy(status = SyncStatus.Running(context.getString(R.string.sync_sending_to, device.name), done, total)) } },
            fileProgress = { done, total, name, fraction -> _state.update { it.copy(status = SyncStatus.Running(context.getString(R.string.sync_sending_to, device.name), done, total, name, fraction)) } },
            cancelled = { job?.isCancelled == true },
            connection = { openSocket = it },
        )
        return when (outcome) {
            SendOutcome.SENT -> {
                val resources = context.resources
                val note = if (plan.leftOut.isNotEmpty()) " " + resources.getQuantityString(R.plurals.sync_not_sent, plan.leftOut.size, plan.leftOut.size) else ""
                Outcome(context.getString(R.string.sync_sent, device.name) + note, plan.leftOut)
            }
            SendOutcome.DECLINED -> Outcome(context.getString(R.string.sync_declined, device.name))
            SendOutcome.BUSY -> Outcome(context.getString(R.string.sync_busy, device.name))
            SendOutcome.NOTHING -> Outcome(context.getString(R.string.sync_other_has_all, device.name), plan.leftOut)
        }
    }

    fun cancel() {
        job?.cancel()
        // A transfer waiting on the network would not notice; closing the connection ends it.
        runCatching { openSocket?.close() }
    }

    fun dismissStatus() = _state.update { if (it.status is SyncStatus.Running) it else it.copy(status = null) }

    // endregion
}
