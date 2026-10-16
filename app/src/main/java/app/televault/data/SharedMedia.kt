package app.televault.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.work.*
import app.televault.domain.SharedCopyLifetime
import java.io.File
import java.util.concurrent.TimeUnit

/** Only user-approved external-share copies, never pending upload sources. */
object SharedMedia {
    fun directory(context: Context): File = File(context.cacheDir, "shared/export").apply { mkdirs() }
    fun prune(context: Context) {
        val now = System.currentTimeMillis()
        directory(context).listFiles().orEmpty().filter { it.isFile && SharedCopyLifetime.expired(it.lastModified(), now) }.forEach { file ->
            runCatching { context.revokeUriPermission(FileProvider.getUriForFile(context, "${context.packageName}.files", file), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            file.delete()
        }
        // Older releases wrote UUID.extension shares in shared/. Cloud upload staging is UUID-name,
        // so this exact legacy pattern cannot remove those queued sources.
        val legacy = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[a-zA-Z0-9]{1,12}")
        File(context.cacheDir, "shared").listFiles().orEmpty().filter { it.isFile && legacy.matches(it.name) && SharedCopyLifetime.expired(it.lastModified(), now) }.forEach { it.delete() }
    }
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniqueWork("share-cleanup-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SharedMediaCleanupWorker>().build())
        val periodic = PeriodicWorkRequestBuilder<SharedMediaCleanupWorker>(1, TimeUnit.HOURS).build()
        work.enqueueUniquePeriodicWork("share-cleanup", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }
}

class SharedMediaCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        SharedMedia.prune(applicationContext)
        MediaStorage(applicationContext).pruneTemporary()
        val now = System.currentTimeMillis()
        File(applicationContext.noBackupFilesDir, "photo-cipher-cache").listFiles()?.filter { it.isFile && (now - it.lastModified() >= app.televault.crypto.CipherRangeCache.TTL || it.lastModified() > now) }?.forEach { it.delete() }
        // Only encrypted staging for terminal jobs/orphans. Active/retryable sources are retained.
        val database = androidx.room.Room.databaseBuilder(applicationContext, DriveDatabase::class.java, File(applicationContext.noBackupFilesDir, "drive-cache.sqlite").absolutePath).build()
        try {
            val dao = database.cache()
            File(applicationContext.noBackupFilesDir, "drive-cache").listFiles().orEmpty().forEach { file ->
                if (file.extension != "bin") return@forEach
                val id = file.nameWithoutExtension.removeSuffix("-part")
                if (runCatching { java.util.UUID.fromString(id) }.isFailure) return@forEach
                val job = dao.get(id)
                if (job?.phase in setOf("COMPLETE", "CANCELLED") || job == null && now - file.lastModified() >= app.televault.crypto.CipherRangeCache.TTL) file.delete()
            }
            dao.pruneCompletedJobs()
        } finally {
            database.close()
        }
        Result.success()
    }
}
