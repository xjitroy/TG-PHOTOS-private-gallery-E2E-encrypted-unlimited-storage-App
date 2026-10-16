package app.televault.data

import android.content.Context
import android.util.Base64
import app.televault.crypto.AuthenticatedRecords
import app.televault.crypto.VaultCrypto
import app.televault.telegram.RemoteDocument
import app.televault.telegram.TelegramGateway
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal data class PrivateVaultCloudObject(val outerKey: ByteArray, val parts: List<RemoteDocument>, val manifest: RemoteDocument) {
    override fun toString() = "PrivateVaultCloudObject(redacted)"
}

/** No Photos catalog records, plaintext files, thumbnails or retained media ranges. */
internal class PrivateVaultCloudStore(private val context: Context, private val drive: DriveRepository, private val telegram: TelegramGateway) {
    private val staging = File(context.noBackupFilesDir, "private-vault-upload-v2").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }
    private var rootReady: Triple<Long, String, String>? = null
    fun binding() = drive.privateVaultBinding()
    private fun rootMarker(binding: Pair<Long, String>) = "TGDRIVE_V1_PRIVATE_ROOT:${binding.second}"
    private fun manifestMarker(binding: Pair<Long, String>, vault: UUID) = "TGDRIVE_V1_FILE:PV2:${binding.second}:$vault"
    private fun partMarker(binding: Pair<Long, String>, vault: UUID, id: String, index: Int) = "TGDRIVE_V1_DATA:PV2:${binding.second}:$vault:$id:$index"
    private fun aad(vault: UUID, id: String) = "TGPhotos/private-vault/cloud/v2/$vault/$id".toByteArray()
    private fun b64(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    private suspend fun checkBinding(expected: Pair<Long, String>, valid: () -> Unit) {
        valid()
        check(binding() == expected && telegram.accountId() == expected.first) { "Telegram account or Photos vault changed" }
    }
    private suspend fun whole(reference: RemoteDocument, marker: String, access: () -> Unit = {}): ByteArray {
        check(reference.bytes in 1..1_048_576)
        return telegram.readPrivateVaultPart(reference, 0, reference.bytes.toInt(), marker, access)
    }
    suspend fun publishRoot(descriptor: JSONObject) {
        val binding = binding()
        check(telegram.accountId() == binding.first)
        val plain = descriptor.toString().toByteArray()
        val sealed = try {
            drive.privateVaultEnvelope(plain, "root", binding, false)
        } finally {
            plain.fill(0)
        }
        val file = File(staging, "${UUID.randomUUID()}.bin")
        try {
            file.writeBytes(sealed)
            val reference = telegram.sendDocument(file, rootMarker(binding)) { _, _ -> }
            check(file.delete()) { "Could not retire encrypted upload staging" }
            val server = whole(reference, rootMarker(binding)) { check(binding() == binding) }
            try {
                check(java.security.MessageDigest.isEqual(server, sealed))
            } finally {
                server.fill(0)
            }
            check(binding() == binding)
            rootReady = Triple(binding.first, binding.second, descriptor.getString("id"))
        } finally {
            sealed.fill(0)
            file.delete()
        }
    }
    suspend fun ensureRoot(descriptor: JSONObject) {
        val binding = binding()
        val identity = Triple(binding.first, binding.second, descriptor.getString("id"))
        if (rootReady == identity) return
        val existing = discoverRoot()
        if (existing == null) {
            publishRoot(descriptor)
        } else {
            check(existing.getString("id") == descriptor.getString("id") && existing.getString("wrapped") == descriptor.getString("wrapped")) { "Cloud Vault differs; both copies preserved" }
            rootReady = identity
        }
    }
    suspend fun discoverRoot(): JSONObject? {
        val binding = binding()
        check(telegram.accountId() == binding.first)
        val roots = telegram.findDocuments(rootMarker(binding)).sortedByDescending { it.messageId }
        if (roots.isEmpty()) return null
        // Never fall back to an older password envelope after a damaged newest root.
        val sealed = whole(roots.first(), rootMarker(binding))
        val plain = try {
            drive.privateVaultEnvelope(sealed, "root", binding, true)
        } finally {
            sealed.fill(0)
        }
        return try {
            JSONObject(plain.toString(Charsets.UTF_8)).also {
                check(it.getLong("cloudAccount") == binding.first && it.getString("cloudVault") == binding.second)
                UUID.fromString(it.getString("id"))
            }
        } finally {
            plain.fill(0)
        }
    }
    fun sealMetadata(data: JSONObject, key: ByteArray, vault: UUID, id: String, binding: Pair<Long, String>): ByteArray {
        val bytes = data.toString().toByteArray()
        val normal = try {
            drive.privateVaultEnvelope(bytes, "$vault/$id", binding, false)
        } finally {
            bytes.fill(0)
        }
        return try {
            VaultCrypto.seal(key, normal, aad(vault, id))
        } finally {
            normal.fill(0)
        }
    }
    fun openMetadata(sealed: ByteArray, key: ByteArray, vault: UUID, id: String, binding: Pair<Long, String>): JSONObject {
        val normal = VaultCrypto.open(key, sealed, aad(vault, id))
        val bytes = try {
            drive.privateVaultEnvelope(normal, "$vault/$id", binding, true)
        } finally {
            normal.fill(0)
        }
        return try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }
    private fun reference(json: JSONObject) = RemoteDocument(json.getLong("chat"), json.getLong("message"), json.getLong("bytes"))
    private fun json(ref: RemoteDocument) = JSONObject().put("chat", ref.chatId).put("message", ref.messageId).put("bytes", ref.bytes)
    fun item(id: String, data: JSONObject): PrivateVaultItem {
        val parts = data.getJSONArray("parts")
        val refs = (0 until parts.length()).map { reference(parts.getJSONObject(it)) }
        check(refs.isNotEmpty() && refs.size <= 2048 && refs.all { it.bytes > 0 })
        val size = data.getLong("size")
        check(size in 0..64L * 1024 * 1024 * 1024)
        check(refs.sumOf { it.bytes } == VaultCrypto.encryptedLength(VaultCrypto.encryptedLength(size)))
        val inner = decode(data.getString("fileKey"))
        val outer = decode(data.getString("outerKey"))
        val hash = decode(data.getString("hash"))
        check(inner.size == 32 && outer.size == 32 && hash.size == 32)
        return PrivateVaultItem(id, data.getString("name").take(512), data.getString("mime").take(128), size, data.getLong("date"), hash, inner, PrivateVaultCloudObject(outer, refs, reference(data.getJSONObject("manifest"))))
    }
    suspend fun discoverItems(key: ByteArray, vault: UUID, expected: Pair<Long, String>, valid: () -> Unit): List<Pair<String, ByteArray>> {
        checkBinding(expected, valid)
        val result = mutableListOf<Pair<String, ByteArray>>()
        val references = telegram.findDocuments(manifestMarker(expected, vault))
        check(references.size <= 10_000)
        for (ref in references) {
            checkBinding(expected, valid)
            val envelope = whole(ref, manifestMarker(expected, vault)) {
                valid()
                check(binding() == expected)
            }
            val json = try {
                JSONObject(envelope.toString(Charsets.UTF_8))
            } finally {
                envelope.fill(0)
            }
            val id = UUID.fromString(json.getString("id")).toString()
            val sealed = decode(json.getString("sealed"))
            val data = try {
                openMetadata(sealed, key, vault, id, expected)
            } finally {
                sealed.fill(0)
            }
            check(data.getString("id") == id)
            data.put("manifest", json(ref))
            val validated = item(id, data)
            validated.wipe()
            result.add(id to sealMetadata(data, key, vault, id, expected))
        }
        checkBinding(expected, valid)
        check(result.map { it.first }.toSet().size == result.size) { "Duplicate Private Vault identities" }
        return result
    }
    suspend fun upload(inner: File, name: String, mime: String, size: Long, hash: ByteArray, innerKey: ByteArray, key: ByteArray, vault: UUID, objectId: String, expected: Pair<Long, String>, facts: MediaFacts, originalDate: Long, valid: () -> Unit, phase: (VaultImportStage) -> Unit, progress: (Long, Long) -> Unit): ByteArray {
        checkBinding(expected, valid)
        val outerKey = VaultCrypto.randomKey()
        val outer = File(staging, "${UUID.randomUUID()}.bin")
        val refs = mutableListOf<RemoteDocument>()
        var manifest: RemoteDocument? = null
        var committed = false
        try {
            phase(VaultImportStage.ENCRYPTION)
            progress(0, inner.length())
            inner.inputStream().use { input ->
                outer.outputStream().use { output ->
                    VaultCrypto.encrypt(input, output, outerKey, vault, UUID.fromString(objectId), inner.length()) {
                        valid()
                        progress(it, inner.length())
                    }
                }
            }
            val limit = minOf(telegram.uploadLimit(), 512L * 1024 * 1024)
            check(limit >= 1_048_576)
            var uploaded = 0L
            phase(VaultImportStage.UPLOAD)
            progress(0, outer.length())
            outer.inputStream().use { input ->
                while (uploaded < outer.length()) {
                    checkBinding(expected, valid)
                    val part = File(staging, "${UUID.randomUUID()}.bin")
                    val partBytes = minOf(limit, outer.length() - uploaded)
                    try {
                        part.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var remaining = partBytes
                            while (remaining > 0) {
                                currentCoroutineContext().ensureActive()
                                valid()
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                check(n > 0)
                                output.write(buffer, 0, n)
                                remaining -= n
                            }
                        }
                        refs.add(telegram.sendDocument(part, partMarker(expected, vault, objectId, refs.size)) { done, _ -> progress(uploaded + done, outer.length()) })
                        uploaded += partBytes
                    } finally {
                        part.delete()
                    }
                }
            }
            val data = JSONObject().put("id", objectId).put("name", name).put("mime", mime).put("size", size).put("date", originalDate).put("facts", facts.json()).put("hash", b64(hash)).put("fileKey", b64(innerKey)).put("outerKey", b64(outerKey)).put("parts", JSONArray(refs.map { json(it) }))
            // Independently read/authenticate the complete server copy before committing metadata or deleting any source.
            phase(VaultImportStage.SERVER_VERIFICATION)
            progress(0, size)
            val provisional = PrivateVaultItem(objectId, name, mime, size, data.getLong("date"), hash, innerKey, PrivateVaultCloudObject(outerKey, refs, RemoteDocument(0, 0, 0)))
            val (reader, close) = reader(provisional, vault, expected, valid)
            try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                try {
                    var offset = 0L
                    while (offset < size) {
                        currentCoroutineContext().ensureActive()
                        valid()
                        val n = reader.read(offset, buffer, 0, minOf(buffer.size.toLong(), size - offset).toInt())
                        check(n > 0)
                        digest.update(buffer, 0, n)
                        offset += n
                        progress(offset, size)
                    }
                    check(java.security.MessageDigest.isEqual(digest.digest(), hash))
                } finally {
                    buffer.fill(0)
                }
            } finally {
                close.close()
            }
            checkBinding(expected, valid)
            phase(VaultImportStage.COMMIT)
            progress(0, 1)
            val sealed = sealMetadata(data, key, vault, objectId, expected)
            val envelope = JSONObject().put("version", 2).put("id", objectId).put("sealed", b64(sealed)).toString().toByteArray()
            val file = File(staging, "${UUID.randomUUID()}.bin")
            try {
                file.writeBytes(envelope)
                manifest = telegram.sendDocument(file, manifestMarker(expected, vault)) { _, _ -> }
                check(file.delete()) { "Could not retire encrypted upload staging" }
                val server = whole(checkNotNull(manifest), manifestMarker(expected, vault)) {
                    valid()
                    check(binding() == expected)
                }
                try {
                    check(java.security.MessageDigest.isEqual(server, envelope))
                } finally {
                    server.fill(0)
                }
            } finally {
                sealed.fill(0)
                envelope.fill(0)
                file.delete()
            }
            data.put("manifest", json(checkNotNull(manifest)))
            val local = sealMetadata(data, key, vault, objectId, expected)
            committed = true
            progress(1, 1)
            return local
        } finally {
            outerKey.fill(0)
            outer.delete()
            if (!committed && refs.isNotEmpty()) {
                withContext(NonCancellable) {
                    runCatching { withTimeout(30_000) { telegram.deleteOwnedDocuments(refs + listOfNotNull(manifest), ":PV2:${expected.second}:$vault") } }
                }
            }
        }
    }
    fun reader(item: PrivateVaultItem, vault: UUID, expected: Pair<Long, String>, valid: () -> Unit): Pair<AuthenticatedRecords, AutoCloseable> {
        val cloud = checkNotNull(item.cloud)
        val cipherLength = VaultCrypto.encryptedLength(VaultCrypto.encryptedLength(item.size))
        val window = app.televault.crypto.TransientCipherWindow({ position, output, offset, length ->
            var cursor = position
            var copied = 0
            while (copied < length) {
                valid()
                check(binding() == expected)
                var start = 0L
                val index = cloud.parts.indexOfFirst { ref ->
                    if (cursor < start + ref.bytes) {
                        true
                    } else {
                        start += ref.bytes
                        false
                    }
                }
                check(index >= 0)
                val part = cloud.parts[index]
                val n = minOf((length - copied).toLong(), part.bytes - (cursor - start)).toInt()
                val bytes = runBlocking(Dispatchers.IO) {
                    checkBinding(expected, valid)
                    telegram.readPrivateVaultPart(part, cursor - start, n, partMarker(expected, vault, item.id, index)) {
                        valid()
                        check(binding() == expected)
                    }
                }
                try {
                    valid()
                    check(bytes.size == n)
                    bytes.copyInto(output, offset + copied)
                } finally {
                    bytes.fill(0)
                }
                cursor += n
                copied += n
            }
        }, cipherLength)
        try {
            val reader = app.televault.crypto.NestedAuthenticatedRecords(window, item.fileKey, cloud.outerKey, UUID.fromString(expected.second), vault, UUID.fromString(item.id), item.size, cipherLength)
            return reader.reader() to AutoCloseable {
                reader.close()
                window.close()
            }
        } catch (error: Throwable) {
            window.close()
            throw error
        }
    }

    suspend fun delete(item: PrivateVaultItem, vault: UUID, expected: Pair<Long, String>, valid: () -> Unit) {
        checkBinding(expected, valid)
        val cloud = checkNotNull(item.cloud)
        telegram.deleteOwnedDocuments(cloud.parts + cloud.manifest, ":PV2:${expected.second}:$vault")
        checkBinding(expected, valid)
    }
    suspend fun deleteAll(expected: Pair<Long, String>, vault: UUID, valid: () -> Unit) {
        checkBinding(expected, valid)
        // Include orphaned upload parts as well as manifests, including copies added by another device.
        val parts = telegram.findDocuments("TGDRIVE_V1_DATA:PV2:${expected.second}:$vault:")
        val manifests = telegram.findDocuments(manifestMarker(expected, vault))
        for (batch in (parts + manifests).chunked(100)) {
            checkBinding(expected, valid)
            telegram.deleteOwnedDocuments(batch, ":PV2:${expected.second}:$vault")
        }
        // A dedicated gateway checks exact marker, bytes and Saved Messages ownership.
        val roots = telegram.findDocuments(rootMarker(expected))
        telegram.deletePrivateVaultRoots(roots, rootMarker(expected))
        checkBinding(expected, valid)
    }
}
