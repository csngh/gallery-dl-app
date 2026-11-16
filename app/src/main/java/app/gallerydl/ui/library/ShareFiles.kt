package app.gallerydl.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.content.FileProvider
import app.gallerydl.R
import app.gallerydl.data.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A file other apps may be given: a favourite lives inside the app, so it goes through the file provider. */
private fun shareableUri(context: Context, item: LibraryItem): Uri {
    val uri = Uri.parse(item.contentUri)
    return if (uri.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.files", File(requireNotNull(uri.path))) else uri
}

/** Opens the system share sheet for [items]; it also offers "Save to Files" and the other apps that take files. */
fun shareItems(context: Context, items: List<LibraryItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { shareableUri(context, it) })
    val types = items.map { it.mimeType }.distinct()
    val type = if (types.size == 1) types.first() else if (types.all { it.startsWith("video/") }) "video/*" else "*/*"
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}

/** Copies [items] into the folder the person picked (a tree address from the system picker). Returns how many were saved. */
suspend fun saveItemsToFolder(context: Context, tree: Uri, items: List<LibraryItem>): Int = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    var saved = 0
    for (item in items) {
        val target = runCatching { DocumentsContract.createDocument(resolver, parent, item.mimeType, item.displayName) }.getOrNull() ?: continue
        val ok = runCatching {
            resolver.openInputStream(Uri.parse(item.contentUri))!!.use { input ->
                resolver.openOutputStream(target)!!.use { out -> input.copyTo(out) }
            }
        }.isSuccess
        // A half-written copy is not left behind.
        if (ok) saved++ else runCatching { DocumentsContract.deleteDocument(resolver, target) }
    }
    saved
}

/** Copies one file to the new document the person named (from the system "create file" picker). */
suspend fun saveItemToDocument(context: Context, target: Uri, item: LibraryItem): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val ok = runCatching {
        resolver.openInputStream(Uri.parse(item.contentUri))!!.use { input ->
            resolver.openOutputStream(target)!!.use { out -> input.copyTo(out) }
        }
    }.isSuccess
    if (!ok) runCatching { DocumentsContract.deleteDocument(resolver, target) }
    ok
}

/** Shares one plain file kept in the app (the player monitor's log). */
fun shareFile(context: Context, file: File, mime: String) {
    if (!file.isFile) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}
