package app.gallerydl

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps what a device that cannot be connected to a computer would otherwise never tell: why the app closed. A crash in the
 * app's own code is written down as it happens; a crash in the system's video decoder, the system stopping the app for
 * memory, or a hang is read from Android's own record of how the last run ended, the next time the app starts.
 */
object CrashLog {
    fun file(context: Context): File = File(context.cacheDir, "diag").apply { mkdirs() }.let { File(it, "last-crash.txt") }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = buildString {
                    append("Crash in the app's own code, ").appendLine(now())
                    append("thread: ").appendLine(thread.name)
                    appendLine(error.stackTraceToString().take(6000))
                    append(memory(app))
                }
                file(app).writeText(text)
            }
            previous?.uncaughtException(thread, error)
        }
        recordLastExit(app)
    }

    /** How the previous run of the app ended, when Android says it was not a normal close (Android 11 and later). */
    private fun recordLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull { it.reason != ApplicationExitInfo.REASON_USER_REQUESTED && it.reason != ApplicationExitInfo.REASON_EXIT_SELF } ?: return
            val known = runCatching { file(context).readText() }.getOrDefault("")
            // The app's own crash was already written down with a stack trace; the system's note adds the rest.
            val text = buildString {
                append(known)
                if (known.isNotEmpty()) appendLine("\n----")
                append("Android says the last run ended: ").appendLine(reasonName(last.reason))
                append("when: ").appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(last.timestamp)))
                append("description: ").appendLine(last.description ?: "none")
                append("status: ").appendLine(last.status)
                append("memory at the end: pss ${last.pss / 1024} MB, rss ${last.rss / 1024} MB")
                appendLine()
                append("device: ").appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
            }
            file(context).writeText(text.take(8000))
        }
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (an exception in the app)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (a crash in native code, such as a video decoder)"
        ApplicationExitInfo.REASON_ANR -> "ANR (the app stopped answering)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (the system closed it to free memory)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (stopped by a signal, often a memory or watchdog kill)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "reason $reason"
    }

    fun memory(context: Context): String {
        val runtime = Runtime.getRuntime()
        val info = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        return "memory: java ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576}/${runtime.maxMemory() / 1048576} MB, " +
            "native ${android.os.Debug.getNativeHeapAllocatedSize() / 1048576} MB, device free ${info.availMem / 1048576} of ${info.totalMem / 1048576} MB, low=${info.lowMemory}\n"
    }

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
