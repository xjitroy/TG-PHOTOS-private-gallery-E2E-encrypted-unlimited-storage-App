package app.televault.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.televault.TeleVaultApplication
import kotlinx.coroutines.CancellationException

/** WorkManager resumes a partially restored encrypted catalog after Activity/process recreation. */
class CloudRestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val account = inputData.getLong("account", 0L)
        val vault = inputData.getString("vault") ?: return Result.failure()
        if (account == 0L) return Result.failure()
        return try {
            setForeground(transferForeground(applicationContext, vault.hashCode(), "Restoring encrypted library and thumbnails"))
            (applicationContext as TeleVaultApplication).drive.restoreCloudInBackground(account, vault)
            Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
