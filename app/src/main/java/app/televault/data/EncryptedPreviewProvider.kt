package app.televault.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.*
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import app.televault.TeleVaultApplication
import app.televault.crypto.AuthenticatedRecords
import kotlinx.coroutines.*

/** App-private seekable descriptor. Authenticated plaintext exists only in decoder/reader RAM. */
class EncryptedPreviewProvider : ContentProvider() {
    // Bounded independent lanes: one stalled descriptor does not stop all photos.
    private val threads by lazy { List(3) { HandlerThread("encrypted-preview-io-$it").apply { start() } } }
    private val nextLane = java.util.concurrent.atomic.AtomicInteger()
    override fun onCreate() = true
    private fun grant(uri: Uri): PrivateMediaGrants.Grant {
        val value = PrivateMediaGrants.get(checkNotNull(uri.lastPathSegment))
        check(drive().currentAccessEpoch() == value.epoch) { "Vault changed or locked" }
        return value
    }
    override fun getType(uri: Uri): String? = if (uri.pathSegments.firstOrNull() == "private-vault") {
        privateVault().let {
            it.validateUri(uri)
            it.item(checkNotNull(uri.lastPathSegment)).mime
        }
    } else if (uri.pathSegments.firstOrNull() == "share") {
        grant(uri).mime
    } else {
        drive().state.value.files.find { it.id == uri.lastPathSegment }?.mime
    }
    private fun privateVault() = (checkNotNull(context).applicationContext as TeleVaultApplication).privateVault
    private fun drive() = (checkNotNull(context).applicationContext as TeleVaultApplication).drive
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.pathSegments.firstOrNull() == "private-vault") return openVaultFile(uri, mode)
        require(mode == "r" && uri.pathSegments.size == 2 && uri.pathSegments[0] in setOf("media", "share"))
        val repository = drive()
        val value = if (uri.pathSegments[0] == "share") grant(uri) else null
        if (value?.source != null) return checkNotNull(context).contentResolver.openFileDescriptor(value.source, "r") ?: error("Source unavailable")
        val epoch = repository.currentAccessEpoch()
        val reader = try {
            runBlocking(Dispatchers.IO) { repository.authenticatedReader(value?.cloudId ?: checkNotNull(uri.lastPathSegment)) }
        } catch (failure: Exception) {
            if (AuthenticatedRecords.integrityFailure(failure)) repository.invalidateStreamCache(value?.cloudId ?: uri.lastPathSegment)
            throw java.io.FileNotFoundException("Authenticated media unavailable").apply { initCause(failure) }
        }
        try {
            val manager = checkNotNull(context).getSystemService(StorageManager::class.java)
            return manager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize() = reader.length()
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        if (repository.state.value.stage != VaultStage.READY || runCatching { repository.currentAccessEpoch() }.getOrNull() != epoch || value != null && value.expires <= SystemClock.elapsedRealtime()) {
                            data.fill(0)
                            throw ErrnoException("locked", OsConstants.EACCES)
                        }
                        return try {
                            if (offset >= reader.length()) return 0
                            val count = reader.read(offset, data, 0, minOf(size.toLong(), reader.length() - offset).toInt()).coerceAtLeast(0)
                            if (repository.currentAccessEpoch() != epoch || value != null && value.expires <= SystemClock.elapsedRealtime()) throw ErrnoException("locked", OsConstants.EACCES)
                            count
                        } catch (failure: Exception) {
                            data.fill(0)
                            if (AuthenticatedRecords.integrityFailure(failure)) repository.invalidateStreamCache(value?.cloudId ?: uri.lastPathSegment)
                            throw ErrnoException("authenticated read", OsConstants.EIO)
                        }
                    }
                    override fun onRelease() {
                        repository.closeReader(reader)
                    }
                },
                Handler(threads[Math.floorMod(nextLane.getAndIncrement(), threads.size)].looper)
            )
        } catch (e: Exception) {
            repository.closeReader(reader)
            throw e
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.pathSegments.firstOrNull() == "private-vault") {
            privateVault().validateUri(uri)
            val entry = privateVault().item(checkNotNull(uri.lastPathSegment))
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(entry.name, entry.size)) }
        }
        val value = if (uri.pathSegments.firstOrNull() == "share") grant(uri) else null
        val item = if (value == null) drive().streamingFile(checkNotNull(uri.lastPathSegment)).first else null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> value?.name ?: item!!.name
                        OpenableColumns.SIZE -> value?.size ?: item!!.size
                        "datetaken", "last_modified" -> item?.date ?: 0L
                        "date_modified" -> (item?.date ?: 0L) / 1000
                        else -> null
                    }
                }
            )
        }
    }
    private fun openVaultFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r" && uri.pathSegments.size == 2)
        val vault = privateVault()
        vault.validateUri(uri)
        val (reader, epoch) = vault.reader(checkNotNull(uri.lastPathSegment))
        try {
            return checkNotNull(context).getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long {
                        vault.validateUri(uri)
                        if (!vault.valid(epoch)) throw ErrnoException("locked", OsConstants.EACCES)
                        return reader.length()
                    }
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        try {
                            vault.validateUri(uri)
                            if (!vault.valid(epoch)) throw java.io.IOException("Locked")
                            val count = reader.read(offset, data, 0, minOf(size.toLong(), reader.length() - offset).coerceAtLeast(0).toInt()).coerceAtLeast(0)
                            vault.validateUri(uri)
                            if (!vault.valid(epoch)) throw java.io.IOException("Locked")
                            return count
                        } catch (_: Exception) {
                            data.fill(0)
                            throw ErrnoException("authenticated vault", OsConstants.EIO)
                        }
                    }
                    override fun onRelease() {
                        vault.close(reader)
                    }
                },
                Handler(threads[Math.floorMod(nextLane.getAndIncrement(), threads.size)].looper)
            )
        } catch (e: Exception) {
            vault.close(reader)
            throw e
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
