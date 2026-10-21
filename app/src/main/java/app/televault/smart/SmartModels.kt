package app.televault.smart

import android.app.ActivityManager
import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.util.AtomicFile
import androidx.work.*
import app.televault.data.MediaIntelligenceScheduling
import app.televault.data.transferForeground
import app.televault.domain.AiModelActivation
import app.televault.domain.AiModelPolicy
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

internal data class SmartFile(val path: String, val bytes: Long, val sha: String, val url: String)
internal data class SmartSpec(val id: String, val revision: String, val files: List<SmartFile>) {
    val bytes get() = files.sumOf { it.bytes }
    val name get() = when (id) {
        "small" -> "SmolVLM2 256M"
        "balanced" -> "SmolVLM2 500M"
        "qwen" -> "Qwen3.5 0.8B"
        else -> "EmbeddingGemma 2 · full"
    }
}
internal object SmartCatalog {
    fun read(context: Context): List<SmartSpec> {
        val rows = context.assets.open("smart-models.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("models") }
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val files = row.getJSONArray("files")
            SmartSpec(
                row.getString("id"),
                row.getString("revision"),
                (0 until files.length()).map { j ->
                    val f = files.getJSONObject(j)
                    SmartFile(f.getString("path"), f.getLong("bytes"), f.getString("sha256"), f.getString("url")).also {
                        check(it.path.matches(Regex("[A-Za-z0-9_.-]+")) && it.sha.matches(Regex("[0-9a-f]{64}")))
                        check(URI(it.url).scheme == "https" && URI(it.url).host == "huggingface.co" && it.url.contains("/resolve/${row.getString("revision")}/"))
                    }
                }
            ).also { check(AiModelPolicy.known(it.id)) }
        }
    }
}
internal data class SmartModelState(val choice: String = "auto", val desired: String = "balanced", val active: String = "", val busy: Boolean = false, val downloaded: Long = 0, val total: Long = 0, val notice: String = "", val fallback: Boolean = false, val enabled: Boolean = true, val phase: String = "idle", val failure: String = "")
internal data class SmartOutput(val vector: FloatArray = floatArrayOf(), val caption: String = "", val text: String = "")

/** Public weights only. The inference Binder owns no vault/session keys or network client. */
internal class SmartModels(context: Context) {
    private val app = context.applicationContext
    val specs = SmartCatalog.read(app)
    val supported = android.os.Process.is64Bit() && Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
    private val preferences = app.getSharedPreferences("smart-model-choice-v1", Context.MODE_PRIVATE).also { prefs ->
        // Repair 1.20.41's accidental model-failure cascade once; preserve choice, Off, Pause and files.
        if (prefs.getInt("runtimeRevision", 0) < 2) check(prefs.edit().putInt("runtimeRevision", 2).putBoolean("fallback", false).putBoolean("blocked", false).commit())
    }
    private val root = File(app.noBackupFilesDir, "smart-models-v1")
    private val activeFile = AtomicFile(File(root, "active.json"))
    private val lanes = SmartWorkLanes()
    private val commitGate = Any()
    private val interruptions = java.util.concurrent.atomic.AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ram: Long get() {
        val info = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem
    }
    private fun desired() = AiModelPolicy.desired(preferences.getString("choice", "auto"), ram, preferences.getBoolean("fallback", false))
    private fun readActive() = try {
        activeFile.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)).getString("id") }.takeIf { AiModelPolicy.known(it) }.orEmpty()
    } catch (_: Exception) {
        ""
    }
    private val mutable = MutableStateFlow(SmartModelState(choice = preferences.getString("choice", "auto").orEmpty(), desired = desired(), active = readActive(), fallback = preferences.getBoolean("fallback", false), enabled = preferences.getBoolean("enabled", true)))
    val state = mutable.asStateFlow()
    private fun active() = mutable.value.active

    @Volatile private var suspended = preferences.getBoolean("fallback", false) && mutable.value.active != "small"
    fun available() = supported && mutable.value.enabled && !suspended && !preferences.getBoolean("blocked", false) && active().isNotBlank()
    fun enable(enabled: Boolean) {
        check(preferences.edit().putBoolean("enabled", enabled).commit())
        mutable.update { it.copy(enabled = enabled) }
        if (enabled) {
            retry()
            MediaIntelligenceScheduling.request(app)
        } else {
            WorkManager.getInstance(app).cancelUniqueWork("smart-models-v1")
            pause()
        }
    }

    @Volatile private var remote: IBinder? = null
    private var connection: ServiceConnection? = null

    @Volatile private var loaded = ""
    private val verified = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun spec(id: String) = specs.single { it.id == id }
    private fun directory(id: String) = File(root, "$id-${spec(id).revision}")
    private fun memoryAllowed(id: String): Boolean {
        val info = ActivityManager.MemoryInfo()
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return !info.lowMemory && if (loaded == id) info.availMem - info.threshold >= 192L * 1024 * 1024 else AiModelPolicy.enoughMemory(info.availMem, info.threshold, id)
    }
    fun select(id: String) {
        require(id == "auto" || AiModelPolicy.known(id))
        synchronized(commitGate) {
            check(preferences.edit().putString("choice", id).putBoolean("fallback", false).putBoolean("blocked", false).putBoolean("downloadPaused", false).commit())
            if (id == active()) suspended = false
            mutable.update { it.copy(choice = id, desired = desired(), fallback = false, notice = "") }
        }
        install()
    }
    fun retry() {
        suspended = false
        check(preferences.edit().putBoolean("blocked", false).putBoolean("downloadPaused", false).commit())
        install()
        MediaIntelligenceScheduling.request(app)
    }
    fun installAllowed() = mutable.value.enabled && !preferences.getBoolean("downloadPaused", false) && !preferences.getBoolean("blocked", false)
    fun install() {
        if (!installAllowed()) return
        if (active() == desired() && active() in verified && !preferences.getBoolean("cleanupPending", false)) return
        if (!supported) {
            mutable.update { it.copy(notice = "Smart models require ARM64. Existing on-device face, text and subject engines remain available.") }
            return
        }
        val work = OneTimeWorkRequestBuilder<SmartModelsWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()).addTag("smart-models-v1").build()
        WorkManager.getInstance(app).enqueueUniqueWork("smart-models-v1", ExistingWorkPolicy.KEEP, work)
    }
    fun cancel() {
        check(preferences.edit().putBoolean("downloadPaused", true).commit())
        WorkManager.getInstance(app).cancelUniqueWork("smart-models-v1")
    }
    private fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(131072)
        while (true) {
            val n = input.read(buffer)
            if (n < 0)break
            hash.update(buffer, 0, n)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun valid(id: String): Boolean {
        if (id in verified)return true
        val s = spec(id)
        val ok = s.files.all {
            val f = File(directory(id), it.path)
            f.isFile && f.length() == it.bytes && digest(f) == it.sha
        }
        if (ok)verified.add(id)
        return ok
    }
    private suspend fun release() {
        val binder = remote
        val stopped = CompletableDeferred<Unit>()
        val death = IBinder.DeathRecipient { stopped.complete(Unit) }
        if (binder != null) runCatching { binder.linkToDeath(death, 0) }.onFailure { stopped.complete(Unit) }
        remote = null
        loaded = ""
        if (binder != null) {
            runCatching {
                val p = Parcel.obtain()
                try {
                    p.writeInterfaceToken(InferenceService.DESCRIPTOR)
                    binder.transact(InferenceService.STOP, p, null, IBinder.FLAG_ONEWAY)
                } finally {
                    p.recycle()
                }
            }
        }
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
        if (binder != null) {
            withTimeoutOrNull(4000) { stopped.await() }
            runCatching { binder.unlinkToDeath(death, 0) }
        }
    }
    fun pause() {
        interruptions.incrementAndGet()
        val owned = remote
        // Stop the currently owned child immediately, even during an in-flight native call.
        scope.launch {
            if (owned != null) {
                runCatching {
                    val parcel = Parcel.obtain()
                    try {
                        parcel.writeInterfaceToken(InferenceService.DESCRIPTOR)
                        owned.transact(InferenceService.STOP, parcel, null, IBinder.FLAG_ONEWAY)
                    } finally {
                        parcel.recycle()
                    }
                }
            }
            lanes.runtime { if (remote === owned || remote == null) release() }
        }
    }
    suspend fun releaseWhenIdle() = withContext(Dispatchers.IO) {
        lanes.idle { release() }
    }
    fun memoryPressure() {
        if (loaded.isNotBlank()) scope.launch { lanes.runtime { fallback("The selected AI met memory pressure. The small model will be used.", memory = true) } }
    }
    private suspend fun bind(): IBinder {
        remote?.takeIf { it.isBinderAlive }?.let { return it }
        release()
        val ready = CompletableDeferred<IBinder>()
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                ready.complete(binder)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) {
                    remote = null
                    loaded = ""
                }
                ready.completeExceptionally(DeadObjectException())
            }
            override fun onNullBinding(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("AI service unavailable"))
            }
        }
        connection = c
        check(app.bindService(Intent(app, InferenceService::class.java), c, Context.BIND_AUTO_CREATE))
        return withTimeout(15000) { ready.await() }.also { remote = it }
    }
    private fun <T> rpc(binder: IBinder, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(InferenceService.DESCRIPTOR)
            write(input)
            check(binder.transact(code, input, output, 0))
            output.readException()
            return read(output)
        } finally {
            input.recycle()
            output.recycle()
        }
    }
    private suspend fun load(id: String) {
        if (loaded == id && remote?.isBinderAlive == true)return
        release()
        check(memoryAllowed(id)) { "model-memory" }
        val b = bind()
        rpc(b, InferenceService.OPEN, { it.writeString(id) }) { check(it.readInt() != Process.myPid()) }
        loaded = id
    }
    private suspend fun fallback(message: String, failedId: String = active(), memory: Boolean = false) {
        suspended = true
        release()
        if (desired() != failedId) return // A stale model failure cannot override a newer choice.
        if (desired() != "small") {
            check(preferences.edit().putBoolean("fallback", true).commit())
            mutable.update { it.copy(desired = "small", fallback = true, notice = message) }
            install()
        } else if (memory) {
            mutable.update { it.copy(notice = "Waiting for available memory. Small AI will retry; face/OCR analysis continues.") }
            install()
        } else {
            check(preferences.edit().putBoolean("blocked", true).commit())
            mutable.update { it.copy(notice = "AI could not run on this device. Existing face/OCR/subject engines remain available. Retry in Settings.") }
        }
    }
    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        lanes.runtime {
            if (!available())return@runtime false
            val id = active()
            val request = interruptions.get()
            if (id.isBlank() || !valid(id))return@runtime false
            try {
                load(id)
                true
            } catch (cancel: CancellationException) {
                release()
                throw cancel
            } catch (e: Exception) {
                mutable.update { it.copy(failure = safeFailure(e)) }
                if (request == interruptions.get()) fallback("AI could not run reliably; switching to the small model.", id, e.message?.startsWith("model-memory") == true)
                false
            }
        }
    }
    suspend fun image(image: Bitmap): SmartOutput? = withContext(Dispatchers.IO) {
        lanes.runtime {
            val id = active()
            val request = interruptions.get()
            if (!available() || !valid(id))return@runtime null
            try {
                load(id)
                if (!memoryAllowed(id)) {
                    fallback("Low available memory; switching to the small AI.", memory = true)
                    return@runtime null
                }
                val scale = minOf(1f, 512f / maxOf(image.width, image.height))
                val small = Bitmap.createScaledBitmap(image, (image.width * scale).toInt().coerceAtLeast(1), (image.height * scale).toInt().coerceAtLeast(1), true)
                val stream = java.io.ByteArrayOutputStream()
                val bytes = try {
                    check(small.compress(Bitmap.CompressFormat.JPEG, 88, stream))
                    stream.toByteArray()
                } finally {
                    if (small !== image) {
                        small.eraseColor(0)
                        small.recycle()
                    }
                }
                try {
                    rpc(checkNotNull(remote), InferenceService.IMAGE, { it.writeByteArray(bytes) }) { p ->
                        val vector = p.createFloatArray() ?: floatArrayOf()
                        val body = p.createByteArray() ?: byteArrayOf()
                        try {
                            if (id == "embedding") {
                                check(vector.size == 256 && vector.all { it.isFinite() })
                                SmartOutput(vector)
                            } else {
                                val raw = body.toString(Charsets.UTF_8).take(16000)
                                val json = runCatching { JSONObject(raw.substringAfter("{", "").substringBeforeLast("}").let { "{$it}" }) }.getOrNull()
                                val caption = json?.optString("caption")?.takeIf { it.isNotBlank() } ?: raw.replace(Regex("(?s)<think>.*?</think>"), "").trim().takeIf { !it.contains("<think>") }.orEmpty()
                                SmartOutput(caption = caption.take(1024), text = json?.optString("text").orEmpty().take(4096))
                            }
                        } finally {
                            body.fill(0)
                        }
                    }
                } finally {
                    bytes.fill(0)
                }
            } catch (cancel: CancellationException) {
                release()
                throw cancel
            } catch (e: Exception) {
                // An unreadable/slow image is not proof that the selected model is incompatible.
                release()
                mutable.update { it.copy(failure = safeFailure(e), notice = "This image could not be analysed by the large AI. Other photos and face/OCR analysis continue.") }
                null
            }
        }
    }
    suspend fun query(text: String): FloatArray? = withContext(Dispatchers.IO) {
        lanes.runtime {
            val id = active()
            val request = interruptions.get()
            if (id != "embedding" || !available() || !valid(id))return@runtime null
            try {
                load(id)
                rpc(checkNotNull(remote), InferenceService.TEXT, { it.writeString(text.take(2048)) }) { it.createFloatArray() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                release()
                null
            }
        }
    }
    private fun safeFailure(error: Exception): String = when {
        error is DeadObjectException -> "process-stopped"
        error.message == "model-storage" -> "storage"
        error.message == "model-verification" -> "verification"
        error.message == "model-memory" -> "memory"
        error.message?.startsWith("model-") == true -> error.message.orEmpty().take(80).takeIf { it.matches(Regex("[a-z0-9:_ -]+")) } ?: "runtime"
        else -> "setup-or-runtime"
    }
    private fun connect(url: String, offset: Long): HttpURLConnection {
        var current = URL(url)
        repeat(8) {
            check(current.protocol == "https")
            val c = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 30000
                if (offset > 0)setRequestProperty("Range", "bytes=$offset-")
            }
            if (c.responseCode in listOf(301, 302, 303, 307, 308)) {
                val next = URL(current, c.getHeaderField("Location"))
                c.disconnect()
                check(next.protocol == "https" && next.userInfo == null)
                current = next
            } else {
                return c
            }
        }
        error("Too many model redirects")
    }
    private fun retireInactive(id: String) {
        var complete = true
        val keep = directory(id).name
        val names = specs.map { it.id }.toSet()
        for (old in root.listFiles().orEmpty()) {
            val owned = old.name in names || names.any { old.name.matches(Regex("$it-[0-9a-f]{40}")) }
            if (owned && old.isDirectory && old.name != keep && !old.deleteRecursively()) complete = false
        }
        verified.retainAll(setOf(id))
        val compiled = File(app.cacheDir, "embedding-2")
        if (id != "embedding" && compiled.exists() && !compiled.deleteRecursively()) complete = false
        check(preferences.edit().putBoolean("cleanupPending", !complete).commit())
        if (!complete) mutable.update { it.copy(notice = "New AI is ready. Old model cleanup will retry; free space may take longer to update.") }
    }
    suspend fun download() = withContext(Dispatchers.IO) {
        lanes.download {
            if (!installAllowed()) return@download
            val id = desired()
            val s = spec(id)
            if (active() == id && valid(id)) {
                mutable.update { it.copy(busy = true, phase = "initializing", downloaded = s.bytes, total = s.bytes, failure = "", notice = "Verifying the installed model can run…") }
                try {
                    lanes.runtime {
                        try {
                            load(id)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            mutable.update { it.copy(phase = "failed", failure = safeFailure(e)) }
                            fallback("Installed AI could not initialize; existing specialised engines continue.", id, e.message?.startsWith("model-memory") == true)
                            throw e
                        }
                        retireInactive(id)
                    }
                } finally {
                    mutable.update { it.copy(busy = false) }
                }
                mutable.update { it.copy(busy = false, phase = "ready", failure = "", desired = id, total = s.bytes, downloaded = s.bytes) }
                return@download
            }
            val folder = directory(id)
            folder.mkdirs()
            mutable.update { it.copy(busy = true, phase = "downloading", failure = "", desired = id, total = s.bytes, downloaded = 0, notice = "Downloading verified AI. The previous model stays until this one works.") }
            var complete = 0L
            var lastProgress = 0L
            try {
                for (f in s.files) {
                    currentCoroutineContext().ensureActive()
                    val target = File(folder, f.path)
                    if (target.isFile && target.length() == f.bytes && digest(target) == f.sha) {
                        complete += f.bytes
                        continue
                    }
                    val part = File(folder, f.path + ".partial")
                    if (part.length() > f.bytes || part.length() == f.bytes && digest(part) != f.sha)part.delete()
                    if (part.length() < f.bytes) {
                        check(folder.usableSpace > f.bytes - part.length() + (if (id == "embedding") 512L else 64L) * 1024 * 1024) { "model-storage" }
                        var offset = part.length()
                        val c = connect(f.url, offset)
                        try {
                            val code = c.responseCode
                            if (code == 200 && offset > 0) {
                                check(part.delete())
                                offset = 0
                            }
                            check(code == 200 && offset == 0L || code == 206 && c.getHeaderField("Content-Range") == "bytes $offset-${f.bytes - 1}/${f.bytes}")
                            c.inputStream.use { input ->
                                java.io.FileOutputStream(part, offset > 0).use { out ->
                                    val buffer = ByteArray(131072)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        check(desired() == id) { "selection-changed" }
                                        val n = input.read(buffer)
                                        if (n < 0)break
                                        check(offset + n <= f.bytes)
                                        out.write(buffer, 0, n)
                                        offset += n
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - lastProgress >= 200) {
                                            mutable.update { it.copy(downloaded = complete + offset) }
                                            lastProgress = now
                                        }
                                    }
                                    out.fd.sync()
                                }
                            }
                        } finally {
                            c.disconnect()
                        }
                    }
                    check(part.length() == f.bytes && digest(part) == f.sha) { "model-verification" }
                    check(part.renameTo(target))
                    complete += f.bytes
                }
                mutable.update { it.copy(phase = "verifying", downloaded = s.bytes) }
                check(valid(id))
                currentCoroutineContext().ensureActive()
                lanes.runtime {
                    val initialization = interruptions.get()
                    try {
                        try {
                            mutable.update { it.copy(phase = "initializing", downloaded = s.bytes, failure = "", notice = "Download verified. Initializing and testing the model; first setup can take several minutes.") }
                            load(id)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            mutable.update { it.copy(failure = safeFailure(e), phase = "failed") }
                            if (initialization == interruptions.get()) fallback("This AI could not initialize. The previous model is kept; a small-model fallback is scheduled.", id, e.message?.startsWith("model-memory") == true)
                            throw IllegalStateException("model-initialize")
                        }
                        currentCoroutineContext().ensureActive()
                        synchronized(commitGate) {
                            AiModelActivation.commit(valid(id), loaded == id && remote?.isBinderAlive == true, desired() == id, {
                                val out = activeFile.startWrite()
                                try {
                                    out.write(JSONObject().put("id", id).put("revision", s.revision).toString().toByteArray())
                                    activeFile.finishWrite(out)
                                    suspended = false
                                } catch (t: Throwable) {
                                    activeFile.failWrite(out)
                                    throw t
                                }
                                mutable.update { it.copy(active = id, downloaded = s.bytes, phase = "ready", failure = "", notice = "AI ready. Face recognition and local OCR remain available.") }
                            }, { retireInactive(id) })
                        }
                    } catch (cancel: CancellationException) {
                        release()
                        throw cancel
                    }
                }
            } catch (cancel: CancellationException) {
                mutable.update { it.copy(phase = "paused", notice = "Download paused. The previous model is kept; Retry resumes verified partial files.") }
                throw cancel
            } catch (e: Exception) {
                if (!mutable.value.fallback) mutable.update { it.copy(phase = "failed", failure = safeFailure(e), notice = if (active() == id) "New AI is ready. Old model cleanup will retry." else "AI setup incomplete. Check connection and free space, then Retry. The previous model is kept.") }
                throw e
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}

class SmartModelsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as app.televault.TeleVaultApplication
        if (!app.smartModels.installAllowed()) return Result.success()
        return try {
            setForeground(transferForeground(applicationContext, 71910, "Preparing on-device AI"))
            app.smartModels.download()
            MediaIntelligenceScheduling.request(applicationContext)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (app.smartModels.installAllowed()) Result.retry() else Result.success()
        }
    }
}
