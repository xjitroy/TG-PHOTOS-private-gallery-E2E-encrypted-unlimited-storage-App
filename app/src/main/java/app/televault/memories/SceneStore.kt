package app.televault.memories

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import app.televault.data.DriveRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class SceneState(val enabled: Boolean = false, val indexed: Int = 0, val revision: Long = 0, val labels: Map<String, String> = emptyMap(), val notice: String = "", val capacityReached: Boolean = false)

/** Local encrypted derived index. No images, labels or embeddings go to an AI service. */
internal class SceneStore(private val context: Context, private val drive: DriveRepository, val models: MemoryModels) {
    private data class Record(val stamp: String, val hash: String, val labels: String)
    private val gate = Mutex()
    private val publication = Any()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = linkedMapOf<String, Record>()
    private val mutable = MutableStateFlow(SceneState())
    val state = mutable.asStateFlow()

    @Volatile private var binding: Pair<Long, String>? = null
    private var enabled = false
    private var dirty = 0
    private var full = false
    private val budget = IndexBudget(8L * 1024 * 1024 - 4096)
    private fun encoded(id: String, row: Record) = JSONArray().put(id).put(row.stamp).put(row.hash).put(row.labels)
    private fun weight(id: String, row: Record) = encoded(id, row).toString().toByteArray().size + 1
    private fun file(scope: Pair<Long, String>) = AtomicFile(File(context.noBackupFilesDir, "memory-index/${scope.first}/${scope.second}/scenes-v1.bin"))
    private fun guard(scope: Pair<Long, String>, token: Long) {
        check(generation.get() == token && binding == scope && drive.privateVaultBinding() == scope)
    }
    private fun publish(scope: Pair<Long, String>, token: Long) {
        guard(scope, token)
        val visible = if (enabled) records.filter { !drive.privateAi.excluded(it.key, it.value.hash) }.mapValues { it.value.labels } else emptyMap()
        synchronized(publication) {
            check(generation.get() == token && binding == scope)
            mutable.value = SceneState(enabled, records.size, mutable.value.revision + 1, visible, if (full) "Subject index reached its on-device storage budget. Existing suggestions and search results remain available." else "", full)
        }
    }
    suspend fun activate() = withContext(Dispatchers.IO) {
        gate.withLock {
            val scope = drive.privateVaultBinding()
            if (binding == scope) return@withLock
            records.clear()
            budget.clear()
            full = false
            enabled = false
            dirty = 0
            val token = generation.get()
            binding = scope
            val target = file(scope)
            if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) {
                try {
                    val cipher = target.openRead().use { input ->
                        check(target.baseFile.length() in 28..(8L * 1024 * 1024 + 28))
                        input.readBytes()
                    }
                    val bytes = try {
                        drive.ocrEnvelope(cipher, "memory-scenes-v1", scope, true)
                    } finally {
                        cipher.fill(0)
                    }
                    try {
                        val body = JSONObject(bytes.toString(Charsets.UTF_8))
                        check(body.getInt("version") == 1 && body.getString("model") == "mobilenetv2-c1c51358-v1")
                        enabled = body.getBoolean("enabled")
                        full = body.optBoolean("capacityReached")
                        val rows = body.getJSONArray("records")
                        check(rows.length() <= 50000)
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONArray(i)
                            val id = row.getString(0)
                            val stamp = row.getString(1)
                            val hash = row.getString(2)
                            val text = row.getString(3)
                            check(id.length <= 4096 && stamp.length <= 8192 && hash.length <= 128 && text.length <= 1024)
                            val record = Record(stamp, hash, text)
                            check(budget.replace(id, weight(id, record)))
                            records[id] = record
                        }
                    } finally {
                        bytes.fill(0)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Keep unreadable ciphertext for recovery. Never overwrite it with an empty index.
                    records.clear()
                    budget.clear()
                    binding = null
                    synchronized(publication) { if (generation.get() == token) mutable.value = SceneState(notice = "Smart suggestions could not open. Original files are kept.") }
                    return@withLock
                }
            }
            if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
                enabled = true
                save(scope, token)
            }
            publish(scope, token)
        }
    }
    private fun save(scope: Pair<Long, String>, token: Long) {
        guard(scope, token)
        val rows = JSONArray()
        records.forEach { (id, row) -> rows.put(encoded(id, row)) }
        val bytes = JSONObject().put("version", 1).put("model", "mobilenetv2-c1c51358-v1").put("enabled", enabled).put("capacityReached", full).put("records", rows).toString().toByteArray()
        try {
            check(bytes.size <= 8 * 1024 * 1024)
            val cipher = drive.ocrEnvelope(bytes, "memory-scenes-v1", scope, false)
            try {
                val target = file(scope)
                target.baseFile.parentFile!!.mkdirs()
                val output = target.startWrite()
                try {
                    output.write(cipher)
                    guard(scope, token)
                    target.finishWrite(output)
                    dirty = 0
                } catch (error: Throwable) {
                    target.failWrite(output)
                    throw error
                }
            } finally {
                cipher.fill(0)
            }
        } finally {
            bytes.fill(0)
        }
    }
    suspend fun enable(value: Boolean) = withContext(Dispatchers.IO) {
        activate()
        gate.withLock {
            val scope = checkNotNull(binding)
            val token = generation.get()
            enabled = value
            if (value) full = false
            save(scope, token)
            publish(scope, token)
            if (value) models.install()
        }
    }
    suspend fun analyze(id: String, stamp: String, preview: (suspend () -> android.graphics.Bitmap?)? = null, source: suspend () -> Uri): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            if (!enabled || full) return@withLock false
            val scope = checkNotNull(binding)
            val token = generation.get()
            guard(scope, token)
            if (!drive.normalIntelligenceMedia(id)) return@withLock false
            records[id]?.takeIf { it.stamp == stamp && !drive.privateAi.excluded(id, it.hash) }?.let { return@withLock false }
            val hash = drive.intelligenceContentHash(id)
            if (drive.privateAi.excluded(id, hash)) return@withLock false
            val text = app.televault.OnDeviceAnalysis.run {
                guard(scope, token)
                check(models.prepareInstalled())
                if (preview == null) SceneEngine(context, models).use { it.classify(context, source()) } else preview()?.let { image -> SceneEngine(context, models).use { it.classifyPixels(image) } }
            } ?: return@withLock false
            guard(scope, token)
            if (drive.privateAi.excluded(id, hash)) return@withLock false
            val record = Record(stamp, hash, text)
            if (records.size >= 50000 && id !in records || !budget.replace(id, weight(id, record))) {
                full = true
                publish(scope, token)
                save(scope, token)
                return@withLock false
            }
            records[id] = record
            dirty++
            if (dirty >= 50) {
                save(scope, token)
                publish(scope, token)
            }
            true
        }
    }
    suspend fun snapshot(id: String, stamp: String): JSONObject? = withContext(Dispatchers.IO) {
        gate.withLock {
            val scope = binding ?: return@withLock null
            guard(scope, generation.get())
            if (!enabled) return@withLock null
            records[id]?.takeIf { it.stamp == stamp && !drive.privateAi.excluded(id, it.hash) }?.let { JSONObject().put("model", "mobilenetv2-c1c51358-v1").put("labels", it.labels) }
        }
    }
    suspend fun reuse(id: String, stamp: String, hash: String, body: JSONObject?) = withContext(Dispatchers.IO) {
        if (body == null || hash.isBlank()) return@withContext
        gate.withLock {
            val scope = binding ?: return@withLock
            val token = generation.get()
            guard(scope, token)
            if (!enabled || !drive.normalIntelligenceMedia(id) || drive.privateAi.excluded(id, hash)) return@withLock
            check(body.getString("model") == "mobilenetv2-c1c51358-v1")
            val text = body.getString("labels")
            check(text.length <= 1024)
            if (records[id]?.stamp == stamp) return@withLock
            val row = Record(stamp, hash, text)
            if (records.size >= 50000 && id !in records || !budget.replace(id, weight(id, row))) return@withLock
            records[id] = row
            dirty++
            if (dirty >= 50) {
                save(scope, token)
                publish(scope, token)
            }
        }
    }
    suspend fun finish() = withContext(Dispatchers.IO) {
        gate.withLock {
            val scope = binding ?: return@withLock
            val token = generation.get()
            guard(scope, token)
            if (dirty > 0) {
                save(scope, token)
                publish(scope, token)
            }
        }
    }
    suspend fun cleanPrivateSources() = withContext(Dispatchers.IO) {
        activate()
        gate.withLock {
            val scope = binding ?: return@withLock
            val token = generation.get()
            val remove = records.filter { drive.privateAi.excluded(it.key, it.value.hash) }.keys
            if (remove.isNotEmpty()) {
                remove.forEach {
                    records.remove(it)
                    budget.remove(it)
                }
                full = false
                dirty++
                publish(scope, token)
                save(scope, token)
            } else {
                publish(scope, token)
            }
        }
    }
    fun retire(owner: Long, vault: String) {
        require(app.televault.domain.ForgottenVaultPolicy.validId(vault))
        if (binding == (owner to vault)) lock()
        File(context.noBackupFilesDir, "memory-index/$owner/$vault").deleteRecursively()
    }
    fun lock() {
        val token = synchronized(publication) {
            generation.incrementAndGet().also {
                binding = null
                mutable.value = SceneState()
            }
        }
        cleanup.launch {
            gate.withLock {
                if (generation.get() == token) {
                    records.clear()
                    budget.clear()
                    full = false
                    enabled = false
                    dirty = 0
                }
            }
        }
    }
}
