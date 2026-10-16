package app.televault.data

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import app.televault.MainActivity
import app.televault.TeleVaultApplication
import app.televault.crypto.AuthenticatedRecords
import app.televault.crypto.VaultCrypto
import app.televault.domain.CloudExportFilter
import app.televault.domain.CloudExportNaming
import app.televault.domain.ResumableZip
import app.televault.telegram.RemoteDocument
import app.televault.telegram.TelegramGateway
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class CloudExportOptions(val kinds: Int = 0, val categories: Int = 0, val from: Long = 0, val until: Long = 0, val backupTime: Boolean = false, val timeZone: String = ZoneId.systemDefault().id) {
    fun matches(file: DriveFile) = CloudExportFilter.matches(file.mime, file.mediaCategories, kinds, categories, if (backupTime) file.backedUpAt else file.date, from, until)
    fun json() = JSONObject().put("kinds", kinds).put("categories", categories).put("from", from).put("until", until).put("backupTime", backupTime).put("timeZone", timeZone)
    fun filename(requestedAt: Long, count: Int, bytes: Long, coveredFrom: Long, coveredUntil: Long, id: String): String = CloudExportNaming.name(requestedAt, kinds, categories, from, until, backupTime, timeZone, coveredFrom, coveredUntil, count, bytes, id)
    companion object {
        fun read(json: JSONObject) = CloudExportOptions(json.optInt("kinds"), json.optInt("categories"), json.optLong("from"), json.optLong("until"), json.optBoolean("backupTime"), json.optString("timeZone", ZoneId.systemDefault().id))
    }
}
internal data class CloudExportHistory(val id: String, val name: String, val phase: String, val count: Int, val done: Int, val bytes: Long, val total: Long, val requestedAt: Long, val completedAt: Long, val destination: String, val noticed: Boolean, val options: CloudExportOptions, val automaticDownloads: Boolean = false, val directory: String = "", val coveredFrom: Long = 0, val coveredUntil: Long = 0, val unknownTimes: Int = 0, val problem: String = "")

private class EmptyCloudExport : IllegalStateException("No cloud files match the export filters")

/** Normal Cloud only. Plans/checkpoints/history stay AEAD sealed; originals stream only to owner-selected ZIP. */
internal class CloudExports(
    private val app: Context,
    private val database: DriveDatabase,
    private val telegram: TelegramGateway,
    private val protection: app.televault.telegram.TransportProtection,
    private val scope: () -> Pair<Long, String>,
    private val ownerKey: () -> ByteArray,
    private val workKey: suspend (CacheRow) -> ByteArray,
    private val release: (ByteArray) -> Unit,
    private val unlocked: () -> Boolean,
    private val syncCatalog: suspend (CacheRow) -> Unit,
    private val parseFile: (JSONObject) -> DriveFile
) {
    private val dao = database.cache()
    private val storage = CloudExportStorage(app)
    private val work = WorkManager.getInstance(app)
    private val control = Mutex()
    private val runner = Mutex()
    private val mutable = MutableStateFlow(emptyList<CloudExportHistory>())
    val history = mutable.asStateFlow()
    fun clear() {
        mutable.value = emptyList()
    }
    private fun aad(row: CacheRow) = "TGDrive/v1/${row.account}/${row.vault}/local/${row.id}".toByteArray()
    private fun open(row: CacheRow, key: ByteArray): JSONObject {
        protection.requireAllowed()
        val bytes = VaultCrypto.open(key, row.sealed, aad(row))
        return try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }
    private fun seal(row: CacheRow, data: JSONObject, key: ByteArray) {
        protection.requireAllowed()
        val bytes = data.toString().toByteArray()
        try {
            row.sealed = VaultCrypto.seal(key, bytes, aad(row))
        } finally {
            bytes.fill(0)
        }
    }
    private fun save(row: CacheRow, data: JSONObject, key: ByteArray) {
        if (dao.get(row.id)?.phase == "CANCELLED") throw CancellationException("Export cancelled")
        seal(row, data, key)
        if (dao.updateExportPayload(row.id, row.sealed, row.phase, row.progress, row.total) == 0) throw CancellationException("Export cancelled")
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!unlocked()) {
            clear()
            return@withContext
        }
        val binding = scope()
        val key = ownerKey()
        try {
            val items = dao.list(binding.first, binding.second, "cloud-export").map { row ->
                val data = open(row, key)
                val options = data.getJSONObject("options")
                CloudExportHistory(row.id, data.getString("name"), row.phase, data.getInt("count"), data.optInt("done"), row.progress, row.total, data.getLong("requestedAt"), data.optLong("completedAt"), data.optString("createdUri"), data.optBoolean("noticed"), CloudExportOptions.read(options), storage.automatic(data), data.optString("destination"), data.optLong("coveredFrom"), data.optLong("coveredUntil"), data.optInt("unknownTimes"), data.optString("problem"))
            }.sortedByDescending { it.requestedAt }
            if (unlocked() && scope() == binding) mutable.value = items
        } finally {
            key.fill(0)
        }
    }
    suspend fun start(options: CloudExportOptions, destination: Uri?) = withContext(Dispatchers.IO) {
        control.withLock {
            val binding = scope()
            val key = ownerKey()
            try {
                check(dao.list(binding.first, binding.second, "cloud-export").none { it.phase !in setOf("COMPLETE", "FAILED", "CANCELLED") }) { "An export is already running" }
                CloudExportNaming.validate(options.kinds, options.categories, options.from, options.until, options.timeZone)
                storage.validate(destination)
                val id = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val name = options.filename(now, 0, 0, 0, 0, id)
                val task = CacheRow().apply {
                    this.id = id
                    kind = "cloud-export"
                    account = binding.first
                    vault = binding.second
                    phase = "PREPARING"
                    total = 0
                }
                val data = JSONObject().put("version", 1).put("name", name).put("count", 0).put("requestedAt", now).put("destination", destination?.toString() ?: "").put("storage", if (destination == null) CloudExportStorage.DOWNLOADS else CloudExportStorage.TREE).put("options", options.json()).put("offset", 0).put("done", 0)
                seal(task, data, key)
                dao.put(task)
                schedule(id)
                refresh()
            } finally {
                key.fill(0)
            }
        }
    }
    private suspend fun prepare(task: CacheRow, data: JSONObject, key: ByteArray) {
        syncCatalog(task)
        checkWork(task)
        val selected = data.getJSONObject("options")
        val options = CloudExportOptions.read(selected)
        var count = 0
        var total = 0L
        var coveredFrom = 0L
        var coveredUntil = 0L
        var unknownTimes = 0
        val job = currentCoroutineContext()[Job]
        database.runInTransaction {
            for (id in dao.ids(task.account, task.vault, "file").sorted()) {
                job?.ensureActive()
                checkWork(task)
                val source = checkNotNull(dao.get(id))
                val metadata = open(source, key)
                val state = dao.get("state:${id.removePrefix("file:")}")?.let { open(it, key) }
                if (state?.optBoolean("purged") == true || (state?.optLong("trashedAt") ?: metadata.optLong("trashedAt")) > 0) continue
                metadata.put("name", state?.optString("displayName", metadata.getString("name")) ?: metadata.getString("name"))
                metadata.put("album", state?.optString("album", metadata.optString("album", "Uploads")) ?: metadata.optString("album", "Uploads"))
                val item = parseFile(metadata)
                if (!options.matches(item)) continue
                val time = if (options.backupTime) item.backedUpAt else item.date
                if (time > 0) {
                    coveredFrom = if (coveredFrom == 0L) time else minOf(coveredFrom, time)
                    coveredUntil = maxOf(coveredUntil, time)
                } else {
                    unknownTimes++
                }
                val entry = CacheRow().apply {
                    this.id = "${task.id}:entry:${count.toString().padStart(8, '0')}"
                    kind = "export-entry"
                    account = task.account
                    vault = task.vault
                }
                val name = "${(count + 1).toString().padStart(8, '0')}/${ResumableZip.safeName(item.name)}"
                seal(entry, JSONObject().put("metadata", metadata.put("thumbnail", "")).put("entry", name).put("time", item.date), key)
                dao.put(entry)
                count++
                total = Math.addExact(total, item.size)
            }
            if (count == 0) throw EmptyCloudExport()
            data.put("count", count).put("planned", true).put("coveredFrom", coveredFrom).put("coveredUntil", coveredUntil).put("unknownTimes", unknownTimes)
                .put("name", options.filename(data.getLong("requestedAt"), count, total, coveredFrom, coveredUntil, task.id))
            task.total = total
            task.phase = "QUEUED"
            save(task, data, key)
        }
    }
    private fun schedule(id: String, append: Boolean = false, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<CloudExportWorker>().setInputData(workDataOf("export" to id))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("tgdrive").addTag("tgdrive-cloud-export").build()
        work.enqueueUniqueWork("cloud-export:$id", if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }
    fun restoreScheduled(owner: Long, vault: String) {
        dao.list(owner, vault, "cloud-export").forEach {
            if (it.phase == "CANCELLED") {
                if (dao.get("${it.id}:entry:00000000") != null) scheduleCancelledCleanup(it.id)
            } else if (it.phase !in setOf("COMPLETE", "FAILED")) {
                schedule(it.id)
            }
        }
    }
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        val row = checkNotNull(dao.get(id))
        check(row.kind == "cloud-export" && row.account == scope().first && row.vault == scope().second && unlocked())
        if (row.phase == "COMPLETE" || row.phase == "CANCELLED") return@withContext
        schedule(id)
    }
    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        control.withLock {
            check(unlocked())
            val binding = scope()
            val row = checkNotNull(dao.get(id))
            check(row.kind == "cloud-export" && row.account == binding.first && row.vault == binding.second)
            val key = ownerKey()
            try {
                open(row, key) // Authenticate ownership before changing the job.
                if (dao.cancelExport(id, binding.first, binding.second) > 0) {
                    work.cancelUniqueWork("cloud-export:$id")
                    // Cleanup runs after the writer has released its destination. Keep entry keys
                    // until successful retirement; provider failures have a durable retry lane.
                    scheduleCancelledCleanup(id)
                }
            } finally {
                key.fill(0)
            }
            refresh()
        }
    }
    private fun scheduleCancelledCleanup(id: String) {
        val request = OneTimeWorkRequestBuilder<CloudExportWorker>().setInputData(workDataOf("export" to id, "cleanupCancelled" to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("tgdrive").addTag("tgdrive-cloud-export-cleanup").build()
        work.enqueueUniqueWork("cloud-export-cleanup:$id", ExistingWorkPolicy.KEEP, request)
    }
    suspend fun cleanupCancelled(id: String) = withContext(Dispatchers.IO) {
        runner.withLock {
            val row = dao.get(id) ?: return@withLock
            if (row.kind != "cloud-export" || row.phase != "CANCELLED") return@withLock
            val key = workKey(row)
            try {
                storage.discardPending(open(row, key), row.id)
                dao.removeExportEntries(row.account, row.vault, row.id)
            } finally {
                release(key)
            }
        }
    }
    suspend fun acknowledge(id: String) = withContext(Dispatchers.IO) {
        control.withLock {
            val key = ownerKey()
            try {
                val row = checkNotNull(dao.get(id))
                check(row.account == scope().first && row.vault == scope().second && row.phase == "COMPLETE")
                save(row, open(row, key).put("noticed", true), key)
                refresh()
            } finally {
                key.fill(0)
            }
        }
    }
    private fun checkWork(task: CacheRow) {
        protection.requireAllowed()
        check(scope() == (task.account to task.vault)) { "Cloud account changed" }
        if ((app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) throw DataPolicyBlocked("WAITING_UNLOCK")
        if (dao.get(task.id)?.phase == "CANCELLED") throw CancellationException("Export cancelled")
    }
    private suspend fun reader(task: CacheRow, metadata: JSONObject): AuthenticatedRecords {
        val parts = metadata.optJSONArray("parts")?.let { list -> (0 until list.length()).map { list.getJSONObject(it).let { p -> RemoteDocument(p.getLong("chat"), p.getLong("message"), p.getLong("bytes")) } } }
            ?: listOf(RemoteDocument(metadata.getLong("chat"), metadata.getLong("message"), metadata.getLong("cipherSize")))
        val contentKey = android.util.Base64.decode(metadata.getString("key"), android.util.Base64.NO_WRAP)
        val job = currentCoroutineContext()[Job]
        return try {
            AuthenticatedRecords({ offset, output, destination, requested ->
                checkWork(task)
                var at = offset
                var remaining = requested
                var target = destination
                for (part in parts) {
                    if (at >= part.bytes) {
                        at -= part.bytes
                        continue
                    }
                    val amount = minOf(remaining.toLong(), part.bytes - at).toInt()
                    val bytes = runBlocking((job ?: kotlin.coroutines.EmptyCoroutineContext) + Dispatchers.IO) { telegram.readSpeculativeDocumentPart(part, at, amount) }
                    try {
                        checkWork(task)
                        check(bytes.size == amount)
                        System.arraycopy(bytes, 0, output, target, amount)
                    } finally {
                        bytes.fill(0)
                    }
                    remaining -= amount
                    target += amount
                    at = 0
                    if (remaining == 0) break
                }
                check(remaining == 0)
            }, contentKey, UUID.fromString(task.vault), UUID.fromString(metadata.getString("id")), metadata.getLong("size"), parts.sumOf { it.bytes })
        } finally {
            contentKey.fill(0)
        }
    }
    private fun checkpoint(entry: ResumableZip.Entry) = JSONObject().put("name", entry.name()).put("size", entry.size()).put("time", entry.time()).put("offset", entry.offset()).put("crc", entry.crc()).put("end", entry.end())
    private fun checkpoint(json: JSONObject) = ResumableZip.Entry(json.getString("name"), json.getLong("size"), json.getLong("time"), json.getLong("offset"), json.getLong("crc"), json.getLong("end"))
    suspend fun execute(id: String, report: suspend (Long, Long, Int, Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        runner.withLock {
            val task = dao.get(id) ?: return@withContext true
            check(task.kind == "cloud-export")
            if (task.phase in setOf("COMPLETE", "CANCELLED")) return@withContext true
            val key = workKey(task)
            var data: JSONObject? = null
            try {
                data = open(task, key)
                data.remove("problem")
                checkWork(task)
                if (!data.optBoolean("planned")) prepare(task, data, key)
                if (!data.has("createdUri")) {
                    control.withLock {
                        // Explicit cancellation cannot commit between creation and persisting the
                        // exact ownership proof needed to retire that new partial safely.
                        checkWork(task)
                        data.put("createdUri", storage.createOrRecover(data, task.id).toString())
                        save(task, data, key)
                    }
                }
                // Resume only the tracked destination; a filename is not proof of ownership.
                val uri = storage.recover(data, Uri.parse(data.getString("createdUri")))
                data.put("createdUri", uri.toString())
                save(task, data, key)
                val count = data.getInt("count")
                val done = data.optInt("done")
                check(done in 0..count)
                fun entryId(index: Int) = "$id:entry:${index.toString().padStart(8, '0')}"
                fun completedEntry(index: Int): ResumableZip.Entry = checkpoint(open(checkNotNull(dao.get(entryId(index))), key).getJSONObject("checkpoint"))
                // Parent and entry boundary commit atomically. No full-library scan on each chunk.
                if (done > 0) check(completedEntry(done - 1).end() == data.getLong("offset"))
                task.progress = data.optLong("completedBytes")
                val started = android.os.SystemClock.elapsedRealtime()
                var processed = 0
                task.phase = "EXPORTING"
                save(task, data, key)
                val descriptor = checkNotNull(app.contentResolver.openFileDescriptor(uri, "rw"))
                lateinit var manifestEntry: ResumableZip.Entry
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    val zip = try {
                        ResumableZip(output.channel, data.getLong("offset"))
                    } catch (error: java.io.IOException) {
                        throw IllegalArgumentException("Choose a seekable local folder; destination changed or cannot resume", error)
                    }
                    for (index in done until count) {
                        currentCoroutineContext().ensureActive()
                        checkWork(task)
                        if (processed >= 16 || (processed > 0 && android.os.SystemClock.elapsedRealtime() - started > 120000)) {
                            schedule(id, append = true)
                            refreshIfUnlocked()
                            return@withContext true
                        }
                        val entryRow = checkNotNull(dao.get(entryId(index)))
                        val entry = open(entryRow, key)
                        val metadata = entry.getJSONObject("metadata")
                        val size = metadata.getLong("size")
                        val name = entry.getString("entry")
                        val time = entry.getLong("time")
                        val offset = zip.begin(name, size, time)
                        val crc = CRC32()
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(65536)
                        val reader = reader(task, metadata)
                        val base = task.progress
                        try {
                            var position = 0L
                            var last = 0L
                            while (position < size) {
                                currentCoroutineContext().ensureActive()
                                checkWork(task)
                                val n = reader.read(position, buffer, 0, minOf(buffer.size.toLong(), size - position).toInt())
                                check(n > 0)
                                zip.write(buffer, n)
                                crc.update(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                position += n
                                if (android.os.SystemClock.elapsedRealtime() - last > 1000) {
                                    dao.exportProgress(id, "EXPORTING", base + position, task.total)
                                    report(base + position, task.total, index, count)
                                    refreshIfUnlocked()
                                    last = android.os.SystemClock.elapsedRealtime()
                                }
                            }
                            check(MessageDigest.isEqual(digest.digest(), android.util.Base64.decode(metadata.getString("hash"), android.util.Base64.NO_WRAP))) { "Export integrity failed" }
                        } finally {
                            reader.close()
                            buffer.fill(0)
                        }
                        val complete = zip.commit(name, size, time, offset, crc.value)
                        task.progress = base + size
                        entry.put("checkpoint", checkpoint(complete))
                        seal(entryRow, entry, key)
                        data.put("done", index + 1).put("offset", complete.end()).put("completedBytes", task.progress)
                        seal(task, data, key)
                        database.runInTransaction {
                            if (dao.updateExportPayload(task.id, task.sealed, task.phase, task.progress, task.total) == 0) throw CancellationException("Export cancelled")
                            dao.put(entryRow)
                        }
                        processed++
                    }
                    val manifest = JSONObject().put("format", "TG Photos Cloud export v1").put("requestedAt", data.getLong("requestedAt")).put("requestedAtUtc", Instant.ofEpochMilli(data.getLong("requestedAt")).toString()).put("preparedAtUtc", Instant.now().toString()).put("options", data.getJSONObject("options"))
                        .put("count", count).put("bytes", task.total).put("coveredFrom", data.optLong("coveredFrom")).put("coveredUntil", data.optLong("coveredUntil")).put("unknownTimes", data.optInt("unknownTimes")).put("zipName", data.getString("name")).put("note", "Normal Cloud snapshot; pending backups and Private Vault excluded. Times are milliseconds since Unix epoch. Unknown historical backup times are 0; original bytes and embedded metadata are unchanged.")
                    fun detail(entryId: String): ByteArray {
                        val entry = open(checkNotNull(dao.get(entryId)), key)
                        val metadata = entry.getJSONObject("metadata")
                        return JSONObject().put("path", entry.getString("entry")).put("originalName", metadata.getString("name")).put("mime", metadata.getString("mime")).put("bytes", metadata.getLong("size")).put("sha256Base64", metadata.getString("hash")).put("captureTime", entry.getLong("time")).put("backupTime", metadata.optLong("backedUpAt")).put("deviceAddedAt", metadata.optLong("sourceAddedAt")).put("deviceModifiedAt", metadata.optLong("sourceModifiedAt")).put("album", metadata.optString("album")).put("mediaFacts", metadata.optJSONObject("facts") ?: JSONObject()).toString().toByteArray()
                    }
                    val header = (manifest.toString().dropLast(1) + ",\"files\":[").toByteArray()
                    var length = header.size.toLong() + 2 + (count - 1).coerceAtLeast(0)
                    for (index in 0 until count) {
                        currentCoroutineContext().ensureActive()
                        checkWork(task)
                        val bytes = detail(entryId(index))
                        length += bytes.size
                        bytes.fill(0)
                    }
                    val time = System.currentTimeMillis()
                    val offset = zip.begin("_TG_Photos/export.json", length, time)
                    val crc = CRC32()
                    fun write(bytes: ByteArray) {
                        try {
                            zip.write(bytes, bytes.size)
                            crc.update(bytes)
                        } finally {
                            bytes.fill(0)
                        }
                    }
                    write(header)
                    (0 until count).forEach { index ->
                        currentCoroutineContext().ensureActive()
                        checkWork(task)
                        if (index > 0) write(byteArrayOf(44))
                        write(detail(entryId(index)))
                    }
                    write(byteArrayOf(93, 125))
                    manifestEntry = zip.commit("_TG_Photos/export.json", length, time, offset, crc.value)
                    val central = Iterable { (0..count).asSequence().map { index -> if (index == count) manifestEntry else completedEntry(index) }.iterator() }
                    zip.finish(central, count.toLong() + 1)
                }
                task.phase = "VERIFYING"
                save(task, data, key)
                verifySaved(task, uri, count, key, ::entryId) { index -> if (index == count) manifestEntry else completedEntry(index) }
                control.withLock {
                    currentCoroutineContext().ensureActive()
                    checkWork(task)
                    val finalUri = storage.finish(data, uri)
                    data.put("createdUri", finalUri.toString()).put("completedAt", System.currentTimeMillis())
                    task.phase = "COMPLETE"
                    task.progress = task.total
                    save(task, data, key)
                }
                // Completed history needs no file keys. Keep only its sealed summary and saved ZIP URI.
                runCatching { completedNotification(task.id) }
                refreshIfUnlocked()
                runCatching { dao.removeExportEntries(task.account, task.vault, task.id) }
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (blocked: DataPolicyBlocked) {
                task.phase = blocked.reason
                data?.let { save(task, it, key) }
                refreshIfUnlocked()
                false
            } catch (error: app.televault.telegram.TelegramFailure) {
                task.phase = "WAITING_TELEGRAM"
                data?.let { save(task, it, key) }
                schedule(id, append = true, delaySeconds = error.safe.retryAfterSeconds().coerceAtLeast(30))
                refreshIfUnlocked()
                true
            } catch (error: Exception) {
                val retry = error is java.io.IOException && error !is java.io.FileNotFoundException && !AuthenticatedRecords.integrityFailure(error)
                task.phase = if (retry) "RETRYING" else "FAILED"
                if (error is EmptyCloudExport) data?.put("problem", "EMPTY_SELECTION")
                // Progress restarts at the last completed entry, never a partially written original.
                task.progress = data?.optLong("completedBytes") ?: 0L
                data?.let { save(task, it, key) }
                refreshIfUnlocked()
                !retry
            } finally {
                try {
                    if (dao.get(id)?.phase == "CANCELLED") {
                        data?.let {
                            runCatching {
                                storage.discardPending(it, id)
                                dao.removeExportEntries(task.account, task.vault, id)
                            }
                        }
                    }
                } finally {
                    release(key)
                }
            }
        }
    }
    private suspend fun verifySaved(task: CacheRow, uri: Uri, count: Int, key: ByteArray, entryId: (Int) -> String, expectedEntry: (Int) -> ResumableZip.Entry) {
        ZipInputStream(checkNotNull(app.contentResolver.openInputStream(uri))).use { zip ->
            val buffer = ByteArray(65536)
            try {
                for (index in 0..count) {
                    val expected = expectedEntry(index)
                    currentCoroutineContext().ensureActive()
                    checkWork(task)
                    val entry = checkNotNull(zip.nextEntry)
                    check(entry.name == expected.name())
                    val crc = CRC32()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkWork(task)
                        val n = zip.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        size += n
                    }
                    check(size == expected.size() && crc.value == expected.crc()) { "Saved ZIP verification failed" }
                    if (index < count) {
                        val metadata = open(checkNotNull(dao.get(entryId(index))), key).getJSONObject("metadata")
                        check(MessageDigest.isEqual(digest.digest(), android.util.Base64.decode(metadata.getString("hash"), android.util.Base64.NO_WRAP))) { "Saved original differs" }
                    }
                }
                check(zip.nextEntry == null)
            } finally {
                buffer.fill(0)
            }
        }
    }
    private suspend fun refreshIfUnlocked() {
        try {
            if (unlocked()) refresh()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { }
    }
    private fun completedNotification(id: String) {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("tgdrive-exports", "Cloud exports", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(app, id.hashCode(), Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openCloudExports", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(id.hashCode(), AppNotifications.builder(app, "tgdrive-exports").setContentText("Cloud ZIP saved and verified. Open Export history for details.").setContentIntent(intent).setAutoCancel(true).build())
        } catch (_: SecurityException) { /* History and completion popup remain available without notification permission. */ }
    }
}

class CloudExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("export") ?: return Result.failure()
        return try {
            if (inputData.getBoolean("cleanupCancelled", false)) {
                (applicationContext as TeleVaultApplication).drive.cloudExports.cleanupCancelled(id)
                return Result.success()
            }
            setForeground(transferForeground(applicationContext, id.hashCode(), "Preparing Cloud ZIP export"))
            val exports = (applicationContext as TeleVaultApplication).drive.cloudExports
            val done = exports.execute(id) { bytes, total, count, all ->
                val percent = if (total > 0) (bytes.toDouble() * 100 / total).toInt().coerceIn(0, 100) else 0
                setForeground(transferForeground(applicationContext, id.hashCode(), "Cloud ZIP · $percent% · $count/$all files"))
            }
            if (done) Result.success() else Result.retry()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
