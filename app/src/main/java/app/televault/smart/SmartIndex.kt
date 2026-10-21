package app.televault.smart

import android.graphics.Bitmap
import android.util.Base64
import app.televault.data.DriveRepository
import app.televault.domain.AiVectorSearch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Bounded in-memory search projection; durable records live in the existing authenticated result store. */
internal class SmartIndex(private val drive: DriveRepository, private val models: SmartModels) {
    private data class Row(val stamp: String, val hash: String, val model: String, val revision: String, val vector: ByteArray, val caption: String, val text: String)
    private val rows = ConcurrentHashMap<String, Row>()
    private val local = SmartLocalCache(drive)
    private val epoch = AtomicLong()
    private val gate = Any()
    private var bytes = 0
    private val queryVectors = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean {
            if (size <= 16) return false
            eldest?.value?.fill(0f)
            return true
        }
    }
    val revision = MutableStateFlow(0L)
    fun lock() {
        synchronized(gate) {
            epoch.incrementAndGet()
            rows.clear()
            queryVectors.values.forEach { it.fill(0f) }
            queryVectors.clear()
            bytes = 0
            revision.value++
        }
        models.pause()
    }
    suspend fun cleanPrivateSources() {
        val excluded = rows.entries.filter { drive.privateAi.excluded(it.key, it.value.hash) }.map { it.key }
        synchronized(gate) {
            excluded.forEach { rows.remove(it) }
            bytes = rows.values.sumOf { 512 + it.caption.length * 2 + it.text.length * 2 }
            epoch.incrementAndGet()
            revision.value++
        }
        local.cleanPrivateSources()
    }
    suspend fun retire(owner: Long, vault: String) {
        if (runCatching { drive.privateVaultBinding() == (owner to vault) }.getOrDefault(false)) lock()
        local.retire(owner, vault)
    }
    private fun active() = models.state.value.active
    private fun accepted(id: String, hash: String) = drive.normalIntelligenceMedia(id) && !drive.privateAi.excluded(id, hash)
    private fun put(id: String, row: Row, generation: Long) {
        if (!accepted(id, row.hash))return
        synchronized(gate) {
            if (epoch.get() != generation)return
            val size = 512 + row.caption.length * 2 + row.text.length * 2
            val old = rows[id]?.let { 512 + it.caption.length * 2 + it.text.length * 2 } ?: 0
            if (bytes - old + size > 32 * 1024 * 1024 || rows.size >= 50000 && !rows.containsKey(id))return
            rows[id] = row
            bytes += size - old
            revision.value++
        }
    }
    fun restore(id: String, stamp: String, hash: String, body: JSONObject?) {
        if (body == null || body.optString("model") != active())return
        val token = epoch.get()
        val model = models.specs.find { it.id == body.optString("model") } ?: return
        if (body.optString("revision") != model.revision || !accepted(id, hash))return
        val vector = Base64.decode(body.optString("vector"), Base64.NO_WRAP)
        check(vector.isEmpty() || vector.size == 256)
        put(id, Row(stamp, hash, model.id, model.revision, vector, body.optString("caption").take(1024), body.optString("text").take(4096)), token)
    }
    fun snapshot(id: String, stamp: String): JSONObject? {
        val row = rows[id]?.takeIf { it.stamp == stamp && it.model == active() && accepted(id, it.hash) } ?: return null
        return JSONObject().put("version", 1).put("model", row.model).put("revision", row.revision).put("vector", Base64.encodeToString(row.vector, Base64.NO_WRAP)).put("caption", row.caption).put("text", row.text)
    }
    suspend fun analyze(id: String, stamp: String, hash: String, photo: suspend () -> Bitmap?) {
        if (!accepted(id, hash) || snapshot(id, stamp) != null)return
        val token = epoch.get()
        val binding = drive.privateVaultBinding()
        val selected = active()
        val spec = models.specs.find { it.id == selected } ?: return
        fun current() {
            check(epoch.get() == token && drive.privateVaultBinding() == binding && accepted(id, hash) && selected == active())
        }
        if (hash.isBlank()) {
            val saved = local.restore(id, stamp, spec, ::current)
            if (saved != null) {
                restore(id, stamp, hash, saved)
                if (snapshot(id, stamp) != null)return
            }
        }
        val image = photo() ?: return
        val output = try {
            models.image(image)
        } finally {
            image.eraseColor(0)
            image.recycle()
        } ?: error("Large AI returned no result for this image")
        currentCoroutineContext().ensureActive()
        if (drive.privateVaultBinding() != binding || selected != active())return
        check(output.vector.isNotEmpty() || output.caption.isNotBlank() || output.text.isNotBlank()) { "AI returned no usable result" }
        val vector = if (output.vector.isEmpty()) byteArrayOf() else AiVectorSearch.quantize(output.vector)
        val record = Row(stamp, hash, spec.id, spec.revision, vector, output.caption, output.text)
        if (hash.isBlank()) {
            val body = JSONObject().put("version", 1).put("model", spec.id).put("revision", spec.revision).put("vector", Base64.encodeToString(vector, Base64.NO_WRAP)).put("caption", output.caption).put("text", output.text)
            local.save(id, stamp, spec, body, ::current)
        }
        put(id, record, token)
    }
    private fun normalIds(): Set<String> {
        check(drive.state.value.stage == app.televault.data.VaultStage.READY && drive.privateAi.analysisReady())
        return buildSet {
            drive.state.value.files.filter { it.trashedAt == 0L && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) }.forEach { add(it.id) }
            drive.localMedia.changes.value.filter { !it.trashed && !it.galleryHidden && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) }.forEach { add(it.uri.toString()) }
        }
    }
    fun descriptions(ids: Set<String>): Map<String, String> {
        if (!models.state.value.enabled) return emptyMap()
        val allowed = normalIds()
        val token = epoch.get()
        val result = rows.entries.asSequence().filter { (id, row) -> id in ids && id in allowed && row.model == active() && !drive.privateAi.excluded(id, row.hash) }.associate { it.key to it.value.caption }
        return if (epoch.get() == token) result else emptyMap()
    }

    /** Text search keeps its exact matches; semantic matches add a bounded, scored shortlist. */
    suspend fun rank(ids: Set<String>, query: String): Map<String, Float> = withContext(Dispatchers.Default) {
        if (!models.state.value.enabled || query.isBlank() || active() != "embedding")return@withContext emptyMap()
        val token = epoch.get()
        val binding = drive.privateVaultBinding()
        val cacheKey = "$token/${active()}/${query.take(2048)}"
        val q = synchronized(gate) { queryVectors[cacheKey]?.copyOf() } ?: run {
            val vector = models.query(query) ?: return@withContext emptyMap()
            if (epoch.get() != token || drive.privateVaultBinding() != binding || !models.state.value.enabled) {
                vector.fill(0f)
                return@withContext emptyMap()
            }
            synchronized(gate) { if (epoch.get() == token) queryVectors[cacheKey] = vector.copyOf() }
            vector
        }
        try {
            val qNorm = sqrt(q.sumOf { (it * it).toDouble() }).toFloat()
            if (qNorm <= 0)return@withContext emptyMap()
            val allowed = normalIds()
            val matches = mutableListOf<Pair<String, Float>>()
            for ((id, row) in rows) {
                currentCoroutineContext().ensureActive()
                if (id !in ids || row.model != "embedding" || row.vector.size != q.size || id !in allowed || drive.privateAi.excluded(id, row.hash))continue
                val score = AiVectorSearch.score(row.vector, q)
                if (score.isFinite() && score >= 0.30f)matches += id to score
            }
            if (epoch.get() != token || drive.privateVaultBinding() != binding)return@withContext emptyMap()
            AiVectorSearch.shortlist(matches.toMap())
        } finally {
            q.fill(0f)
        }
    }
}
