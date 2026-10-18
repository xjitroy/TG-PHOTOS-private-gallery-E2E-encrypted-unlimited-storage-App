package app.televault.memories

import android.content.Context
import androidx.work.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class MemoryPack(val id: String, val path: String, val bytes: Long, val hash: String, val url: String)
internal data class MemoryModelState(val busy: Boolean = false, val downloaded: Long = 0, val total: Long = 0, val installed: Boolean = false, val notice: String = "")

/** Public model weights only. Private photos and face references never leave on-device inference. */
internal class MemoryModels(context: Context) {
    private val app = context.applicationContext
    private val gate = Mutex()
    val directory = File(app.noBackupFilesDir, "memory-models-v1")
    val packs: List<MemoryPack> = app.assets.open("memory-models.json").bufferedReader().use { input ->
        val rows = JSONObject(input.readText()).getJSONArray("models")
        (0 until rows.length()).map { i -> rows.getJSONObject(i).let { MemoryPack(it.getString("id"), it.getString("path"), it.getLong("bytes"), it.getString("sha256"), it.getString("url")) } }
    }
    private val mutable = MutableStateFlow(MemoryModelState(total = packs.sumOf { it.bytes }, installed = complete()))
    val state = mutable.asStateFlow()

    @Volatile private var verified = false
    private fun complete() = packs.all { File(directory, it.path).let { file -> file.isFile && file.length() == it.bytes } }
    private fun digest(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(131072)
        while (true) {
            val count = input.read(block)
            if (count < 0) break
            hash.update(block, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun install() {
        val work = OneTimeWorkRequestBuilder<MemoryModelsWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()).addTag("memory-models-v1").build()
        WorkManager.getInstance(app).enqueueUniqueWork("memory-models-v1", ExistingWorkPolicy.KEEP, work)
    }
    fun cancel() {
        WorkManager.getInstance(app).cancelUniqueWork("memory-models-v1")
    }
    suspend fun prepareInstalled(): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            if (verified) return@withLock true
            val valid = complete() && packs.all { digest(File(directory, it.path)) == it.hash }
            verified = valid
            mutable.value = mutable.value.copy(installed = valid)
            valid
        }
    }
    suspend fun download() = withContext(Dispatchers.IO) {
        gate.withLock {
            directory.mkdirs()
            val total = packs.sumOf { it.bytes }
            var completed = 0L
            try {
                for (pack in packs) {
                    check(pack.path.matches(Regex("[a-zA-Z0-9_]+\\.onnx")))
                    check(pack.url.startsWith("https://media.githubusercontent.com/media/onnx/models/c5cae0f1942c8d7727372c7b1f6b485aa39e4421/validated/vision/classification/mobilenet/model/"))
                    val target = File(directory, pack.path)
                    if (target.isFile && target.length() == pack.bytes && digest(target) == pack.hash) {
                        completed += pack.bytes
                        continue
                    }
                    check(directory.usableSpace > pack.bytes + 64L * 1024 * 1024) { "Not enough space for the memory model" }
                    val part = File(directory, pack.path + ".partial")
                    if (part.length() > pack.bytes || part.length() == pack.bytes && digest(part) != pack.hash) part.delete()
                    var offset = part.length()
                    val connection = URL(pack.url).openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 20000
                    connection.readTimeout = 30000
                    if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                    try {
                        val status = connection.responseCode
                        if (status == 200 && offset > 0) {
                            part.delete()
                            offset = 0
                        }
                        check(status == 200 && offset == 0L || status == 206 && offset > 0 && connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true)
                        connection.inputStream.use { input ->
                            java.io.FileOutputStream(part, offset > 0).use { output ->
                                val block = ByteArray(131072)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(block)
                                    if (count < 0) break
                                    check(offset + count <= pack.bytes)
                                    output.write(block, 0, count)
                                    offset += count
                                    mutable.value = MemoryModelState(true, completed + offset, total)
                                }
                                output.fd.sync()
                            }
                        }
                        check(part.length() == pack.bytes && digest(part) == pack.hash) { "Memory model verification failed" }
                        check(part.renameTo(target))
                        completed += pack.bytes
                    } finally {
                        connection.disconnect()
                    }
                }
                verified = true
                mutable.value = MemoryModelState(downloaded = total, total = total, installed = true)
            } catch (cancel: CancellationException) {
                mutable.value = MemoryModelState(total = total, notice = "Memory model download paused. Tap Download to resume.")
                throw cancel
            } catch (error: Exception) {
                mutable.value = MemoryModelState(total = total, notice = "Memory model download incomplete. Check your connection and storage, then retry.")
                throw error
            }
        }
    }
}

class MemoryModelsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as app.televault.TeleVaultApplication).memoryModels.download()
        app.televault.data.MediaIntelligenceScheduling.request(applicationContext, afterCurrent = true)
        Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: OutOfMemoryError) {
        Result.retry()
    } catch (_: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
