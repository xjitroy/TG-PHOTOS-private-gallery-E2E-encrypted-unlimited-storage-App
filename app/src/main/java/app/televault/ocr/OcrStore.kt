package app.televault.ocr

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AtomicFile
import app.televault.data.DriveRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class OcrState(val enabled: Boolean = false, val indexed: Int = 0, val processed: Long = 0, val busy: Boolean = false, val notice: String = "")

/** Optional derived text is local authenticated ciphertext; never a cloud AI upload. */
internal class OcrStore(private val context: Context, private val drive: DriveRepository, val models: OcrModels) {
    private val mutex = Mutex()
    private val token = AtomicLong()
    private val publication = Any()
    private val mutable = MutableStateFlow(OcrState())
    val state = mutable.asStateFlow()

    @Volatile private var binding: Pair<Long, String>? = null
    private var engine: OcrEngine? = null
    private data class SearchRecord(val text: String, val hash: String)

    // A bounded RAM-only copy avoids decrypting every OCR record for every keystroke.
    private val searchCache = object : android.util.LruCache<String, SearchRecord>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: SearchRecord) = 2 * (key.length + value.text.length + value.hash.length) + 64
    }
    private val searchRevision = AtomicLong()
    private fun cacheKey(generation: Long, id: String) = "$generation:$id"
    private fun cacheRecord(scope: Pair<Long, String>, generation: Long, id: String, record: SearchRecord) {
        val revision = searchRevision.get()
        // Drive.lock holds the Drive monitor before this publication monitor. Never call back
        // into Drive while publication is held; recheck the token and cache revision instead.
        val allowed = try {
            !drive.privateAi.excluded(id, record.hash)
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
        synchronized(publication) {
            if (allowed && binding == scope && token.get() == generation && searchRevision.get() == revision && mutable.value.enabled) searchCache.put(cacheKey(generation, id), record)
        }
    }
    private fun root(scope: Pair<Long, String>) = File(context.noBackupFilesDir, "ocr-index/${scope.first}/${scope.second}")
    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun guard(scope: Pair<Long, String>, generation: Long) {
        check(token.get() == generation && drive.privateVaultBinding() == scope)
    }
    private fun publish(scope: Pair<Long, String>, generation: Long, value: OcrState) {
        guard(scope, generation)
        synchronized(publication) {
            check(token.get() == generation)
            mutable.value = value
        }
    }
    private fun readByKey(scope: Pair<Long, String>, objectKey: String): JSONObject? {
        check(objectKey.matches(Regex("[0-9a-f]{64}")))
        val file = AtomicFile(File(root(scope), "$objectKey.bin"))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())return null
        check(file.baseFile.length() <= 2 * 1024 * 1024)
        val cipher = file.openRead().use { it.readBytes() }
        val bytes = try {
            drive.ocrEnvelope(cipher, objectKey, scope, true)
        } finally {
            cipher.fill(0)
        }
        return try {
            JSONObject(bytes.toString(Charsets.UTF_8)).also { check(key(it.getString("id")) == objectKey) }
        } finally {
            bytes.fill(0)
        }
    }
    private fun read(scope: Pair<Long, String>, id: String): JSONObject? = readByKey(scope, key(id))
    private fun write(scope: Pair<Long, String>, generation: Long, id: String, body: JSONObject) {
        val bytes = body.toString().toByteArray()
        check(bytes.size <= 2 * 1024 * 1024)
        val cipher = try {
            drive.ocrEnvelope(bytes, key(id), scope, false)
        } finally {
            bytes.fill(0)
        }
        try {
            val file = AtomicFile(File(root(scope), "${key(id)}.bin"))
            file.baseFile.parentFile!!.mkdirs()
            val output = file.startWrite()
            try {
                output.write(cipher)
                guard(scope, generation)
                file.finishWrite(output)
                if (id != "settings") cacheRecord(scope, generation, id, SearchRecord(body.optString("text"), body.optString("contentHash")))
            } catch (error: Throwable) {
                file.failWrite(output)
                throw error
            }
        } finally {
            cipher.fill(0)
        }
    }
    suspend fun activate() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            if (binding == scope)return@withLock
            engine?.close()
            engine = null
            binding = scope
            searchCache.evictAll()
            val generation = token.incrementAndGet()
            val enabled = try {
                read(scope, "settings")?.optBoolean("enabled") ?: true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            }
            val count = root(scope).listFiles()?.count { it.extension == "bin" && it.name != "${key("settings")}.bin" } ?: 0
            publish(scope, generation, OcrState(enabled, count))
        }
    }
    suspend fun enable(enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            check(binding == scope)
            val generation = token.get()
            write(scope, generation, "settings", JSONObject().put("id", "settings").put("enabled", enabled))
            publish(scope, generation, mutable.value.copy(enabled = enabled, notice = ""))
            if (enabled)models.install()
        }
    }
    suspend fun analyze(id: String, stamp: String, source: suspend () -> Uri, thorough: Boolean = false, preferCached: Boolean = false): OcrText = withContext(Dispatchers.IO) {
        check(mutable.value.enabled)
        val requestedScope = drive.privateVaultBinding()
        val requestedToken = token.get()
        if (!drive.normalIntelligenceMedia(id))return@withContext OcrText.EMPTY
        guard(requestedScope, requestedToken)
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            check(binding == scope && mutable.value.enabled)
            val generation = token.get()
            val saved = try {
                read(scope, id)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (saved?.optString("stamp") == stamp && saved.optInt("engineRevision") >= 3 && (!thorough || saved.optBoolean("thorough") || preferCached && (saved.optJSONArray("words")?.length() ?: 0) > 0) && !drive.privateAi.excluded(id, saved.optString("contentHash"))) {
                try {
                    return@withLock OcrText.read(saved)
                } catch (_: org.json.JSONException) { } catch (_: IllegalStateException) { }
            }
            val contentHash = drive.intelligenceContentHash(id)
            if (drive.privateAi.excluded(id, contentHash))return@withLock OcrText.EMPTY
            models.prepare(false)
            val files = root(scope).listFiles().orEmpty()
            check(files.size < 50000 && files.sumOf { it.length() } < 256L * 1024 * 1024) { "Text index is full; clear it in Search to rebuild" }
            publish(scope, generation, mutable.value.copy(busy = true, notice = ""))
            try {
                val text = app.televault.OnDeviceAnalysis.run {
                    val image = decodeOcrPhoto(context, source()) ?: return@run OcrText.EMPTY
                    try {
                        guard(scope, generation)
                        (engine ?: OcrEngine(models).also { engine = it }).read(image, thorough)
                    } finally {
                        image.eraseColor(0)
                        image.recycle()
                    }
                }
                guard(scope, generation)
                if (drive.privateAi.excluded(id, contentHash))return@withLock OcrText.EMPTY
                write(scope, generation, id, text.json().put("contentHash", contentHash).put("id", id).put("stamp", stamp).put("thorough", thorough).put("engineRevision", 3))
                publish(scope, generation, mutable.value.copy(indexed = mutable.value.indexed + if (saved == null) 1 else 0, processed = mutable.value.processed + 1, busy = false))
                text
            } finally {
                if (token.get() == generation) publish(scope, generation, mutable.value.copy(busy = false))
            }
        }
    }
    suspend fun search(names: Map<String, String>, query: String): Set<String> = withContext(Dispatchers.IO) {
        if (query.isBlank())return@withContext names.keys
        val words = query.lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        fun matches(body: String) = words.all(app.televault.domain.TextTopics.searchText(body)::contains)
        val scope = binding
        val generation = token.get()
        // Names/categories remain searchable when OCR is disabled or still activating.
        if (scope == null || !mutable.value.enabled) return@withContext names.filter { !drive.privateAi.excluded(it.key) && matches(it.value) }.keys
        fun current() = binding == scope && token.get() == generation && mutable.value.enabled && drive.state.value.stage == app.televault.data.VaultStage.READY
        buildSet<String> {
            for ((id, name) in names) {
                currentCoroutineContext().ensureActive()
                if (!current()) return@withContext emptySet<String>()
                val text = try {
                    val cacheId = cacheKey(generation, id)
                    val cached = searchCache.get(cacheId)
                    val record = cached ?: read(scope, id)?.let { SearchRecord(it.optString("text"), it.optString("contentHash")) }?.also {
                        if (current()) cacheRecord(scope, generation, id, it)
                    }
                    if (drive.privateAi.excluded(id, record?.hash.orEmpty()))continue
                    record?.text.orEmpty()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    ""
                }
                if (matches(name + " " + text))add(id)
            }
            if (!current())return@withContext emptySet<String>()
        }
    }
    suspend fun cleanPrivateSources() {
        activate()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val scope = drive.privateVaultBinding()
                val generation = token.get()
                synchronized(publication) {
                    searchRevision.incrementAndGet()
                    searchCache.evictAll()
                }
                var removed = 0
                for (path in root(scope).listFiles().orEmpty().filter { it.extension == "bin" && it.nameWithoutExtension != key("settings") }) {
                    currentCoroutineContext().ensureActive()
                    guard(scope, generation)
                    val body = readByKey(scope, path.nameWithoutExtension) ?: continue
                    if (drive.privateAi.excluded(body.getString("id"), body.optString("contentHash"))) {
                        searchCache.remove(cacheKey(generation, body.getString("id")))
                        AtomicFile(path).delete()
                        removed++
                    }
                }
                publish(scope, generation, mutable.value.copy(indexed = (mutable.value.indexed - removed).coerceAtLeast(0)))
            }
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scope = drive.privateVaultBinding()
            val generation = token.get()
            guard(scope, generation)
            searchCache.evictAll()
            root(scope).deleteRecursively()
            engine?.close()
            engine = null
            publish(scope, generation, OcrState(notice = "Text index cleared. Original photos and People are unchanged."))
            searchCache.evictAll()
        }
    }
    suspend fun pauseEngine() = withContext(Dispatchers.IO) {
        mutex.withLock {
            engine?.close()
            engine = null
            mutable.value = mutable.value.copy(busy = false)
        }
    }
    fun lock() {
        val generation = synchronized(publication) {
            token.incrementAndGet().also {
                binding = null
                searchCache.evictAll()
                mutable.value = OcrState()
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (token.get() == generation) {
                    binding = null
                    engine?.close()
                    engine = null
                }
            }
        }
    }
    fun retire(owner: Long, vault: String) {
        require(app.televault.domain.ForgottenVaultPolicy.validId(vault))
        if (binding == (owner to vault))lock()
        root(owner to vault).deleteRecursively()
    }
}
