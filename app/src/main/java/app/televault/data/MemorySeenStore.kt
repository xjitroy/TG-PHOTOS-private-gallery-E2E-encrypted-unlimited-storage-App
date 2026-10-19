package app.televault.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Bounded device-local watch receipts, authenticated by the existing normal-vault envelope. */
internal class MemorySeenStore(private val context: Context, private val drive: DriveRepository) {
    private val gate = Mutex()
    val revision = MutableStateFlow(0)
    private suspend fun access(epoch: Long, add: String?): Set<String> = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                check(epoch == drive.playbackEpoch)
                val binding = drive.privateVaultBinding()
                val target = AtomicFile(File(context.noBackupFilesDir, "browse/${binding.first}/${binding.second}-memories.bin"))
                val rows = if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) {
                    val cipher = target.openRead().use {
                        check(it.available() in 28..65536)
                        it.readBytes()
                    }
                    val bytes = try {
                        drive.ocrEnvelope(cipher, "memory-seen-v1", binding, true)
                    } finally {
                        cipher.fill(0)
                    }
                    try {
                        JSONObject(bytes.toString(Charsets.UTF_8))
                    } finally {
                        bytes.fill(0)
                    }
                } else {
                    JSONObject()
                }
                check(epoch == drive.playbackEpoch && binding == drive.privateVaultBinding())
                if (add != null) {
                    require(add.matches(Regex("[a-f0-9]{64}")))
                    rows.put(add, System.currentTimeMillis())
                    val keys = rows.keys().asSequence().toList().sortedByDescending { rows.optLong(it) }.take(512)
                    val trimmed = JSONObject().apply { keys.forEach { put(it, rows.getLong(it)) } }
                    val bytes = trimmed.toString().toByteArray()
                    val cipher = try {
                        drive.ocrEnvelope(bytes, "memory-seen-v1", binding, false)
                    } finally {
                        bytes.fill(0)
                    }
                    try {
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
                    revision.value++
                    keys.toSet()
                } else {
                    rows.keys().asSequence().take(512).toSet()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                emptySet()
            }
        }
    }
    suspend fun load(epoch: Long) = access(epoch, null)
    suspend fun mark(receipt: String, epoch: Long) {
        access(epoch, receipt)
    }
}
