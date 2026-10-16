package app.televault.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.televault.domain.ExportPartialOwnership
import org.json.JSONObject

/** Write to Downloads directly on Android 10+, or to the owner's legacy/custom SAF folder. */
internal class CloudExportStorage(private val app: Context) {
    companion object {
        const val DOWNLOADS = "downloads"
        const val TREE = "tree"
        const val DOWNLOAD_PATH = "Download/TG Photos/"
    }
    private val resolver get() = app.contentResolver
    fun validate(destination: Uri?) {
        if (destination == null) {
            check(Build.VERSION.SDK_INT >= 29) { "Choose your Downloads folder once on Android 8 or 9" }
        } else {
            check(DocumentFile.fromTreeUri(app, destination)?.canWrite() == true) { "Select a writable local folder" }
        }
    }
    private fun tree(data: JSONObject) = checkNotNull(DocumentFile.fromTreeUri(app, Uri.parse(data.getString("destination"))))
    fun automatic(data: JSONObject) = data.optString("storage", TREE) == DOWNLOADS
    fun createOrRecover(data: JSONObject, exportId: String): Uri {
        val name = data.getString("name")
        if (automatic(data)) {
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Automatic Downloads requires Android 10 or later")
            val uri = checkNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name.removeSuffix(".zip") + ".partial.zip")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_PATH)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            ) { "Could not create ZIP in Downloads" }
            val actualName = checkNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use {
                check(it.moveToFirst())
                it.getString(0)
            }
            trackCreated(data, exportId, uri, actualName)
            return uri
        }
        val folder = tree(data)
        val created = checkNotNull(folder.createFile("application/zip", name.removeSuffix(".zip") + ".partial.zip"))
        trackCreated(data, exportId, created.uri, checkNotNull(created.name))
        return created.uri
    }

    private fun trackCreated(data: JSONObject, exportId: String, uri: Uri, name: String) {
        data.put("partialOwner", exportId).put("partialUri", uri.toString()).put("partialName", name)
    }

    fun recover(data: JSONObject, uri: Uri): Uri {
        // A missing URI never grants permission to truncate a same-named replacement document.
        // Providers that change IDs on rename can leave a valid saved ZIP after process death;
        // preserve it and report the missing tracked destination instead of adopting another file.
        if (!automatic(data)) {
            check(DocumentFile.fromSingleUri(app, uri)?.exists() == true) { "Tracked export destination is missing; any saved ZIP was preserved" }
            return uri
        }
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Automatic Downloads requires Android 10 or later")
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { if (it.moveToFirst()) return uri }
        error("Tracked export is missing from Downloads; other files were preserved")
    }
    fun finish(data: JSONObject, uri: Uri): Uri {
        val name = data.getString("name")
        if (automatic(data)) {
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Automatic Downloads requires Android 10 or later")
            check(
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                ) == 1
            ) { "Could not publish verified ZIP to Downloads" }
            return uri
        }
        val file = checkNotNull(DocumentFile.fromSingleUri(app, uri))
        check(file.name == name || file.renameTo(name)) { "ZIP is verified but could not be renamed; retry to finish saving" }
        return file.uri
    }
    fun discardPending(data: JSONObject, exportId: String) {
        // This proof lives inside the existing authenticated export payload. Legacy/name-recovered
        // files without creation proof are preserved rather than claimed as app-owned.
        val uri = data.optString("createdUri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return
        fun owned(name: String?) = ExportPartialOwnership.mayDelete(exportId, data.optString("partialOwner"), uri.toString(), data.optString("partialUri"), name, data.optString("partialName"), data.optString("name"), data.optLong("completedAt") > 0)
        if (!owned(data.optString("partialName"))) return
        if (automatic(data)) {
            if (Build.VERSION.SDK_INT < 29) return
            checkNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use {
                if (it.moveToFirst() && it.getInt(0) == 1 && owned(it.getString(1))) check(resolver.delete(uri, null, null) == 1) { "Could not remove canceled export partial" }
            }
        } else {
            // Query directly so a revoked grant/provider failure is not mistaken for "missing".
            checkNotNull(resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)).use {
                if (it.moveToFirst() && owned(it.getString(0))) check(checkNotNull(DocumentFile.fromSingleUri(app, uri)).delete()) { "Could not remove canceled export partial" }
            }
        }
    }
}
