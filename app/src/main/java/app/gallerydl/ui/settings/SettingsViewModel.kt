package app.gallerydl.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gallerydl.data.DownloadDestination
import app.gallerydl.data.DownloadRepository
import app.gallerydl.data.SettingsRepository
import app.gallerydl.download.GalleryDlOptions
import app.gallerydl.download.PythonBridge
import app.gallerydl.download.PluginSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the app and its parts are, for the About section. */
data class AboutInfo(val appVersion: String, val galleryDlVersion: String)

enum class CookieImport { INVALID }

internal data class PluginSettingsState(
    val snapshot: PluginSnapshot? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val downloads: DownloadRepository,
    private val bridge: PythonBridge,
    context: Context,
    /** Already-loaded settings from the app container, so this screen never starts empty. */
    val options: StateFlow<GalleryDlOptions?>,
    val destination: StateFlow<DownloadDestination?>,
    val cookiesCount: StateFlow<Int>,
    val startupSound: StateFlow<Boolean>,
    val livePreviews: StateFlow<Boolean>,
    val previewSpeed: StateFlow<Float>,
    private val thumbnails: app.gallerydl.data.ThumbnailCache,
    private val previewStrips: app.gallerydl.data.PreviewStripCache,
) : ViewModel() {

    private val _cookieError = MutableStateFlow<CookieImport?>(null)
    val cookieError: StateFlow<CookieImport?> = _cookieError.asStateFlow()

    private val _thumbnailBytes = MutableStateFlow<Long?>(null)
    val thumbnailBytes: StateFlow<Long?> = _thumbnailBytes.asStateFlow()

    /** How many videos have no preview because it could not be made. */
    private val _failedPreviews = MutableStateFlow(0)
    val failedPreviews: StateFlow<Int> = _failedPreviews.asStateFlow()

    private val _recordsBytes = MutableStateFlow<Long?>(null)
    val recordsBytes: StateFlow<Long?> = _recordsBytes.asStateFlow()

    private val _about = MutableStateFlow<AboutInfo?>(null)
    val about: StateFlow<AboutInfo?> = _about.asStateFlow()
    private val _plugins = MutableStateFlow(PluginSettingsState())
    internal val plugins = _plugins.asStateFlow()

    internal fun updatePlugins(action: String, id: String? = null, enabled: Boolean? = null, uri: Uri? = null) {
        if (_plugins.value.busy) return
        _plugins.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { bridge.plugins(action, id, enabled, uri) }
                _plugins.value = PluginSettingsState(snapshot = snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _plugins.update { it.copy(busy = false, error = failure.localizedMessage ?: "Could not update extractor plugins.") }
            }
        }
    }

    init {
        refreshRecords()
        viewModelScope.launch {
            val appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
            val galleryDl = withContext(Dispatchers.Default) { runCatching { bridge.galleryDlVersion() }.getOrDefault("") }
            _about.value = AboutInfo(appVersion, galleryDl)
        }
    }

    private fun refreshRecords() {
        viewModelScope.launch {
            _recordsBytes.value = settings.recordsBytes()
            _thumbnailBytes.value = withContext(Dispatchers.IO) { thumbnails.sizeBytes() + previewStrips.sizeBytes() }
            _failedPreviews.value = previewStrips.failedCount()
        }
    }

    /** Forgets the videos whose preview could not be made; [onDone] runs once they are free to be tried again. */
    fun retryFailedPreviews(onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { previewStrips.retryFailed() }
        refreshRecords()
        onDone()
    }

    /** Forces every thumbnail to be made again the next time the library needs it. */
    fun clearThumbnails() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            thumbnails.clear()
            previewStrips.clear()
        }
        refreshRecords()
    }

    fun setStartupSound(on: Boolean) = viewModelScope.launch { settings.setStartupSound(on) }
    fun setDarkAppearance(dark: Boolean) = viewModelScope.launch { settings.setDarkAppearance(dark) }
    fun setLivePreviews(on: Boolean) = viewModelScope.launch { settings.setLivePreviews(on) }
    fun setPreviewSpeed(speed: Float) = viewModelScope.launch { settings.setPreviewSpeed(speed) }
    fun setApiRest(on: Boolean) = viewModelScope.launch { settings.setApiRest(on) }
    fun setSleepRequestEnabled(on: Boolean) = viewModelScope.launch { settings.setSleepRequestEnabled(on) }
    fun setSleepRequest(value: String) = viewModelScope.launch { settings.setSleepRequest(value) }
    fun setSleepEnabled(on: Boolean) = viewModelScope.launch { settings.setSleepEnabled(on) }
    fun setSleep(value: String) = viewModelScope.launch { settings.setSleep(value) }
    fun setRetriesEnabled(on: Boolean) = viewModelScope.launch { settings.setRetriesEnabled(on) }
    fun setRetries(value: String) = viewModelScope.launch { settings.setRetries(value) }
    fun setTimeoutEnabled(on: Boolean) = viewModelScope.launch { settings.setTimeoutEnabled(on) }
    fun setTimeout(value: String) = viewModelScope.launch { settings.setTimeout(value) }

    fun setWifiOnly(on: Boolean) = viewModelScope.launch {
        settings.setWifiOnly(on)
        downloads.applyNetworkSetting()
    }

    fun setDestination(treeUri: Uri) = viewModelScope.launch { settings.setDestination(treeUri) }
    fun resetDestination() = viewModelScope.launch { settings.resetToDefault() }

    fun setCookiesEnabled(on: Boolean) = viewModelScope.launch { settings.setCookiesEnabled(on) }
    fun importCookies(uri: Uri) = viewModelScope.launch {
        _cookieError.value = if (settings.importCookies(uri) == null) CookieImport.INVALID else null
    }
    fun removeCookies() = viewModelScope.launch {
        settings.removeCookies()
        _cookieError.value = null
    }
    fun dismissCookieError() = _cookieError.update { null }

    fun clearFinishedHistory() = viewModelScope.launch {
        downloads.clearFinishedHistory()
        refreshRecords()
    }

    fun resetAllSettings() = viewModelScope.launch {
        settings.resetAll()
        downloads.applyNetworkSetting()
    }
}
