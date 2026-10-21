package app.televault.smart

import android.util.AtomicFile
import app.televault.data.DriveRepository
import app.televault.domain.AiAnalysisSource
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Hashless large device media: authenticated per-source cache, never a cloud duplicate receipt. */
internal class SmartLocalCache(private val drive: DriveRepository) {
    private val gate = Mutex()
    private val base = File(drive.localMediaContext().noBackupFilesDir, "smart-local-index-v1")
    private fun root(scope: Pair<Long, String>) = File(base, "${scope.first}/${scope.second}")
    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun read(scope: Pair<Long, String>, objectKey: String): JSONObject? {
        val file = AtomicFile(File(root(scope), "$objectKey.bin"))
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())return null
        val cipher = file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val block = ByteArray(4096)
            while (true) {
                val count = input.read(block)
                if (count < 0)break
                check(out.size() + count <= 32768)
                out.write(block, 0, count)
            }
            out.toByteArray()
        }
        val plain = try {
            drive.ocrEnvelope(cipher, "smart-local-v1/$objectKey", scope, true)
        } finally {
            cipher.fill(0)
        }
        return try {
            JSONObject(plain.toString(Charsets.UTF_8)).also { check(it.getInt("version") == 1 && it.getLong("account") == scope.first && it.getString("vault") == scope.second && key(it.getString("id")) == objectKey) }
        } finally {
            plain.fill(0)
        }
    }
    suspend fun restore(id: String, stamp: String, spec: SmartSpec, current: () -> Unit): JSONObject? = withContext(Dispatchers.IO) {
        gate.withLock {
            current()
            val scope = drive.privateVaultBinding()
            val body = try {
                read(scope, key(id))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            } ?: return@withLock null
            current()
            if (!AiAnalysisSource.same(id, stamp, spec.id, spec.revision, body.optString("id"), body.optString("stamp"), body.optString("model"), body.optString("revision")))return@withLock null
            body.getJSONObject("result")
        }
    }
    suspend fun save(id: String, stamp: String, spec: SmartSpec, result: JSONObject, current: () -> Unit) = withContext(Dispatchers.IO) {
        gate.withLock {
            current()
            check(AiAnalysisSource.same(id, stamp, spec.id, spec.revision, id, stamp, spec.id, spec.revision))
            val scope = drive.privateVaultBinding()
            val directory = root(scope)
            directory.mkdirs()
            val files = directory.listFiles().orEmpty()
            check(files.size < 50000 && files.sumOf { it.length() } < 256L * 1024 * 1024) { "Local AI cache is full" }
            val objectKey = key(id)
            val plain = JSONObject().put("version", 1).put("account", scope.first).put("vault", scope.second).put("id", id).put("stamp", stamp).put("model", spec.id).put("revision", spec.revision).put("result", result).toString().toByteArray()
            val cipher = try {
                check(plain.size <= 32740)
                drive.ocrEnvelope(plain, "smart-local-v1/$objectKey", scope, false)
            } finally {
                plain.fill(0)
            }
            try {
                val file = AtomicFile(File(directory, "$objectKey.bin"))
                val out = file.startWrite()
                try {
                    out.write(cipher)
                    current()
                    check(drive.privateVaultBinding() == scope)
                    file.finishWrite(out)
                } catch (t: Throwable) {
                    file.failWrite(out)
                    throw t
                }
            } finally {
                cipher.fill(0)
            }
        }
    }
    suspend fun cleanPrivateSources() = withContext(Dispatchers.IO) {
        gate.withLock {
            val scope = drive.privateVaultBinding()
            val directory = root(scope)
            val keys = directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak").removeSuffix(".new") }.filter { it.matches(Regex("[0-9a-f]{64}\\.bin")) }.distinct()
            for (name in keys) {
                currentCoroutineContext().ensureActive()
                check(drive.privateVaultBinding() == scope)
                val body = try {
                    read(scope, name.removeSuffix(".bin"))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                // This namespace holds only rebuildable derived results, never originals or names/reviews.
                val remove = body == null || drive.privateAi.excluded(body.optString("id"))
                check(drive.privateVaultBinding() == scope)
                if (remove) AtomicFile(File(directory, name)).delete()
            }
        }
    }
    suspend fun retire(owner: Long, vault: String) = withContext(Dispatchers.IO) {
        gate.withLock {
            require(owner > 0 && UUID.fromString(vault).toString() == vault)
            val directory = root(owner to vault)
            check(!directory.exists() || directory.deleteRecursively())
        }
    }
}
