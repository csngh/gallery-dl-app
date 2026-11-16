package app.gallerydl.sync

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A copy of this app found on the network. */
data class FoundDevice(val info: DeviceInfo, val host: String, val seen: Long)

/**
 * Finds the other copies of this app by itself, the way LocalSend does: it announces itself on the multicast group and listens
 * for the others, and as a second way (for networks that block multicast) asks every address of the local network whether
 * a copy answers. Both are repeated while the Sync screen is open.
 */
class SyncDiscovery(private val context: Context, private val me: () -> DeviceInfo, private val client: LocalSendClient) {
    private val _found = MutableStateFlow<Map<String, FoundDevice>>(emptyMap())
    val found: StateFlow<Map<String, FoundDevice>> = _found.asStateFlow()

    private val pool = Executors.newFixedThreadPool(48) { runnable -> Thread(runnable, "scan").apply { isDaemon = true } }
    private var scheduler: ScheduledExecutorService? = null
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val scanning = AtomicBoolean(false)
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "sync-schedule").apply { isDaemon = true } }
        this.scheduler = scheduler
        listen()
        scheduler.scheduleWithFixedDelay({ runCatching { round() } }, 0, 8, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        scheduler?.shutdownNow()
        scheduler = null
        runCatching { socket?.close() }
        socket = null
        runCatching { lock?.release() }
        lock = null
        _found.value = emptyMap()
    }

    /** Looks again, now. */
    fun scanNow() {
        pool.execute { runCatching { round() } }
    }

    private fun round() {
        announce()
        scan()
        val cutoff = System.currentTimeMillis() - 70_000
        _found.update { map -> map.filterValues { it.seen >= cutoff } }
    }

    /** Notes a copy of the app that told us (or answered) that it is there. */
    fun note(info: DeviceInfo, host: String) {
        if (!info.isGalleryDl || info.fingerprint == me().fingerprint || info.fingerprint.isEmpty()) return
        _found.update { it + (info.fingerprint to FoundDevice(info, host, System.currentTimeMillis())) }
    }

    // region Multicast

    private fun listen() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        lock = wifi?.createMulticastLock("gallerydl-sync")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val group = InetAddress.getByName(LOCALSEND_GROUP)
        val multicast = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(LOCALSEND_PORT))
                joinGroup(group)
            }
        } catch (_: Exception) {
            // Some networks and devices do not allow it; the search through the addresses carries on regardless.
            return
        }
        socket = multicast
        pool.execute {
            val buffer = ByteArray(8192)
            while (running && !multicast.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { multicast.receive(packet) } catch (_: Exception) { break }
                runCatching {
                    val json = JSONObject(String(packet.data, 0, packet.length))
                    val info = DeviceInfo.from(json)
                    val host = packet.address.hostAddress?.substringBefore('%').orEmpty()
                    if (info.isGalleryDl && info.fingerprint != me().fingerprint && host.isNotEmpty()) {
                        note(info, host)
                        // The one who announced is told about this device in return, over HTTP.
                        if (json.optBoolean("announce")) pool.execute { client.register(host, info.port)?.let { note(it, host) } }
                    }
                }
            }
        }
    }

    private fun announce() {
        val multicast = socket ?: return
        runCatching {
            val bytes = me().toJson().put("announce", true).toString().toByteArray()
            multicast.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(LOCALSEND_GROUP), LOCALSEND_PORT))
        }
    }

    // endregion

    // region Searching the addresses

    /** The addresses of this device's own networks, as the host part of each: up to 254 around its own. */
    private fun neighbours(): List<String> {
        val hosts = LinkedHashSet<String>()
        val interfaces = try { java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        for (network in interfaces) {
            // Not the phone's mobile data, a tunnel or a loopback: only networks other devices can be on.
            val name = network.name.lowercase()
            if (!network.isUp || network.isLoopback || network.isPointToPoint || name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("dummy")) continue
            for (own in network.interfaceAddresses) {
                val address = own.address as? Inet4Address ?: continue
                val prefix = own.networkPrefixLength.toInt().coerceIn(24, 30)
                val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                val base = ip and (-1 shl (32 - prefix))
                val size = (1 shl (32 - prefix)) - 1
                for (offset in 1 until size) {
                    val value = base + offset
                    if (value != ip) hosts += "${(value shr 24) and 255}.${(value shr 16) and 255}.${(value shr 8) and 255}.${value and 255}"
                }
            }
        }
        return hosts.toList()
    }

    private fun scan() {
        if (!scanning.compareAndSet(false, true)) return
        val hosts = neighbours()
        val remaining = java.util.concurrent.atomic.AtomicInteger(hosts.size)
        if (hosts.isEmpty()) scanning.set(false)
        for (host in hosts) {
            pool.execute {
                try {
                    if (running) client.register(host)?.let { note(it, host) }
                } finally {
                    if (remaining.decrementAndGet() == 0) scanning.set(false)
                }
            }
        }
    }

    // endregion
}
