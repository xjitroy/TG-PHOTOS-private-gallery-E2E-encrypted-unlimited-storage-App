package app.televault.data

import android.content.Context
import android.util.AtomicFile
import app.televault.domain.ForgottenVaultPolicy
import app.televault.domain.IntelligencePrivacy
import app.televault.telegram.RemoteDocument
import app.televault.telegram.TelegramGateway
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private data class PrivacyOperation(val id: String, val ids: Set<String>, val hashes: Set<String>) {
    fun json() = JSONObject().put("id", id).put("ids", JSONArray(ids.sorted())).put("hashes", JSONArray(hashes.sorted()))
}
internal data class PrivateAiScope(val owner: Long = 0, val vault: String = "", val ids: Set<String> = emptySet(), val hashes: Set<String> = emptySet(), val loaded: Boolean = false)

/** Durable, encrypted ADD-only exclusions; no photo/text/name/face template is stored here. */
internal class PrivateIntelligenceExclusions(private val context: Context, private val drive: DriveRepository, private val telegram: TelegramGateway) {
    private val mutex = Mutex()
    private val publication = Any()
    private val generation = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var sessionToken = -1L
    private val installation = context.getSharedPreferences("intelligence-device-v1", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("installation", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("installation", it).commit()) }
    }
    fun identity(id: String): String = IntelligencePrivacy.identity(id, installation)
    fun localId(id: String): String = id.removePrefix("local/$installation/")
    fun hasExclusions(): Boolean = current.ids.isNotEmpty() || current.hashes.isNotEmpty()

    @Volatile private var current = PrivateAiScope()

    @Volatile private var cleanupPending = false
    fun needsCleanup(): Boolean = cleanupPending
    private val seen = mutableSetOf<String>()
    private val pending = linkedMapOf<String, PrivacyOperation>()
    fun excluded(id: String, hash: String = ""): Boolean {
        val scope = drive.privateVaultBinding()
        val snapshot = current
        check(snapshot.loaded && snapshot.owner == scope.first && snapshot.vault == scope.second) { "Private media exclusions are not ready" }
        return IntelligencePrivacy.excluded(identity(id), hash, snapshot.ids, snapshot.hashes)
    }
    private fun file(scope: Pair<Long, String>) = AtomicFile(File(context.noBackupFilesDir, "intelligence-private/${scope.first}/${scope.second}/ledger.bin"))
    private fun access(scope: Pair<Long, String>) {
        check(sessionToken == generation.get() && drive.privateVaultBinding() == scope && current.loaded && current.owner == scope.first && current.vault == scope.second)
    }
    private fun strings(body: JSONObject, key: String, bound: Int, length: Int): Set<String> {
        val rows = body.getJSONArray(key)
        check(rows.length() <= bound)
        return (0 until rows.length()).mapTo(linkedSetOf()) { rows.getString(it).also { value -> check(value.length in 1..length) } }
    }
    private fun operation(body: JSONObject): PrivacyOperation {
        val id = body.getString("id")
        check(ForgottenVaultPolicy.validId(id))
        return PrivacyOperation(id, strings(body, "ids", 256, 4096), strings(body, "hashes", 256, 128))
    }
    private fun merge(scope: Pair<Long, String>, op: PrivacyOperation) {
        access(scope)
        synchronized(publication) {
            check(sessionToken == generation.get())
            val ids = current.ids + op.ids
            val hashes = current.hashes + op.hashes
            check(ids.size <= 50000 && hashes.size <= 50000)
            current = PrivateAiScope(scope.first, scope.second, ids, hashes, true)
            cleanupPending = true
        }
    }
    private fun save(scope: Pair<Long, String>) {
        access(scope)
        val body = JSONObject().put("version", 1).put("ids", JSONArray(current.ids.sorted())).put("hashes", JSONArray(current.hashes.sorted())).put("seen", JSONArray(seen.sorted())).put("cleanupPending", cleanupPending).put("pending", JSONArray(pending.values.map { it.json() })).toString().toByteArray()
        try {
            check(body.size <= 12 * 1024 * 1024)
            val cipher = drive.intelligencePrivacyEnvelope(body, "local", scope, false)
            try {
                val target = file(scope)
                target.baseFile.parentFile!!.mkdirs()
                val output = target.startWrite()
                try {
                    output.write(cipher)
                    access(scope)
                    target.finishWrite(output)
                } catch (error: Throwable) {
                    target.failWrite(output)
                    throw error
                }
            } finally {
                cipher.fill(0)
            }
        } finally {
            body.fill(0)
        }
    }
    private fun activateLocked(scope: Pair<Long, String>) {
        if (current.loaded && current.owner == scope.first && current.vault == scope.second && sessionToken == generation.get())return
        val expected = synchronized(publication) {
            generation.get().also {
                sessionToken = it
                current = PrivateAiScope(scope.first, scope.second)
            }
        }
        seen.clear()
        pending.clear()
        cleanupPending = false
        var ids = emptySet<String>()
        var hashes = emptySet<String>()
        val target = file(scope)
        if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) {
            check(target.baseFile.length() <= 13 * 1024 * 1024)
            val cipher = target.openRead().use { it.readBytes() }
            val bytes = try {
                drive.intelligencePrivacyEnvelope(cipher, "local", scope, true)
            } finally {
                cipher.fill(0)
            }
            try {
                val body = JSONObject(bytes.toString(Charsets.UTF_8))
                check(body.getInt("version") == 1)
                ids = strings(body, "ids", 50000, 4096)
                hashes = strings(body, "hashes", 50000, 128)
                cleanupPending = body.optBoolean("cleanupPending")
                seen += strings(body, "seen", 50000, 36)
                val outbox = body.getJSONArray("pending")
                check(outbox.length() <= 50000)
                for (i in 0 until outbox.length()) {
                    val op = operation(outbox.getJSONObject(i))
                    pending[op.id] = op
                    ids = ids + op.ids
                    hashes = hashes + op.hashes
                }
            } finally {
                bytes.fill(0)
            }
        }
        check(drive.privateVaultBinding() == scope)
        synchronized(publication) {
            check(generation.get() == expected && sessionToken == expected)
            current = PrivateAiScope(scope.first, scope.second, ids, hashes, true)
        }
        access(scope)
    }
    suspend fun activate() = withContext(Dispatchers.IO) { mutex.withLock { activateLocked(drive.privateVaultBinding()) } }
    suspend fun block(ids: Set<String>, hashes: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            activateLocked(scope)
            val added = ids.map(::identity).toSet() - current.ids
            val hashDelta = hashes - current.hashes
            check(hashDelta.size <= 256)
            val batches = added.toList().chunked(256).ifEmpty { listOf(emptyList()) }
            for ((index, batch) in batches.withIndex()) {
                val values = if (index == 0)hashDelta else emptySet()
                if (batch.isEmpty() && values.isEmpty())continue
                val op = PrivacyOperation(UUID.randomUUID().toString(), batch.toSet(), values)
                merge(scope, op)
                pending[op.id] = op
            }
            save(scope)
        }
    }
    private suspend fun read(ref: RemoteDocument, scope: Pair<Long, String>, id: String): PrivacyOperation {
        check(ref.bytes in 1..2L * 1024 * 1024)
        val bytes = ByteArray(ref.bytes.toInt())
        var offset = 0
        try {
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                access(scope)
                val part = telegram.readDocumentPart(ref, offset.toLong(), minOf(262144, bytes.size - offset))
                try {
                    check(part.isNotEmpty() && part.size <= bytes.size - offset)
                    part.copyInto(bytes, offset)
                    offset += part.size
                } finally {
                    part.fill(0)
                }
            }
            val plain = drive.intelligencePrivacyEnvelope(bytes, id, scope, true)
            return try {
                JSONObject(plain.toString(Charsets.UTF_8)).let { body ->
                    check(body.getLong("account") == scope.first && body.getString("vault") == scope.second)
                    operation(body.getJSONObject("operation")).also { check(it.id == id) }
                }
            } finally {
                plain.fill(0)
            }
        } finally {
            bytes.fill(0)
        }
    }
    suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            activateLocked(scope)
            val prefix = "TGDRIVE_V1_AI_PRIVATE:${scope.second}:"
            for (ref in telegram.findDocuments(prefix)) {
                val id = ref.marker.removePrefix(prefix)
                if (!ForgottenVaultPolicy.validId(id) || ref.marker != prefix + id || id in seen)continue
                val op = read(ref, scope, id)
                merge(scope, op)
                seen += id
                pending.remove(id)
                save(scope)
            }
            for (op in pending.values.toList()) {
                access(scope)
                val body = JSONObject().put("account", scope.first).put("vault", scope.second).put("operation", op.json()).toString().toByteArray()
                val cipher = try {
                    drive.intelligencePrivacyEnvelope(body, op.id, scope, false)
                } finally {
                    body.fill(0)
                }
                val staging = File(context.cacheDir, "private-ai-${UUID.randomUUID()}.bin")
                try {
                    staging.writeBytes(cipher)
                    access(scope)
                    val sent = telegram.sendDocument(staging, prefix + op.id) { _, _ -> }
                    val confirmed = read(sent, scope, op.id)
                    check(confirmed == op)
                    pending.remove(op.id)
                    seen.add(op.id)
                    save(scope)
                } finally {
                    cipher.fill(0)
                    staging.delete()
                }
            }
        }
    }
    suspend fun finishCleanup() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            access(scope)
            cleanupPending = false
            save(scope)
        }
    }
    fun lock() {
        synchronized(publication) {
            generation.incrementAndGet()
            sessionToken = -1
            current = PrivateAiScope()
        }
    }
    fun retire(owner: Long, vault: String) {
        require(ForgottenVaultPolicy.validId(vault))
        if (current.owner == owner && current.vault == vault)lock()
        File(context.noBackupFilesDir, "intelligence-private/$owner/$vault").deleteRecursively()
    }
}
