package app.televault.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import app.televault.crypto.VaultCrypto
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class DeviceFileTrashItem(val id: String, val local: LocalMedia, val trashedAt: Long, val origin: String)

/** Recoverable non-MediaStore device files, local-only. Metadata and file bytes are encrypted. */
internal class LocalFileTrash(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "device-file-trash").apply { mkdirs() }
    private val lock = Mutex()
    private val mutable = MutableStateFlow<List<DeviceFileTrashItem>>(emptyList())
    val items = mutable.asStateFlow()
    private val currentActivity = MutableStateFlow<WorkProgress?>(null)
    val activity = currentActivity.asStateFlow()
    private var progressAt = 0L
    private var keyHandle: SecretKey? = null
    private fun progress(name: String, completed: Long, total: Long) {
        (context.applicationContext as app.televault.TeleVaultApplication).protection.requireAllowed()
        val now = android.os.SystemClock.elapsedRealtime()
        if (completed == 0L || completed == total || now - progressAt >= 100) {
            progressAt = now
            currentActivity.value = WorkProgress(name, completed, total)
        }
    }
    private val namespace = UUID.fromString("d726bdea-ab11-4134-8c67-a4d951d821cd")
    private fun key(create: Boolean): SecretKey {
        (context.applicationContext as app.televault.TeleVaultApplication).protection.requireAllowed()
        check(!(context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceLocked) { "Unlock your phone first." }
        keyHandle?.let { return it } // Keystore handle only; never exported key bytes.
        val alias = "tgphotos.device-file-trash.v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return (store.getKey(alias, null) as SecretKey).also { keyHandle = it }
        check(create) { "Device Trash key unavailable. Originals were not changed." }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        if (android.os.Build.VERSION.SDK_INT >= 28) spec.setUnlockedDeviceRequired(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey().also { keyHandle = it }
    }
    private fun file(id: String, extension: String): File = File(directory, "${UUID.fromString(id)}.$extension")
    private fun seal(id: String, metadata: JSONObject) {
        val bytes = metadata.toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD("TGPhotos/device-file-trash/$id".toByteArray())
        val encrypted = try {
            cipher.iv + cipher.doFinal(bytes)
        } finally {
            bytes.fill(0)
        }
        val atomic = AtomicFile(file(id, "meta"))
        val sink = atomic.startWrite()
        try {
            sink.write(encrypted)
            atomic.finishWrite(sink)
        } catch (e: Exception) {
            atomic.failWrite(sink)
            throw e
        } finally {
            encrypted.fill(0)
        }
    }
    private fun read(id: String): JSONObject {
        check(file(id, "meta").length() in 28..65536)
        val envelope = AtomicFile(file(id, "meta")).openRead().use { it.readBytes().also { bytes -> check(bytes.size in 28..65536) } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, envelope.copyOfRange(0, 12)))
        cipher.updateAAD("TGPhotos/device-file-trash/$id".toByteArray())
        val plain = cipher.doFinal(envelope, 12, envelope.size - 12)
        return try {
            JSONObject(plain.toString(Charsets.UTF_8))
        } finally {
            plain.fill(0)
            envelope.fill(0)
        }
    }
    private fun refreshLocked() {
        mutable.value = directory.listFiles().orEmpty().filter { it.extension == "meta" }.mapNotNull { entry ->
            runCatching {
                val id = UUID.fromString(entry.nameWithoutExtension).toString()
                val data = read(id)
                DeviceFileTrashItem(id, LocalMedia(Uri.parse("tgphotos-device-trash:$id"), data.getString("name"), data.getString("mime"), data.getLong("size"), data.getLong("date"), data.getLong("modified"), data.getString("folder"), trashed = true), data.getLong("trashedAt"), data.optString("origin", "library"))
            }.getOrNull()
        }.sortedByDescending { it.trashedAt }
    }
    suspend fun refresh() = withContext(Dispatchers.IO) { lock.withLock { refreshLocked() } }
    private suspend fun hash(uri: Uri, changed: (Long) -> Unit = {}): ByteArray {
        var count = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val bytes = ByteArray(65536)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(bytes)
                    if (n < 0) break
                    digest.update(bytes, 0, n)
                    count += n
                    changed(count)
                }
            } finally {
                bytes.fill(0)
            }
        }
        return digest.digest()
    }
    private fun removeOriginal(uri: Uri): Boolean {
        if (uri.scheme == "file") {
            check(DeviceFileAccess.granted(context))
            val path = File(uri.path!!).canonicalPath
            check(path.startsWith("/storage/") && !path.contains("/Android/data/") && !path.contains("/Android/obb/"))
            return File(path).delete()
        }
        check(uri.scheme == "content" && android.provider.DocumentsContract.isDocumentUri(context, uri)) { "Use your device file manager for this provider." }
        return android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
    }
    suspend fun trash(files: List<LocalMedia>, origin: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(directory.listFiles().orEmpty().count { it.extension == "meta" } + files.size <= 10000) { "Device-file Trash is full. Restore or permanently empty older items first; originals kept." }
            require(files.all { it.size in 0..(64L * 1024 * 1024 * 1024) }) { "Unsupported size; originals kept." }
            val total = files.sumOf { it.size.coerceAtLeast(1) } * 3
            var offset = 0L
            progress("Moving device files to Trash", 0, total)
            try {
                for (source in files) {
                    ensureActive()
                    require(!source.trashed && source.size >= 0)
                    check(directory.usableSpace > VaultCrypto.encryptedLength(source.size) + 16L * 1024 * 1024) { "Not enough local space for recoverable Trash. Original kept." }
                    val id = UUID.randomUUID().toString()
                    val contentKey = VaultCrypto.randomKey()
                    var metadataSaved = false
                    try {
                        val hash = context.contentResolver.openInputStream(source.uri)!!.use { input ->
                            FileOutputStream(file(id, "bin")).use { output ->
                                VaultCrypto.encrypt(input, output, contentKey, namespace, UUID.fromString(id), source.size) { done ->
                                    ensureActive()
                                    progress("Moving device files to Trash", offset + done, total)
                                }.also { output.fd.sync() }
                            }
                        }
                        // Authenticate/read back every record before ever touching the shared original.
                        file(id, "bin").inputStream().use { input ->
                            VaultCrypto.decrypt(
                                input,
                                object : java.io.OutputStream() {
                                    override fun write(value: Int) {} override fun write(bytes: ByteArray, offset: Int, count: Int) {}
                                },
                                contentKey,
                                namespace,
                                UUID.fromString(id),
                                source.size,
                                hash
                            ) { done ->
                                ensureActive()
                                progress("Moving device files to Trash", offset + source.size + done, total)
                            }
                        }
                        val data = JSONObject().put("name", source.name).put("mime", source.mime).put("size", source.size).put("date", source.date).put("modified", source.modified).put("folder", source.folder).put("trashedAt", System.currentTimeMillis()).put("origin", origin).put("source", source.uri.toString()).put("hash", Base64.encodeToString(hash, Base64.NO_WRAP)).put("key", Base64.encodeToString(contentKey, Base64.NO_WRAP))
                        seal(id, data)
                        metadataSaved = true
                        ensureActive()
                        val deletionUri = source.physicalPath?.let { Uri.fromFile(File(it)) } ?: source.uri
                        check(MessageDigest.isEqual(hash, hash(deletionUri) { done -> progress("Moving device files to Trash", offset + source.size * 2 + done, total) })) { "Source changed; original kept. Recoverable Trash copy retained." }
                        check(removeOriginal(deletionUri)) { "Provider refused deletion. Original and recoverable Trash copy retained." }
                        source.physicalPath?.let { android.media.MediaScannerConnection.scanFile(context, arrayOf(it), null, null) }
                        offset += source.size.coerceAtLeast(1) * 3
                        progress("Moving device files to Trash", offset, total)
                    } finally {
                        contentKey.fill(0)
                        if (!metadataSaved) file(id, "bin").delete()
                    }
                }
            } finally {
                currentActivity.value = null
                refreshLocked()
            }
        }
    }
    suspend fun restore(ids: Set<String>, tree: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            val parent = checkNotNull(DocumentFile.fromTreeUri(context, tree))
            check(parent.canWrite())
            val total = ids.sumOf { read(it).getLong("size").coerceAtLeast(1) } * 2
            var restored = 0L
            progress("Restoring device files", 0, total)
            try {
                for (id in ids) {
                    ensureActive()
                    val data = read(id)
                    val key = Base64.decode(data.getString("key"), Base64.NO_WRAP)
                    var output: DocumentFile? = null
                    var safeNewOutput = false
                    try {
                        val original = data.getString("name").replace(Regex("[\\\\/\\u0000-\\u001f]"), "_").take(180)
                        val name = "Restored_${UUID.randomUUID().toString().take(8)}_$original"
                        output = checkNotNull(parent.createFile(data.getString("mime"), name))
                        check(DeviceFileAccess.identity(output!!.uri) != DeviceFileAccess.identity(Uri.parse(data.getString("source")))) { "Refusing original overwrite." }
                        check(output!!.length() == 0L) { "Provider did not return a new empty document." }
                        safeNewOutput = true
                        val expected = Base64.decode(data.getString("hash"), Base64.NO_WRAP)
                        file(id, "bin").inputStream().use { input ->
                            context.contentResolver.openOutputStream(output!!.uri, "w")!!.use { sink ->
                                VaultCrypto.decrypt(input, sink, key, namespace, UUID.fromString(id), data.getLong("size"), expected) { done ->
                                    ensureActive()
                                    progress("Restoring device files", restored + done, total)
                                }
                            }
                        }
                        check(MessageDigest.isEqual(expected, hash(output!!.uri) { done -> progress("Restoring device files", restored + data.getLong("size") + done, total) }))
                        restored += data.getLong("size").coerceAtLeast(1) * 2
                        progress("Restoring device files", restored, total)
                        safeNewOutput = false // Verified restored output must survive any later cleanup failure.
                        check(file(id, "bin").delete()) { "Restored output verified; could not release Trash copy." }
                        AtomicFile(file(id, "meta")).delete()
                    } catch (failure: Throwable) {
                        if (safeNewOutput) runCatching { output?.delete() }
                        throw failure
                    } finally {
                        key.fill(0)
                    }
                }
            } finally {
                currentActivity.value = null
                refreshLocked()
            }
        }
    }

    /** Called only after the existing password/phone verification and cooldown gate. */
    suspend fun purge(ids: Set<String>, cooldown: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                for (id in ids) {
                    val data = read(id)
                    check(app.televault.domain.TrashCooldown.allowed(data.getLong("trashedAt"), System.currentTimeMillis(), cooldown))
                    check(!file(id, "bin").exists() || file(id, "bin").delete())
                    AtomicFile(file(id, "meta")).delete()
                }
            } finally {
                currentActivity.value = null
                refreshLocked()
            }
        }
    }
}
