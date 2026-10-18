package app.televault.ocr

import android.content.Context
import androidx.work.*
import app.televault.data.MediaIntelligenceScheduling
import app.televault.domain.BackgroundAnalysisPolicy
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

internal data class OcrPack(val id: String, val path: String, val bytes: Long, val hash: String, val url: String)
internal data class OcrModelState(val busy: Boolean = false, val name: String = "", val downloaded: Long = 0, val total: Long = 0, val notice: String = "")
internal class OcrModels(context: Context) {
    private val app = context.applicationContext
    private val gate = Mutex()
    private val mutable = MutableStateFlow(OcrModelState())
    val state = mutable.asStateFlow()
    val directory = File(app.noBackupFilesDir, "ocr-models")
    val packs: List<OcrPack> = app.assets.open("ocr-models.json").bufferedReader().use { input ->
        val rows = JSONObject(input.readText()).getJSONArray("models")
        (0 until rows.length()).map { i -> rows.getJSONObject(i).let { OcrPack(it.getString("id"), it.getString("path"), it.getLong("bytes"), it.getString("sha256"), it.getString("url")) } }
    }

    @Volatile private var preparedForSession = false
    private val verified = mutableSetOf<String>()
    fun backgroundAllowed(needsNetwork: Boolean = true): Boolean = BackgroundAnalysisPolicy.allowed(app.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode, app.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered, needsNetwork)
    fun installedBytes(): Long = packs.sumOf { File(directory, "tessdata/${it.path}").takeIf(File::isFile)?.length() ?: 0L }
    fun install(all: Boolean = false) {
        val work = OneTimeWorkRequestBuilder<OcrModelsWorker>().setInputData(workDataOf("all" to all)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()).addTag("ocr-models").build()
        WorkManager.getInstance(app).enqueueUniqueWork("ocr-models", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }
    fun cancel() {
        WorkManager.getInstance(app).cancelUniqueWork("ocr-models")
    }
    suspend fun ensure(id: String): File = withContext(Dispatchers.IO) {
        gate.withLock {
            val pack = packs.single { it.id == id }
            check(pack.path.matches(Regex("(?:script/)?[A-Za-z_]+\\.traineddata")) && pack.url.startsWith("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/"))
            val target = File(directory, "tessdata/${pack.path}")
            fun digest(file: File): String {
                val md = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val block = ByteArray(131072)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0)break
                        md.update(block, 0, count)
                    }
                }
                return md.digest().joinToString("") { "%02x".format(it) }
            }
            if (target.isFile && target.length() == pack.bytes && (id in verified || digest(target) == pack.hash)) {
                verified += id
                return@withLock target
            }
            verified.remove(id)
            target.parentFile!!.mkdirs()
            check(directory.usableSpace > pack.bytes + 64L * 1024 * 1024)
            val part = File(target.path + ".partial")
            if (part.length() > pack.bytes) part.delete()
            var offset = part.length()
            var connection = URL(pack.url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                val code = connection.responseCode
                if (offset > 0 && code == 200) {
                    part.delete()
                    offset = 0
                }
                check(code == 200 && offset == 0L || code == 206 && offset > 0 && connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true)
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(part, offset > 0).use { output ->
                        val block = ByteArray(131072)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(block)
                            if (count < 0)break
                            check(offset + count <= pack.bytes)
                            output.write(block, 0, count)
                            offset += count
                            mutable.value = OcrModelState(true, id, offset, pack.bytes)
                        }
                        output.fd.sync()
                    }
                }
                check(part.length() == pack.bytes && digest(part) == pack.hash)
                check(part.renameTo(target))
                verified += id
                mutable.value = OcrModelState()
                target
            } catch (cancel: CancellationException) {
                mutable.value = OcrModelState(notice = "Language download paused; it can resume.")
                throw cancel
            } catch (error: Exception) {
                mutable.value = OcrModelState(notice = "Language download incomplete. Check network/storage and retry.")
                throw error
            } finally {
                connection.disconnect()
            }
        }
    }
    suspend fun prepare(all: Boolean) {
        val ids = if (all) packs.map { it.id } else packs.map { it.id }
        for (id in ids) ensure(id)
    }
}

class OcrModelsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as app.televault.TeleVaultApplication
        app.ocrModels.prepare(inputData.getBoolean("all", false))
        MediaIntelligenceScheduling.request(applicationContext)
        Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
