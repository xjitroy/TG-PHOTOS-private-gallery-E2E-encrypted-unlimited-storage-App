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

internal data class OcrState(val enabled: Boolean = false, val indexed: Int = 0, val busy: Boolean = false, val notice: String = "")

/** Optional derived text is local authenticated ciphertext; never a cloud AI upload. */
internal class OcrStore(private val context: Context, private val drive: DriveRepository, val models: OcrModels) {
    private val mutex = Mutex()
    private val token = AtomicLong()
    private val publication = Any()
    private val mutable = MutableStateFlow(OcrState())
    val state = mutable.asStateFlow()

    @Volatile private var binding: Pair<Long, String>? = null
    private var engine: OcrEngine? = null
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
            val generation = token.incrementAndGet()
            val enabled = try {
                read(scope, "settings")?.optBoolean("enabled") ?: false
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
    suspend fun analyze(id: String, stamp: String, source: suspend () -> Uri, thorough: Boolean = false): OcrText = withContext(Dispatchers.IO) {
        check(mutable.value.enabled)
        val requestedScope = drive.privateVaultBinding()
        val requestedToken = token.get()
        if (!drive.normalIntelligenceMedia(id))return@withContext OcrText.EMPTY
        val contentHash = drive.intelligenceContentHash(id)
        if (drive.privateAi.excluded(id, contentHash))return@withContext OcrText.EMPTY
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
            if (saved?.optString("stamp") == stamp && (!thorough || saved.optBoolean("thorough") && saved.optInt("engineRevision") >= 2)) {
                try {
                    return@withLock OcrText.read(saved)
                } catch (_: org.json.JSONException) { } catch (_: IllegalStateException) { }
            }
            models.prepare(false)
            val files = root(scope).listFiles().orEmpty()
            check(files.size < 50000 && files.sumOf { it.length() } < 256L * 1024 * 1024) { "Text index is full; clear it in Search to rebuild" }
            publish(scope, generation, mutable.value.copy(busy = true, notice = ""))
            var bitmap: Bitmap? = null
            try {
                val image = decodeOcrPhoto(context, source()) ?: return@withLock OcrText.EMPTY
                bitmap = image
                guard(scope, generation)
                val text = app.televault.OnDeviceAnalysis.run { (engine ?: OcrEngine(models).also { engine = it }).read(image, thorough) }
                guard(scope, generation)
                if (drive.privateAi.excluded(id, contentHash))return@withLock OcrText.EMPTY
                write(scope, generation, id, text.json().put("contentHash", contentHash).put("id", id).put("stamp", stamp).put("thorough", thorough).put("engineRevision", 2))
                publish(scope, generation, mutable.value.copy(indexed = mutable.value.indexed + if (saved == null) 1 else 0, busy = false))
                text
            } finally {
                bitmap?.let {
                    it.eraseColor(0)
                    it.recycle()
                }
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
                    val record = read(scope, id)
                    if (drive.privateAi.excluded(id, record?.optString("contentHash").orEmpty()))continue
                    record?.optString("text").orEmpty()
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
                var removed = 0
                for (path in root(scope).listFiles().orEmpty().filter { it.extension == "bin" && it.nameWithoutExtension != key("settings") }) {
                    currentCoroutineContext().ensureActive()
                    guard(scope, generation)
                    val body = readByKey(scope, path.nameWithoutExtension) ?: continue
                    if (drive.privateAi.excluded(body.getString("id"), body.optString("contentHash"))) {
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
            root(scope).deleteRecursively()
            engine?.close()
            engine = null
            publish(scope, generation, OcrState(notice = "Text index cleared. Original photos and People are unchanged."))
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
