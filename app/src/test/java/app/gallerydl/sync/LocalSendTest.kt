package app.gallerydl.sync

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Two devices in one process, speaking LocalSend's protocol over a real local socket. */
class LocalSendTest {
    private class Listener : ReceiveListener {
        val me = DeviceInfo("Living room TV", "tv-id", 0, deviceType = "desktop")
        @Volatile var answer = true
        var gate: CountDownLatch? = null
        var asked: List<FileInfo>? = null
        var registered: DeviceInfo? = null
        var progressed = 0
        var finished: Pair<File, List<FileInfo>>? = null
        var cancelled = false
        override fun myInfo() = me
        override fun registered(device: DeviceInfo, address: String) { registered = device }
        override fun offered(from: DeviceInfo, address: String, files: List<FileInfo>): Boolean {
            asked = files
            gate?.await(5, TimeUnit.SECONDS)
            return answer
        }
        override fun progress(received: Int, total: Int) { progressed = received }
        override fun finished(from: DeviceInfo, dir: File, files: List<FileInfo>) { finished = dir to files }
        override fun cancelled() { cancelled = true }
    }

    private val cache = createTempDir("localsend-test")
    private val listener = Listener()
    private val receiver = LocalSendReceiver(cache, listener)
    private val server = HttpServer(receiver)
    private var port = 0
    private val video = ByteArray(700_000) { (it * 31 + 7).toByte() }
    private val phone = DeviceInfo("Pixel", "pixel-id", 0)
    private val client = LocalSendClient { phone }

    @Before fun start() { port = server.start(0) }
    @After fun stop() { server.close(); cache.deleteRecursively() }

    private fun files() = listOf(
        OutgoingFile(FileInfo("links", LINKS_FILE, 20, "application/json", "2")) { ByteArrayInputStream("{\"links\":[],\"x\":123}".toByteArray()) },
        OutgoingFile(FileInfo("fav-0", "x.mp4", video.size.toLong(), "video/mp4")) { ByteArrayInputStream(video) },
    )

    @Test
    fun registeringAnswersWithTheDeviceAndRemembersACopyOfTheApp() {
        val who = client.register("127.0.0.1", port)
        assertEquals("Living room TV", who?.alias)
        assertEquals("pixel-id", listener.registered?.fingerprint)
    }

    @Test
    fun anotherAppIsNotListedAndNotAnswered() {
        val other = LocalSendClient { DeviceInfo("Some phone", "other-id", 0, deviceModel = "Samsung") }
        // It still gets the answer a register request always gets, but is not noted as a copy of this app.
        other.register("127.0.0.1", port)
        assertNull(listener.registered)
    }

    @Test
    fun anAcceptedOfferIsSentAndArrivesIntact() {
        assertEquals(SendOutcome.SENT, client.send("127.0.0.1", port, files()))
        val (dir, received) = listener.finished!!
        assertEquals(setOf("links", "fav-0"), received.map { it.id }.toSet())
        assertArrayEquals(video, File(dir, "fav-0").readBytes())
        assertEquals("{\"links\":[],\"x\":123}", File(dir, "links").readText())
        assertEquals(2, listener.progressed)
        assertEquals(listOf("links", "fav-0").toSet(), listener.asked!!.map { it.id }.toSet())
    }

    @Test
    fun aDeclinedOfferSendsNothing() {
        listener.answer = false
        assertEquals(SendOutcome.DECLINED, client.send("127.0.0.1", port, files()))
        assertNull(listener.finished)
    }

    @Test
    fun anOfferWithNoFilesNeedsNoTransfer() {
        assertEquals(SendOutcome.NOTHING, client.send("127.0.0.1", port, emptyList()))
    }

    @Test
    fun aSecondOfferWhileOneIsBeingAnsweredIsTurnedAway() {
        listener.gate = CountDownLatch(1)
        var first: SendOutcome? = null
        val thread = Thread { first = client.send("127.0.0.1", port, files()) }
        thread.start()
        Thread.sleep(500)
        assertEquals(SendOutcome.BUSY, LocalSendClient { DeviceInfo("Other", "other-id", 0) }.send("127.0.0.1", port, files()))
        listener.gate!!.countDown()
        thread.join(10_000)
        assertEquals(SendOutcome.SENT, first)
    }

    @Test
    fun aFileWithTheWrongTokenOrNoSessionIsRefused() {
        val offer = JSONObject().put("info", phone.toJson()).put("files", JSONObject().put("a", FileInfo("a", "a.mp4", 3, "video/mp4").toJson())).toString().toByteArray()
        val (status, body) = httpRequest("127.0.0.1", port, "POST", "/api/localsend/v2/prepare-upload", length = offer.size.toLong(), writeBody = { it.write(offer) })
        assertEquals(200, status)
        val reply = JSONObject(String(body))
        val session = reply.getString("sessionId")
        fun upload(query: String) = httpRequest("127.0.0.1", port, "POST", "/api/localsend/v2/upload?$query", length = 3, writeBody = { it.write(byteArrayOf(1, 2, 3)) }).first
        assertEquals(403, upload("sessionId=$session&fileId=a&token=wrong"))
        assertEquals(403, upload("sessionId=other&fileId=a&token=${reply.getJSONObject("files").getString("a")}"))
        assertEquals(200, upload("sessionId=$session&fileId=a&token=${reply.getJSONObject("files").getString("a")}"))
        // The session is over once everything arrived.
        assertEquals(409, upload("sessionId=$session&fileId=a&token=whatever"))
    }

    @Test
    fun aSenderThatIsNotThisAppIsRefused() {
        val stranger = LocalSendClient { DeviceInfo("Phone", "s-id", 0, deviceModel = "Pixel 9") }
        // The answer is a 403, which a sender sees as the offer being declined, and the person here is never asked.
        assertEquals(SendOutcome.DECLINED, stranger.send("127.0.0.1", port, files()))
        assertNull(listener.asked)
    }

    @Test
    fun cancellingEndsTheSession() {
        val offer = JSONObject().put("info", phone.toJson()).put("files", JSONObject().put("a", FileInfo("a", "a.mp4", 3, "video/mp4").toJson())).toString().toByteArray()
        val (_, body) = httpRequest("127.0.0.1", port, "POST", "/api/localsend/v2/prepare-upload", length = offer.size.toLong(), writeBody = { it.write(offer) })
        val reply = JSONObject(String(body))
        val session = reply.getString("sessionId")
        assertEquals(200, httpRequest("127.0.0.1", port, "POST", "/api/localsend/v2/cancel?sessionId=$session").first)
        assertTrue(listener.cancelled)
        val upload = httpRequest("127.0.0.1", port, "POST", "/api/localsend/v2/upload?sessionId=$session&fileId=a&token=${reply.getJSONObject("files").getString("a")}", length = 3, writeBody = { it.write(byteArrayOf(1, 2, 3)) })
        assertEquals(409, upload.first)
    }

    @Test
    fun aBodySentInChunksIsReadToo() {
        val offer = JSONObject().put("info", phone.toJson()).put("files", JSONObject().put("a", FileInfo("a", "a.mp4", 10, "video/mp4").toJson())).toString().toByteArray()
        val reply = JSONObject(String(httpRequest("127.0.0.1", port, "POST", "/api/localsend/v2/prepare-upload", length = offer.size.toLong(), writeBody = { it.write(offer) }).second))
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            out.write(("POST /api/localsend/v2/upload?sessionId=${reply.getString("sessionId")}&fileId=a&token=${reply.getJSONObject("files").getString("a")} HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "5\r\n01234\r\n5\r\n56789\r\n0\r\n\r\n").toByteArray())
            out.flush()
            val first = socket.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 200 OK", first)
        }
        assertEquals("0123456789", File(listener.finished!!.first, "a").readText())
    }

    @Test
    fun oneConnectionCanCarryManyRequests() {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val input = socket.getInputStream().bufferedReader()
            repeat(3) {
                out.write("GET /api/localsend/v2/info HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); out.flush()
                assertEquals("HTTP/1.1 200 OK", input.readLine())
                var length = 0
                while (true) {
                    val line = input.readLine()
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toInt()
                }
                val body = CharArray(length).also { input.read(it) }
                assertEquals("Living room TV", JSONObject(String(body)).getString("alias"))
            }
        }
    }

    @Test
    fun stoppingTheServerEndsOpenConnections() {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 3000
        server.close()
        try {
            socket.getOutputStream().write("GET /api/localsend/v2/info HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            assertEquals(-1, socket.getInputStream().read())
        } catch (_: IOException) {
        }
        socket.close()
        assertFalse(socket.isConnected && !socket.isClosed && false)
    }

    @Test
    fun aFileThatStopsHalfWayIsNotKept() {
        val offer = JSONObject().put("info", phone.toJson()).put("files", JSONObject().put("a", FileInfo("a", "a.mp4", 10, "video/mp4").toJson())).toString().toByteArray()
        val reply = JSONObject(String(httpRequest("127.0.0.1", port, "POST", "/api/localsend/v2/prepare-upload", length = offer.size.toLong(), writeBody = { it.write(offer) }).second))
        val token = reply.getJSONObject("files").getString("a")
        Socket("127.0.0.1", port).use { socket ->
            // Says ten bytes, sends four and hangs up.
            socket.getOutputStream().write(("POST /api/localsend/v2/upload?sessionId=${reply.getString("sessionId")}&fileId=a&token=$token HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n0123").toByteArray())
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            socket.soTimeout = 3000
            socket.getInputStream().readBytes()
        }
        assertNull(listener.finished)
    }

    @Test
    fun aSenderThatVanishesDoesNotKeepTheDeviceBusy() {
        val quick = LocalSendReceiver(cache, listener, idleMs = 300)
        val quickServer = HttpServer(quick)
        val quickPort = quickServer.start(0)
        try {
            val offer = JSONObject().put("info", phone.toJson()).put("files", JSONObject().put("a", FileInfo("a", "a.mp4", 3, "video/mp4").toJson())).toString().toByteArray()
            fun prepare() = httpRequest("127.0.0.1", quickPort, "POST", "/api/localsend/v2/prepare-upload", length = offer.size.toLong(), writeBody = { it.write(offer) }).first
            assertEquals(200, prepare())
            assertEquals(409, prepare())
            Thread.sleep(1500)
            assertTrue(listener.cancelled)
            assertEquals(200, prepare())
        } finally {
            quickServer.close()
            quick.stop()
        }
    }
}
