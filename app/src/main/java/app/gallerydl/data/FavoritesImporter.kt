package app.gallerydl.data

import app.gallerydl.download.PythonBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs the import of a favorites sheet in the background, in the app's own scope: it carries on when the user leaves the
 * screen that started it, shows its progress on Library and Home, and can be stopped at any time.
 */
class FavoritesImporter(
    private val library: LibraryRepository,
    private val bridge: PythonBridge,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data class Running(val done: Int, val total: Int, val stopping: Boolean = false, val step: FavoriteStep? = null) : State
        /** What the import did; [stopped] is true when the user ended it early. */
        data class Finished(val result: FavoritesImportResult, val stopped: Boolean) : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()
    private var job: Job? = null

    /** [whenEnded] runs once the import is over, with true when every row was handled and false when it was stopped or failed. */
    fun start(rows: List<FavoriteRow>, whenEnded: suspend (completed: Boolean) -> Unit = {}) {
        if (_state.value is State.Running || rows.isEmpty()) return
        _state.value = State.Running(0, rows.size)
        job = scope.launch {
            var latest = FavoritesImportResult()
            try {
                val options = settings.options.first().toJson(settings.cookiesPath())
                val resolve: suspend (String, List<String>) -> Map<String, String> = { parent, names ->
                    withContext(Dispatchers.IO) {
                        // The look-up of a link is the heaviest part: it runs behind whatever the person is watching.
                        val tid = android.os.Process.myTid()
                        val normal = android.os.Process.getThreadPriority(tid)
                        android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        try { bridge.resolveFiles(parent, names, options) } finally { android.os.Process.setThreadPriority(tid, normal) }
                    }
                }
                var done = 0
                val result = library.importFavoriteVideos(rows, resolve, onProgress = { n, so ->
                    latest = so
                    done = n
                    (_state.value as? State.Running)?.let { _state.value = it.copy(done = n, step = null) }
                }, onStep = { step ->
                    (_state.value as? State.Running)?.let { _state.value = it.copy(done = done, step = step) }
                })
                _state.value = State.Finished(result, stopped = false)
                whenEnded(true)
            } catch (e: CancellationException) {
                _state.value = State.Finished(latest, stopped = true)
                withContext(NonCancellable) { runCatching { whenEnded(false) } }
                throw e
            } catch (_: Exception) {
                _state.value = State.Failed
                runCatching { whenEnded(false) }
            }
        }
    }

    /** Ends the import after the video being fetched right now; what was already added stays. */
    fun cancel() {
        val running = _state.value as? State.Running ?: return
        _state.value = running.copy(stopping = true)
        job?.cancel()
        // A walk through a big link would otherwise keep going until it ended on its own.
        scope.launch(Dispatchers.IO) { runCatching { bridge.cancelResolve() } }
    }

    /** Hides the result once it has been read. */
    fun dismiss() {
        if (_state.value !is State.Running) _state.value = null
    }
}
