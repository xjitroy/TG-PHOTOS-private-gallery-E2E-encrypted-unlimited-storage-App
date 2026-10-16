package app.televault.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import app.televault.DriveViewModel

/** Grants stream original bytes; cloud shares remain encrypted at rest in our private provider. */
internal fun shareEntries(context: Context, model: DriveViewModel, entries: List<MediaEntry>) {
    require(entries.isNotEmpty())
    require(entries.none { it.local?.uri?.scheme == "tgphotos-device-trash" }) { "Restore device-file Trash items before sharing." }
    require(entries.size <= 100) { "Share up to 100 files at a time." }
    val types = entries.map { it.mime.ifBlank { "application/octet-stream" } }.distinct()
    val mime = if (types.size == 1) types.single() else types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val uris = ArrayList(entries.map { model.shareMedia(it.local?.uri, it.cloud?.id, originalMediaName(it.name), it.mime, it.size) })
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = mime
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri("Selected files", uris.first()).also { data -> uris.drop(1).forEach { data.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share selected files"))
}
