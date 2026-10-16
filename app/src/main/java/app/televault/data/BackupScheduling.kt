package app.televault.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import app.televault.TeleVaultApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Metadata-only recovery; it never reads media on the application main thread. */
internal object BackupScheduling {
    fun request(context: Context) {
        val work = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<BackupRecoveryWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("tgdrive").setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        work.enqueueUniqueWork("backup-schedule-recovery", ExistingWorkPolicy.KEEP, request)
        val periodic = PeriodicWorkRequestBuilder<BackupRecoveryWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("tgdrive").build()
        work.enqueueUniquePeriodicWork("backup-schedule-recovery-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }
}

class BackupEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) BackupScheduling.request(context)
    }
}

class BackupRecoveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as TeleVaultApplication).drive.restoreBackupSchedules()
        Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        Result.retry()
    }
}

class UploadQueueWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo() = transferForeground(applicationContext, id.hashCode())
    override suspend fun doWork(): Result {
        val seed = inputData.getString("job") ?: return Result.failure()
        val drive by lazy { (applicationContext as TeleVaultApplication).drive }
        suspend fun defer(): Result {
            drive.deferUploads(seed, inputData.getLong("queueDelay", 0))
            return Result.success()
        }
        return try {
            setForeground(getForegroundInfo())
            if (drive.drainUploads(seed)) Result.success() else defer()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Backoff has its own durable lane; a new owner/content-change wake stays immediate.
            try {
                defer()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Result.retry()
            }
        }
    }
}
