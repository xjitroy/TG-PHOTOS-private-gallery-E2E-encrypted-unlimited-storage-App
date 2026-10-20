package app.televault.data

import android.content.Context
import android.util.AtomicFile
import app.televault.domain.IntelligenceResultPolicy
import app.televault.telegram.RemoteDocument
import app.televault.telegram.TelegramGateway
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Content-addressed derived checkpoints. Every disk/network payload is authenticated ciphertext.
 * Unknown sensitivity is local-only. No source bytes, plaintext hash or recognition text in captions.
 * The current unlocked account/vault and durable Private Vault exclusions guard every publication.
 */
internal class IntelligenceResults(private val context: Context, private val drive: DriveRepository, private val telegram: TelegramGateway) {
    private data class Entry(val hash: String, val safe: Boolean, val ack: String)
    private val gate = Mutex()
    private val generation = AtomicLong()

    @Volatile private var binding: Pair<Long, String>? = null

    @Volatile private var entries: Map<String, Entry> = emptyMap()
    private val localOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var policyChanges = 0
    private var diskBytes = 0L
    private var remote: Map<String, List<RemoteDocument>>? = null
    private var remoteAttempted = false
    private var remoteUnavailable = false
    val syncPending: Boolean get() = remoteUnavailable
    private fun root(scope: Pair<Long, String>) = File(context.noBackupFilesDir, "analysis-results/${scope.first}/${scope.second}")
    private fun prefix(scope: Pair<Long, String>) = "TGDRIVE_V1_ANALYSIS:${scope.second}:"
    private fun guard(scope: Pair<Long, String>, token: Long) {
        check(binding == scope && generation.get() == token && drive.privateVaultBinding() == scope)
    }
    private fun read(scope: Pair<Long, String>, key: String): JSONObject? {
        val target = AtomicFile(File(root(scope), "$key.bin"))
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        check(target.baseFile.length() <= if (key == "index") 12L * 1024 * 1024 else MAX_BYTES.toLong())
        val cipher = target.openRead().use { it.readBytes() }
        val bytes = try {
            drive.ocrEnvelope(cipher, "analysis-v1/$key", scope, true)
        } finally {
            cipher.fill(0)
        }
        return try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }
    private fun write(scope: Pair<Long, String>, token: Long, key: String, body: JSONObject) {
        guard(scope, token)
        val bytes = body.toString().toByteArray()
        check(bytes.size <= if (key == "index") 12 * 1024 * 1024 else MAX_BYTES - 28)
        val cipher = try {
            drive.ocrEnvelope(bytes, "analysis-v1/$key", scope, false)
        } finally {
            bytes.fill(0)
        }
        try {
            val target = AtomicFile(File(root(scope), "$key.bin"))
            target.baseFile.parentFile!!.mkdirs()
            val oldBytes = target.baseFile.length()
            check(diskBytes - oldBytes + cipher.size < 256L * 1024 * 1024) { "Encrypted analysis cache reached its storage budget" }
            val output = target.startWrite()
            try {
                output.write(cipher)
                guard(scope, token)
                target.finishWrite(output)
                diskBytes += cipher.size - oldBytes
            } catch (failure: Throwable) {
                target.failWrite(output)
                throw failure
            }
        } finally {
            cipher.fill(0)
        }
    }
    private fun saveIndex(scope: Pair<Long, String>, token: Long, value: Map<String, Entry>) {
        check(value.size <= 50000)
        // Small durable deltas prevent rewriting the whole library index for every photo.
        val changed = (entries.keys + value.keys).filter { entries[it] != value[it] }
        for (key in changed) {
            val row = value[key]
            val body = JSONObject().put("key", key).put("removed", row == null)
            if (row != null) body.put("hash", row.hash).put("safe", row.safe).put("ack", row.ack)
            write(scope, token, "policy-$key", body)
        }
        guard(scope, token)
        entries = value
        policyChanges += changed.size
        if (policyChanges >= 64) {
            val body = JSONObject().put("version", 1).put("rows", JSONArray(value.map { (key, row) -> JSONArray().put(key).put(row.hash).put(row.safe).put(row.ack) }))
            write(scope, token, "index", body)
            guard(scope, token)
            root(scope).listFiles().orEmpty().filter { it.name.startsWith("policy-") && it.extension == "bin" }.forEach { AtomicFile(it).delete() }
            policyChanges = 0
            diskBytes = root(scope).listFiles().orEmpty().sumOf { it.length() }
        }
    }
    suspend fun activate() = withContext(Dispatchers.IO) {
        gate.withLock {
            val scope = drive.privateVaultBinding()
            if (binding == scope) return@withLock
            val token = generation.get()
            // Keep the store unpublished if decryption fails; never replace unreadable ciphertext.
            val rows = read(scope, "index")?.also { check(it.getInt("version") == 1) }?.getJSONArray("rows") ?: JSONArray()
            check(rows.length() <= 50000)
            val restored = linkedMapOf<String, Entry>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                val key = row.getString(0)
                val hash = row.getString(1)
                check(hash.length in 1..128 && key == drive.analysisObjectId(hash, scope))
                restored[key] = Entry(hash, row.getBoolean(2), row.getString(3).also { check(it.length <= 64) })
            }
            val deltas = root(scope).listFiles().orEmpty().map { it.name.removeSuffix(".bak") }.filter { it.startsWith("policy-") && it.endsWith(".bin") }.distinct()
            check(deltas.size <= 50000)
            for (name in deltas) {
                val key = name.removePrefix("policy-").removeSuffix(".bin")
                check(key.matches(Regex("[0-9a-f]{64}")))
                val row = checkNotNull(read(scope, "policy-$key"))
                check(row.getString("key") == key)
                if (row.getBoolean("removed")) {
                    restored.remove(key)
                } else {
                    val hash = row.getString("hash")
                    check(hash.length in 1..128 && key == drive.analysisObjectId(hash, scope))
                    restored[key] = Entry(hash, row.getBoolean("safe"), row.getString("ack").also { check(it.length <= 64) })
                }
            }
            check(restored.size <= 50000)
            policyChanges = deltas.size
            diskBytes = root(scope).listFiles().orEmpty().sumOf { it.length() }
            check(generation.get() == token && drive.privateVaultBinding() == scope)
            localOnly.clear()
            localOnly.addAll(restored.values.filter { !it.safe }.map { it.hash })
            entries = restored
            remote = null
            remoteAttempted = false
            remoteUnavailable = false
            binding = scope
        }
    }

    /** Safe synchronous filter for People serialization. Unknown/unloaded results stay local. */
    fun classified(hash: String): Boolean {
        val scope = binding ?: return false
        if (hash.isBlank() || drive.privateVaultBinding() != scope) return false
        return drive.analysisObjectId(hash, scope) in entries
    }
    fun cloudAllowed(hash: String): Boolean {
        val scope = binding ?: return false
        if (hash.isBlank() || hash in localOnly || drive.privateVaultBinding() != scope || drive.privateAi.excluded("", hash)) return false
        return entries[drive.analysisObjectId(hash, scope)]?.safe == true
    }
    suspend fun beginBatch() {
        activate()
        gate.withLock {
            remote = null
            remoteAttempted = false
            remoteUnavailable = false
        }
    }
    private suspend fun catalogue(scope: Pair<Long, String>, token: Long): Map<String, List<RemoteDocument>>? {
        if (remoteAttempted) return remote
        remoteAttempted = true
        return try {
            val all = withTimeout(30_000) { telegram.findDocuments(prefix(scope)) }
            guard(scope, token)
            check(all.size <= 50000)
            all.filter { ref ->
                val key = ref.marker.removePrefix(prefix(scope))
                IntelligenceResultPolicy.ownedCaption(ref.marker, scope.second, key) && ref.bytes in 28..MAX_BYTES.toLong()
            }.groupBy { it.marker.removePrefix(prefix(scope)) }.also { remote = it }
        } catch (cancel: TimeoutCancellationException) {
            remoteUnavailable = true
            null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            remoteUnavailable = true
            null
        }
    }
    private fun valid(body: JSONObject, hash: String, scope: Pair<Long, String>) {
        check(body.getInt("version") == 1 && body.getLong("account") == scope.first && body.getString("vault") == scope.second && body.getString("hash") == hash)
        body.optJSONObject("text")?.let {
            check(it.getInt("engineRevision") == 3)
            app.televault.ocr.OcrText.read(it)
        }
        body.optJSONObject("scene")?.let { check(it.getString("model") == "mobilenetv2-c1c51358-v1" && it.getString("labels").length <= 1024) }
        body.optJSONObject("face")?.let { row ->
            check(row.getString("model") == app.televault.people.PeopleStore.MODEL && row.getInt("count") in 0..256)
            val people = row.getJSONArray("people")
            check(people.length() <= 256)
            for (i in 0 until people.length()) check(app.televault.domain.ForgottenVaultPolicy.validId(people.getString(i)))
        }
    }
    private fun safe(body: JSONObject): Boolean = body.optJSONObject("text") != null && !IntelligenceResultPolicy.localOnly(body.getJSONObject("text").optString("text"), body.optJSONObject("scene")?.optString("labels").orEmpty(), body.optBoolean("video", true))
    private fun digest(body: JSONObject): String = MessageDigest.getInstance("SHA-256").digest(body.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    private suspend fun authenticated(ref: RemoteDocument, key: String, hash: String, scope: Pair<Long, String>, token: Long): JSONObject {
        check(IntelligenceResultPolicy.ownedCaption(ref.marker, scope.second, key) && ref.bytes in 28..MAX_BYTES.toLong())
        val cipher = ByteArray(ref.bytes.toInt())
        try {
            var offset = 0
            while (offset < cipher.size) {
                guard(scope, token)
                val part = telegram.readDocumentPart(ref, offset.toLong(), minOf(262144, cipher.size - offset))
                try {
                    check(part.isNotEmpty() && part.size <= cipher.size - offset)
                    part.copyInto(cipher, offset)
                    offset += part.size
                } finally {
                    part.fill(0)
                }
            }
            val bytes = drive.ocrEnvelope(cipher, "analysis-v1/$key", scope, true)
            return try {
                JSONObject(bytes.toString(Charsets.UTF_8)).also {
                    valid(it, hash, scope)
                    check(safe(it))
                    guard(scope, token)
                }
            } finally {
                bytes.fill(0)
            }
        } finally {
            cipher.fill(0)
        }
    }
    suspend fun restore(hash: String): JSONObject? = withContext(Dispatchers.IO) {
        if (hash.isBlank()) return@withContext null
        activate()
        gate.withLock {
            val scope = checkNotNull(binding)
            val token = generation.get()
            guard(scope, token)
            if (drive.privateAi.excluded("", hash)) return@withLock null
            val key = drive.analysisObjectId(hash, scope)
            val local = read(scope, key)
            if (local != null) {
                valid(local, hash, scope)
                return@withLock local
            }
            val ref = catalogue(scope, token)?.get(key)?.maxByOrNull { it.messageId } ?: return@withLock null
            try {
                val body = withTimeout(15_000) { authenticated(ref, key, hash, scope, token) }
                if (drive.privateAi.excluded("", hash)) return@withLock null
                write(scope, token, key, body)
                saveIndex(scope, token, entries + (key to Entry(hash, true, digest(body))))
                body
            } catch (cancel: TimeoutCancellationException) {
                remoteUnavailable = true
                null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                remoteUnavailable = true
                null
            }
        }
    }
    suspend fun checkpoint(hash: String, video: Boolean, text: JSONObject?, face: JSONObject?, scene: JSONObject?): Boolean = withContext(Dispatchers.IO) {
        if (hash.isBlank()) return@withContext false
        activate()
        gate.withLock {
            val scope = checkNotNull(binding)
            val token = generation.get()
            guard(scope, token)
            if (drive.privateAi.excluded("", hash)) return@withLock false
            val key = drive.analysisObjectId(hash, scope)
            val previous = read(scope, key)
            val body = JSONObject().put("version", 1).put("account", scope.first).put("vault", scope.second).put("hash", hash).put("video", video)
            // Null means disabled/not analysed, not an empty successful inference.
            if (text != null) body.put("text", text)
            if (face != null) body.put("face", face)
            if (scene != null) body.put("scene", scene)
            valid(body, hash, scope)
            val safe = safe(body)
            val before = entries[key]
            val row = Entry(hash, safe, before?.ack.orEmpty())
            // A restrictive verdict commits before new payload bytes, and blocks synchronously
            // even if storage fails. A permissive verdict publishes only after durable ciphertext.
            if (!safe) {
                localOnly += hash
                if (before != row) saveIndex(scope, token, entries + (key to row))
            }
            if (previous?.toString() != body.toString()) write(scope, token, key, body)
            if (safe) {
                if (before != row) saveIndex(scope, token, entries + (key to row))
                localOnly -= hash
            }
            before?.safe != safe
        }
    }
    suspend fun sync(hash: String) = withContext(Dispatchers.IO) {
        if (hash.isBlank()) return@withContext
        activate()
        gate.withLock {
            val scope = checkNotNull(binding)
            val token = generation.get()
            guard(scope, token)
            val key = drive.analysisObjectId(hash, scope)
            val row = entries[key] ?: return@withLock
            val body = read(scope, key) ?: return@withLock
            valid(body, hash, scope)
            val all = catalogue(scope, token) ?: return@withLock
            if (!row.safe || !safe(body) || drive.privateAi.excluded("", hash)) {
                val refs = all[key].orEmpty()
                if (refs.isNotEmpty()) {
                    telegram.deleteAnalysisDocuments(refs, scope.second, key)
                    guard(scope, token)
                    remote = all - key
                }
                if (row.ack.isNotEmpty()) saveIndex(scope, token, entries + (key to row.copy(ack = "")))
                return@withLock
            }
            val digest = digest(body)
            if (row.ack == digest && !all[key].isNullOrEmpty()) return@withLock
            val bytes = body.toString().toByteArray()
            val cipher = try {
                drive.ocrEnvelope(bytes, "analysis-v1/$key", scope, false)
            } finally {
                bytes.fill(0)
            }
            val staging = File(context.cacheDir, "analysis-${UUID.randomUUID()}.bin")
            try {
                staging.writeBytes(cipher)
                guard(scope, token)
                check(!drive.privateAi.excluded("", hash) && cloudAllowed(hash))
                val sent = telegram.sendDocument(staging, prefix(scope) + key) { _, _ -> }
                authenticated(sent.copy(marker = prefix(scope) + key), key, hash, scope, token)
                guard(scope, token)
                if (drive.privateAi.excluded("", hash)) {
                    telegram.deleteAnalysisDocuments(listOf(sent.copy(marker = prefix(scope) + key)), scope.second, key)
                    return@withLock
                }
                remote = all + (key to (all[key].orEmpty() + sent.copy(marker = prefix(scope) + key)))
                saveIndex(scope, token, entries + (key to row.copy(ack = digest)))
                val old = all[key].orEmpty().filter { it.messageId != sent.messageId }
                if (old.isNotEmpty()) {
                    telegram.deleteAnalysisDocuments(old, scope.second, key)
                    remote = remote.orEmpty() + (key to listOf(sent.copy(marker = prefix(scope) + key)))
                }
            } finally {
                cipher.fill(0)
                staging.delete()
            }
        }
    }

    /** Called before the Private Vault ledger can finish cleanup, including after reinstall. */
    suspend fun cleanPrivateSources() = withContext(Dispatchers.IO) {
        activate()
        gate.withLock {
            val scope = checkNotNull(binding)
            val token = generation.get()
            val hashes = drive.privateAi.blockedHashes() + entries.values.filter { drive.privateAi.excluded("", it.hash) }.map { it.hash }
            if (hashes.isEmpty()) return@withLock
            // Force a fresh authoritative catalogue; a prior offline attempt cannot complete cleanup.
            remoteAttempted = false
            val all = checkNotNull(catalogue(scope, token)) { "Encrypted analysis cleanup is pending" }
            var updated = entries
            for (hash in hashes) {
                guard(scope, token)
                val key = drive.analysisObjectId(hash, scope)
                val refs = all[key].orEmpty()
                if (refs.isNotEmpty()) telegram.deleteAnalysisDocuments(refs, scope.second, key)
                guard(scope, token)
                val local = AtomicFile(File(root(scope), "$key.bin"))
                val oldBytes = local.baseFile.length()
                local.delete()
                diskBytes = (diskBytes - oldBytes).coerceAtLeast(0)
                updated = updated - key
            }
            saveIndex(scope, token, updated)
            remote = all - hashes.map { drive.analysisObjectId(it, scope) }.toSet()
        }
    }
    fun lock() {
        generation.incrementAndGet()
        binding = null
        entries = emptyMap()
        localOnly.clear()
        remote = null
        remoteAttempted = false
        remoteUnavailable = false
    }
    fun retire(owner: Long, vault: String) {
        if (binding == (owner to vault)) lock()
        File(context.noBackupFilesDir, "analysis-results/$owner/$vault").deleteRecursively()
    }
    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
    }
}
