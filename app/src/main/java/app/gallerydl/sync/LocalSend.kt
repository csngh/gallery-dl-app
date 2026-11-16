package app.gallerydl.sync

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors

/*
 * Device sync speaks LocalSend's protocol (v2, plain HTTP, https://github.com/localsend/protocol): devices find each other
 * by themselves (a multicast announcement on 224.0.0.167, or by asking every address on the network with
 * POST /api/localsend/v2/register), and a sender offers files with POST /prepare-upload. The receiver's person says yes or
 * no, and only then does the sender POST each file to /upload. There is no pairing: nothing is taken in without a tap.
 *
 * Gallery DL tells its own copies apart by deviceModel "Gallery DL", and sends its links and favorite videos as ordinary
 * files: gallery-dl-links.json, gallery-dl-favorites.json (the details of each video) and the videos themselves.
 */

/**
 * Gallery DL's own port, next to LocalSend's 53317: the LocalSend app, when it is installed on the same device, keeps its port, and
 * the two do not see each other's announcements. The protocol is the same.
 */
const val LOCALSEND_PORT = 53318
const val LOCALSEND_GROUP = "224.0.0.167"
const val APP_MODEL = "Gallery DL"
const val LINKS_FILE = "gallery-dl-links.json"
const val FAVORITES_FILE = "gallery-dl-favorites.json"
private const val API = "/api/localsend/v2"
private const val MAX_HEAD = 64 * 1024
private const val MAX_JSON = 16 * 1024 * 1024
private const val OFFER_WAIT_SECONDS = 90L
private const val SESSION_IDLE_MS = 60 * 1000L

/** A device as LocalSend describes it. */
data class DeviceInfo(
    val alias: String,
    val fingerprint: String,
    val port: Int,
    val deviceType: String = "mobile",
    val deviceModel: String? = APP_MODEL,
    val version: String = "2.0",
    val protocol: String = "http",
) {
    val isGalleryDl: Boolean get() = deviceModel == APP_MODEL

    fun toJson(): JSONObject = JSONObject().put("alias", alias).put("version", version).put("deviceModel", deviceModel ?: JSONObject.NULL)
        .put("deviceType", deviceType).put("fingerprint", fingerprint).put("port", port).put("protocol", protocol).put("download", false)

    companion object {
        fun from(json: JSONObject, defaultPort: Int = LOCALSEND_PORT) = DeviceInfo(
            alias = json.optString("alias", "Device"),
            fingerprint = json.optString("fingerprint"),
            port = json.optInt("port", defaultPort),
            deviceType = json.optString("deviceType", "desktop"),
            deviceModel = if (json.isNull("deviceModel")) null else json.optString("deviceModel"),
            version = json.optString("version", "2.0"),
            protocol = json.optString("protocol", "http"),
        )
    }
}

/** One file of an offer. [preview] carries a short note (for the links file, how many links it holds). */
data class FileInfo(val id: String, val fileName: String, val size: Long, val fileType: String, val preview: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("fileName", fileName).put("size", size).put("fileType", fileType)
        .put("sha256", JSONObject.NULL).put("preview", preview ?: JSONObject.NULL).put("metadata", JSONObject.NULL)
}

class OutgoingFile(val info: FileInfo, val open: () -> InputStream)

class SyncException(message: String) : IOException(message)

// region HTTP

class HttpRequest internal constructor(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val remoteAddress: String,
)

class HttpResponse(val status: Int, val body: ByteArray = ByteArray(0), val contentType: String = "application/json") {
    companion object {
        fun json(json: JSONObject, status: Int = 200) = HttpResponse(status, json.toString().toByteArray())
        fun empty(status: Int) = HttpResponse(status)
    }
}

private fun reason(status: Int) = when (status) {
    200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
    409 -> "Conflict"; 429 -> "Too Many Requests"; else -> "Error"
}

private fun readLine(input: InputStream, limit: Int = 8192): String? {
    val line = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0) return if (line.size() == 0) null else throw EOFException()
        if (b == '\n'.code) break
        if (b != '\r'.code) line.write(b)
        if (line.size() > limit) throw SyncException("line too long")
    }
    return line.toString(Charsets.ISO_8859_1.name())
}

private fun parseQuery(raw: String?): Map<String, String> =
    raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        val parts = it.split('=', limit = 2)
        java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }

private fun readHeaders(input: InputStream): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    var size = 0
    while (true) {
        val line = readLine(input) ?: throw EOFException()
        if (line.isEmpty()) return headers
        size += line.length
        if (size > MAX_HEAD) throw SyncException("headers too large")
        val colon = line.indexOf(':')
        if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
}

/** At most [limit] bytes of [input]. */
private class Limited(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val b = input.read()
        if (b >= 0) left--
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (left <= 0) return -1
        val read = input.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (read > 0) left -= read
        return read
    }
}

/** A body sent in chunks ("Transfer-Encoding: chunked"), read as one stream. */
private class Chunked(private val input: InputStream) : InputStream() {
    private var left = 0L
    private var done = false

    private fun next() {
        if (left > 0 || done) return
        val size = readLine(input)?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: throw EOFException()
        if (size == 0L) {
            while (readLine(input)?.isNotEmpty() == true) { /* trailers */ }
            done = true
        } else {
            left = size
        }
    }

    override fun read(): Int {
        next()
        if (done) return -1
        val b = input.read()
        if (b < 0) throw EOFException()
        left--
        if (left == 0L) readLine(input)
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        next()
        if (done) return -1
        val read = input.read(buffer, offset, minOf(length.toLong(), left).toInt())
        if (read < 0) throw EOFException()
        left -= read
        if (left == 0L) readLine(input)
        return read
    }
}

fun interface HttpHandler {
    fun handle(request: HttpRequest): HttpResponse
}

/** A small HTTP/1.1 server: one thread for each connection, which may carry several requests. */
class HttpServer(private val handler: HttpHandler) : Closeable {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "localsend").apply { isDaemon = true } }
    private val active = Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var closed = false

    /** Listens on [preferred], or on any free port when that is taken. Returns the port. */
    fun start(preferred: Int = LOCALSEND_PORT): Int {
        val socket = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferred)) }
        } catch (_: IOException) {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) }
        }
        server = socket
        pool.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                if (active.size >= 64 || closed) {
                    runCatching { client.close() }
                    continue
                }
                active.add(client)
                // Closed between the accept and now: the sweep in close() may have missed this one.
                if (closed) runCatching { client.close() }
                pool.execute {
                    runCatching { serve(client) }
                    active.remove(client)
                    runCatching { client.close() }
                }
            }
        }
        return socket.localPort
    }

    val port: Int get() = server?.localPort ?: 0

    override fun close() {
        closed = true
        runCatching { server?.close() }
        server = null
        synchronized(active) { active.toList() }.forEach { runCatching { it.close() } }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 120_000
        val input = socket.getInputStream().buffered(64 * 1024)
        val output = socket.getOutputStream().buffered(64 * 1024)
        val remote = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress?.substringBefore('%').orEmpty()
        while (!closed) {
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val headers = readHeaders(input)
            val target = parts[1]
            val body: InputStream = when {
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> Chunked(input)
                else -> Limited(input, headers["content-length"]?.toLongOrNull() ?: 0L)
            }
            val request = HttpRequest(parts[0].uppercase(), target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), headers, body, remote)
            val response = try { handler.handle(request) } catch (_: Exception) { HttpResponse.empty(500) }
            // What the handler left unread must still be read, or the next request would start in the middle of it.
            val buffer = ByteArray(8192)
            while (body.read(buffer) >= 0) { /* drain */ }
            writeResponse(output, response, keepAlive = headers["connection"]?.equals("close", ignoreCase = true) != true)
            if (headers["connection"]?.equals("close", ignoreCase = true) == true) return
        }
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse, keepAlive: Boolean) {
        val head = StringBuilder("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
        if (response.status != 204) head.append("Content-Type: ${response.contentType}\r\n")
        head.append("Content-Length: ${response.body.size}\r\nConnection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }
}

/** One request to another device, over a connection of its own. Returns the status and the body. */
fun httpRequest(
    host: String,
    port: Int,
    method: String,
    pathAndQuery: String,
    contentType: String = "application/json",
    length: Long = 0,
    writeBody: (OutputStream) -> Unit = {},
    connectTimeout: Int = 3000,
    readTimeout: Int = 30_000,
    onSocket: (Socket) -> Unit = {},
): Pair<Int, ByteArray> {
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), connectTimeout)
        socket.soTimeout = readTimeout
        onSocket(socket)
        val output = socket.getOutputStream().buffered(64 * 1024)
        output.write(
            ("$method $pathAndQuery HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: $contentType\r\nContent-Length: $length\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.ISO_8859_1),
        )
        writeBody(output)
        output.flush()
        val input = socket.getInputStream().buffered(64 * 1024)
        val status = readLine(input)?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw SyncException("no answer")
        val headers = readHeaders(input)
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> Chunked(input).readBytes()
            headers.containsKey("content-length") -> Limited(input, headers.getValue("content-length").toLong()).readBytes()
            else -> input.readBytes()
        }
        if (body.size > MAX_JSON) throw SyncException("answer too large")
        return status to body
    }
}

// endregion

// region Receiving

/** What the receiving side does with what arrives; the app implements it. */
interface ReceiveListener {
    fun myInfo(): DeviceInfo

    /** Another copy of this app announced itself (or answered a search): it is a device to show. */
    fun registered(device: DeviceInfo, address: String)

    /** Blocks until the person says yes (true) or no (false). */
    fun offered(from: DeviceInfo, address: String, files: List<FileInfo>): Boolean

    /** Before the person is asked: the files worth sending, so what this device already has is never uploaded. */
    fun wanted(files: List<FileInfo>): List<FileInfo> = files

    fun progress(received: Int, total: Int)

    /** How far the file now arriving has come: [done] of [total] files are finished, and this one is at [fraction]. */
    fun fileProgress(done: Int, total: Int, name: String, fraction: Float) {}

    /** Everything arrived: [files] are in [dir], each named by its file id. */
    fun finished(from: DeviceInfo, dir: File, files: List<FileInfo>)

    fun cancelled()
}

/** The receiving half of LocalSend's protocol. Only one offer is dealt with at a time. */
class LocalSendReceiver(private val cacheDir: File, private val listener: ReceiveListener, private val idleMs: Long = SESSION_IDLE_MS) : HttpHandler {
    private class Session(val id: String, val from: DeviceInfo, val address: String, val dir: File, val files: Map<String, FileInfo>, val tokens: Map<String, String>) {
        val received = HashSet<String>()
        @Volatile var lastActive = System.currentTimeMillis()
    }

    private var session: Session? = null
    @Volatile private var asking = false
    private val watchdog = java.util.Timer("sync-session", true)

    init {
        // Folders left by an earlier run that was killed mid-transfer.
        cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("sync-") }?.forEach { it.deleteRecursively() }
        // A sender that vanished mid-transfer would otherwise keep this device "busy" until the next request came in.
        watchdog.schedule(object : java.util.TimerTask() { override fun run() { current() } }, minOf(10_000L, idleMs), minOf(10_000L, idleMs))
    }

    private fun current(): Session? {
        var ended = false
        val now = synchronized(this) {
            session?.let { if (System.currentTimeMillis() - it.lastActive > idleMs) { drop(it); ended = true } }
            session
        }
        if (ended) listener.cancelled()
        return now
    }

    @Synchronized private fun drop(s: Session) {
        if (session === s) session = null
        s.dir.deleteRecursively()
    }

    override fun handle(request: HttpRequest): HttpResponse {
        if (!request.path.startsWith(API)) return HttpResponse.empty(404)
        return when (request.method to request.path.removePrefix(API)) {
            "GET" to "/info" -> HttpResponse.json(listener.myInfo().toJson())
            "POST" to "/register" -> register(request)
            "POST" to "/prepare-upload" -> prepare(request)
            "POST" to "/upload" -> upload(request)
            "POST" to "/cancel" -> cancel(request)
            else -> HttpResponse.empty(404)
        }
    }

    private fun readJson(request: HttpRequest): JSONObject = JSONObject(String(Limited(request.body, MAX_JSON.toLong()).readBytes()))

    private fun register(request: HttpRequest): HttpResponse {
        val device = try { DeviceInfo.from(readJson(request)) } catch (_: Exception) { return HttpResponse.empty(400) }
        if (device.isGalleryDl && device.fingerprint != listener.myInfo().fingerprint) listener.registered(device, request.remoteAddress)
        return HttpResponse.json(listener.myInfo().toJson())
    }

    private fun prepare(request: HttpRequest): HttpResponse {
        val body = try { readJson(request) } catch (_: Exception) { return HttpResponse.empty(400) }
        val from = try { DeviceInfo.from(body.getJSONObject("info")) } catch (_: Exception) { return HttpResponse.empty(400) }
        val offeredList = try {
            val map = body.getJSONObject("files")
            map.keys().asSequence().map { key ->
                val f = map.getJSONObject(key)
                FileInfo(f.optString("id", key), f.getString("fileName"), f.optLong("size"), f.optString("fileType", "application/octet-stream"), if (f.isNull("preview")) null else f.optString("preview"))
            }.toList()
        } catch (_: Exception) { return HttpResponse.empty(400) }
        // Only another copy of this app can send here: what arrives is read as this app's own links and videos.
        if (!from.isGalleryDl) return HttpResponse.empty(403)
        if (offeredList.isEmpty()) return HttpResponse.empty(204)
        val list = listener.wanted(offeredList)
        if (list.isEmpty()) return HttpResponse.empty(204)
        synchronized(this) {
            if (current() != null || asking) return HttpResponse.empty(409)
            asking = true
        }
        val accepted = try { listener.offered(from, request.remoteAddress, list) } finally { asking = false }
        if (!accepted) return HttpResponse.empty(403)
        val id = UUID.randomUUID().toString()
        val dir = File(cacheDir, "sync-$id").apply { mkdirs() }
        val tokens = list.associate { it.id to UUID.randomUUID().toString() }
        synchronized(this) { session = Session(id, from, request.remoteAddress, dir, list.associateBy { it.id }, tokens) }
        return HttpResponse.json(JSONObject().put("sessionId", id).put("files", JSONObject(tokens)))
    }

    private fun upload(request: HttpRequest): HttpResponse {
        val s = current() ?: return HttpResponse.empty(409)
        val q = request.query
        val fileId = q["fileId"] ?: return HttpResponse.empty(400)
        if (q["sessionId"] != s.id || q["token"] == null) return HttpResponse.empty(403)
        if (s.tokens[fileId] != q["token"] || request.remoteAddress != s.address) return HttpResponse.empty(403)
        s.files[fileId] ?: return HttpResponse.empty(400)
        s.lastActive = System.currentTimeMillis()
        val target = File(s.dir, fileId.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        var written = 0L
        var lastReport = 0L
        try {
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = request.body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    // A long file keeps the session alive for as long as it keeps arriving.
                    s.lastActive = System.currentTimeMillis()
                    written += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        val info = s.files.getValue(fileId)
                        if (info.size > 0) listener.fileProgress(synchronized(s) { s.received.size }, s.files.size, info.fileName, (written.toFloat() / info.size).coerceIn(0f, 1f))
                    }
                }
            }
        } catch (_: IOException) {
            target.delete()
            return HttpResponse.empty(500)
        }
        // A sender that dropped half way leaves a short file; a half video must never be taken as the whole one.
        val expected = s.files.getValue(fileId).size
        if (expected > 0 && target.length() != expected) {
            target.delete()
            return HttpResponse.empty(400)
        }
        val done = synchronized(s) { s.received.add(fileId); s.received.size to s.files.size }
        s.lastActive = System.currentTimeMillis()
        listener.progress(done.first, done.second)
        if (done.first == done.second) {
            val files = s.files.values.toList()
            // The files are handed over under their ids; the session ends here and the folder goes with the handover.
            synchronized(this) { if (session === s) session = null }
            listener.finished(s.from, s.dir, files)
        }
        return HttpResponse.empty(200)
    }

    private fun cancel(request: HttpRequest): HttpResponse {
        val s = current()
        if (s != null && request.query["sessionId"] == s.id) {
            drop(s)
            listener.cancelled()
        }
        return HttpResponse.empty(200)
    }

    fun stop() {
        watchdog.cancel()
        synchronized(this) { session?.let { drop(it) } }
    }
}

// endregion

// region Sending

enum class SendOutcome { SENT, DECLINED, BUSY, NOTHING }

/** The sending half. */
class LocalSendClient(private val me: () -> DeviceInfo) {
    /** Announces this device to the one at [host]:[port]; returns that device when it is a copy of this app. */
    fun register(host: String, port: Int = LOCALSEND_PORT, connectTimeout: Int = 700): DeviceInfo? = try {
        val body = me().toJson().toString().toByteArray()
        val (status, answer) = httpRequest(host, port, "POST", "$API/register", length = body.size.toLong(), writeBody = { it.write(body) }, connectTimeout = connectTimeout, readTimeout = 2500)
        if (status == 200) DeviceInfo.from(JSONObject(String(answer)), port).takeIf { it.isGalleryDl && it.fingerprint != me().fingerprint } else null
    } catch (_: Exception) {
        null
    }

    /**
     * Offers [files] to the device at [host]:[port] and, once its person says yes, sends them. [progress] hears about each
     * file done; [cancelled] is asked between pieces; [connection] gets the open connection so a cancel can close it.
     */
    fun send(host: String, port: Int, files: List<OutgoingFile>, progress: (Int, Int) -> Unit = { _, _ -> }, fileProgress: (Int, Int, String, Float) -> Unit = { _, _, _, _ -> }, cancelled: () -> Boolean = { false }, connection: (Socket?) -> Unit = {}): SendOutcome {
        val offer = JSONObject().put("info", me().toJson()).put("files", JSONObject().also { map -> files.forEach { map.put(it.info.id, it.info.toJson()) } })
        val bytes = offer.toString().toByteArray()
        // The receiver's person is asked, so this answer can take a while.
        val (status, answer) = httpRequest(host, port, "POST", "$API/prepare-upload", length = bytes.size.toLong(), writeBody = { it.write(bytes) }, readTimeout = (90_000 + 15_000), onSocket = { connection(it) })
        connection(null)
        when (status) {
            200 -> Unit
            204 -> return SendOutcome.NOTHING
            403 -> return SendOutcome.DECLINED
            409 -> return SendOutcome.BUSY
            else -> throw SyncException("the other device answered $status")
        }
        val reply = JSONObject(String(answer))
        val sessionId = reply.getString("sessionId")
        val tokens = reply.getJSONObject("files")
        var done = 0
        // The receiver may ask for fewer files than were offered: it leaves out what it already has.
        val total = files.count { tokens.optString(it.info.id).isNotEmpty() }
        try {
            for (file in files) {
                if (cancelled()) throw SyncException("cancelled")
                val token = tokens.optString(file.info.id)
                if (token.isEmpty()) continue
                val query = "$API/upload?sessionId=${enc(sessionId)}&fileId=${enc(file.info.id)}&token=${enc(token)}"
                val (code, _) = file.open().use { input ->
                    httpRequest(host, port, "POST", query, contentType = "application/octet-stream", length = file.info.size, writeBody = { out ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            sent += n
                            val now = System.currentTimeMillis()
                            if (now - last > 250 && file.info.size > 0) { last = now; fileProgress(done, total, file.info.fileName, (sent.toFloat() / file.info.size).coerceIn(0f, 1f)) }
                        }
                    }, readTimeout = 120_000, onSocket = { connection(it) })
                }
                connection(null)
                if (code != 200) throw SyncException("the other device answered $code")
                progress(++done, total)
            }
        } catch (e: Exception) {
            runCatching { httpRequest(host, port, "POST", "$API/cancel?sessionId=${enc(sessionId)}", connectTimeout = 1500, readTimeout = 3000) }
            throw e
        }
        return SendOutcome.SENT
    }

    private fun enc(text: String) = java.net.URLEncoder.encode(text, "UTF-8")
}

// endregion

/** The manifests that tell a receiving copy what the videos are; plain JSON files. */
fun linksJson(links: List<SyncLink>): ByteArray =
    JSONObject().put("links", JSONArray(links.map { JSONObject().put("url", it.url).put("site", it.site).put("folder", it.folder) })).toString().toByteArray()

fun favoritesJson(items: List<Pair<String, SyncFavorite>>): ByteArray =
    JSONObject().put("items", JSONArray(items.map { (fileId, f) ->
        JSONObject().put("id", fileId).put("parent", f.parent).put("video", f.video).put("name", f.name).put("mime", f.mime)
    })).toString().toByteArray()

data class SyncLink(val url: String, val site: String, val folder: String)
data class SyncFavorite(val id: String, val parent: String, val video: String, val name: String, val mime: String, val size: Long)
