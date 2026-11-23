package app.gallerydl.download

internal data class ExtractorPlugin(
    val id: String,
    val name: String,
    val version: String,
    val domains: List<String>,
    val enabled: Boolean,
    val active: Boolean,
    val error: String?,
)

internal data class PluginSnapshot(val plugins: List<ExtractorPlugin>, val restartRequired: Boolean)
