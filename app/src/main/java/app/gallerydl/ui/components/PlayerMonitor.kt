package app.gallerydl.ui.components

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

/**
 * A readout for finding out why the player's picture or controls move sideways on some screens: it counts how often the
 * page, the picture and the control bar change place, how long frames take, what keys and pointer events arrive, and what
 * the player itself reports. Switched on from the viewer's menu; it also saves what it shows to a file that can be shared.
 */
object PlayerMonitor {
    private var prefs: SharedPreferences? = null
    var enabled by mutableStateOf(false)
        private set

    fun load(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("player_monitor", Context.MODE_PRIVATE).also { enabled = it.getBoolean("on", false) }
    }

    fun set(context: Context, on: Boolean) {
        load(context)
        enabled = on
        prefs?.edit()?.putBoolean("on", on)?.apply()
    }

    fun logFile(context: Context): File = File(context.cacheDir, "diag").apply { mkdirs() }.let { File(it, "player-monitor.txt") }
}

/** Says where the viewer's pager is (page, offset, scrolling); provided by the screen that hosts the player. */
val LocalPagerInfo = compositionLocalOf<() -> String> { { "pager: not reported" } }

/** What the monitor has seen; written to from the player's callbacks, read by the overlay. */
class MonitorProbe {
    private class Sample(val at: Long, val x: Float, val y: Float)

    private val places = HashMap<String, ArrayDeque<Sample>>()
    private val keys = ArrayDeque<String>()
    private val pointers = ArrayDeque<Long>()
    private var lastPointer = "none yet"
    private val frames = ArrayDeque<Float>()
    private var lastFrame = 0L

    @Synchronized fun place(name: String, x: Float, y: Float) {
        val list = places.getOrPut(name) { ArrayDeque() }
        list.addLast(Sample(SystemClock.elapsedRealtime(), x, y))
        trim(list)
    }

    @Synchronized fun key(text: String) {
        keys.addLast(text)
        while (keys.size > 6) keys.removeFirst()
    }

    @Synchronized fun pointer(kind: String, x: Float, y: Float) {
        pointers.addLast(SystemClock.elapsedRealtime())
        while (pointers.isNotEmpty() && SystemClock.elapsedRealtime() - pointers.first() > 2000) pointers.removeFirst()
        lastPointer = "$kind at ${x.toInt()},${y.toInt()}"
    }

    @Synchronized fun frame(nanos: Long) {
        val now = nanos / 1_000_000
        if (lastFrame != 0L) {
            frames.addLast((now - lastFrame).toFloat())
            while (frames.size > 120) frames.removeFirst()
        }
        lastFrame = now
    }

    private fun trim(list: ArrayDeque<Sample>) {
        val now = SystemClock.elapsedRealtime()
        while (list.isNotEmpty() && now - list.first().at > 2000) list.removeFirst()
    }

    /** The lines to show: how often each thing moved in the last two seconds and by how much. */
    @Synchronized fun lines(): List<String> {
        val out = ArrayList<String>()
        for ((name, list) in places.entries.sortedBy { it.key }) {
            trim(list)
            if (list.isEmpty()) { out += "$name: still"; continue }
            val xs = list.map { it.x }
            val ys = list.map { it.y }
            out += "$name: ${list.size} moves/2s  x ${xs.min().toInt()}..${xs.max().toInt()}  y ${ys.min().toInt()}..${ys.max().toInt()}"
        }
        if (frames.isNotEmpty()) {
            val avg = frames.average()
            val worst = frames.max()
            val slow = frames.count { it > 25f }
            out += "frames: avg ${"%.1f".format(avg)} ms, worst ${worst.toInt()} ms, ${slow} slow of ${frames.size}"
        }
        out += "pointer: ${pointers.size} events/2s, last $lastPointer"
        out += "keys: " + if (keys.isEmpty()) "none" else keys.joinToString("  ")
        return out
    }
}

/** The readout drawn over the player. [extra] adds what only the player knows, as it is read each time. */
@Composable
fun PlayerMonitorOverlay(probe: MonitorProbe, header: List<String>, extra: () -> List<String>, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    // The effect below runs on; these keep it reading the latest values instead of the first ones.
    val headerNow by rememberUpdatedState(header)
    val extraNow by rememberUpdatedState(extra)
    DisposableEffect(probe) {
        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                probe.frame(frameTimeNanos)
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose { choreographer.removeFrameCallback(callback) }
    }
    LaunchedEffect(probe) {
        val file = PlayerMonitor.logFile(context)
        runCatching { file.writeText("") }
        val started = SystemClock.elapsedRealtime()
        var written = 0
        while (true) {
            val body = headerNow + probe.lines() + extraNow()
            text = body.joinToString("\n")
            // One block a second goes to the file, for a minute, so it can be shared afterwards.
            if (written < 60) {
                runCatching { file.appendText("--- +${(SystemClock.elapsedRealtime() - started) / 1000}s\n" + text + "\n") }
                written++
            }
            delay(1000)
        }
    }
    Box(modifier.background(Color.Black.copy(alpha = 0.72f)).padding(6.dp)) {
        Text(text, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
