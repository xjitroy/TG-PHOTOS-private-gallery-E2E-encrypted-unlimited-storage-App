package app.televault.data

import android.content.Context
import android.util.AtomicFile
import app.televault.domain.BrowseResumePolicy
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Normal-library location only: never a viewer, grant, password, or Private Vault path. */
internal data class BrowsePosition(
    val tab: String = "TIMELINE",
    val section: String = "All",
    val folder: String? = null,
    val album: String? = null,
    val device: Boolean = false,
    val galleryCloud: Boolean = false,
    val libraryPath: String = "",
    val libraryCategory: String = "All files",
    val libraryRecent: Boolean = false,
    val index: Int = 0,
    val offset: Int = 0,
    val columns: Float = 4f,
    val explore: String = ""
) {
    fun safe(): BrowsePosition {
        val normal = BrowseResumePolicy.tab(tab)
        if (tab == "VAULT") return BrowsePosition(tab = "GALLERY", section = "Albums")
        if (normal != tab) return BrowsePosition()
        return copy(explore = explore.takeIf { it in setOf("All videos", "Normal videos", "Movies", "Documents") }.orEmpty(), folder = folder?.take(4096), album = album?.take(4096), section = section.take(64), libraryPath = libraryPath.take(4096), libraryCategory = libraryCategory.take(64), index = index.coerceIn(0, 100000), offset = offset.coerceIn(0, 100000), columns = columns.takeIf { it.isFinite() }?.coerceIn(2f, 12f) ?: 4f)
    }
}

internal class BrowsePositionStore(private val context: Context, private val drive: DriveRepository) {
    private val gate = Mutex()
    private fun file(binding: Pair<Long, String>) = AtomicFile(File(context.noBackupFilesDir, "browse/${binding.first}/${binding.second}.bin"))
    suspend fun load(epoch: Long): BrowsePosition = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                check(epoch == drive.playbackEpoch)
                val binding = drive.privateVaultBinding()
                val target = file(binding)
                if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return@withLock BrowsePosition()
                val cipher = target.openRead().use {
                    check(it.available() in 28..32768)
                    it.readBytes()
                }
                val bytes = try {
                    drive.ocrEnvelope(cipher, "browse-position-v1", binding, true)
                } finally {
                    cipher.fill(0)
                }
                try {
                    val row = JSONObject(bytes.toString(Charsets.UTF_8))
                    check(row.getInt("version") == 1 && epoch == drive.playbackEpoch)
                    BrowsePosition(row.getString("tab"), row.getString("section"), row.optString("folder").takeIf { it.isNotEmpty() }, row.optString("album").takeIf { it.isNotEmpty() }, row.optBoolean("device"), row.optBoolean("galleryCloud"), row.optString("path"), row.optString("category", "All files"), row.optBoolean("recent"), row.optInt("index"), row.optInt("offset"), row.optDouble("columns", 4.0).toFloat(), row.optString("explore")).safe()
                } finally {
                    bytes.fill(0)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                BrowsePosition()
            }
        }
    }
    suspend fun save(position: BrowsePosition, epoch: Long) = withContext(Dispatchers.IO) {
        gate.withLock {
            check(epoch == drive.playbackEpoch)
            val binding = drive.privateVaultBinding()
            val value = position.safe()
            val bytes = JSONObject().put("version", 1).put("tab", value.tab).put("section", value.section).put("folder", value.folder.orEmpty()).put("album", value.album.orEmpty()).put("device", value.device).put("galleryCloud", value.galleryCloud).put("path", value.libraryPath).put("category", value.libraryCategory).put("recent", value.libraryRecent).put("index", value.index).put("offset", value.offset).put("columns", value.columns.toDouble()).put("explore", value.explore).toString().toByteArray()
            val cipher = try {
                check(bytes.size <= 32740)
                drive.ocrEnvelope(bytes, "browse-position-v1", binding, false)
            } finally {
                bytes.fill(0)
            }
            try {
                val target = file(binding)
                target.baseFile.parentFile!!.mkdirs()
                val output = target.startWrite()
                try {
                    output.write(cipher)
                    check(epoch == drive.playbackEpoch && binding == drive.privateVaultBinding())
                    target.finishWrite(output)
                } catch (failure: Throwable) {
                    target.failWrite(output)
                    throw failure
                }
            } finally {
                cipher.fill(0)
            }
        }
    }
}
