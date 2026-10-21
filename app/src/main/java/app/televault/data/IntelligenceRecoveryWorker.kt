package app.televault.data

import android.content.Context
import androidx.work.*
import app.televault.TeleVaultApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

/** Encrypted recovery networking never occupies the per-photo OCR/face/subject pipeline. */
internal object IntelligenceRecoveryScheduling {
    fun request(context: Context) {
        val work = OneTimeWorkRequestBuilder<IntelligenceRecoveryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("intelligence-recovery-v1", ExistingWorkPolicy.KEEP, work)
    }
}
class IntelligenceRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as TeleVaultApplication
        val drive = app.drive
        if (drive.state.value.stage != VaultStage.READY || app.intelligencePaused) return@withContext Result.success()
        if (MediaIntelligenceScheduling.runningLane != null) return@withContext Result.retry()
        try {
            if (!drive.privateAi.analysisReady()) return@withContext Result.retry()
            val binding = drive.privateVaultBinding()
            val pending = drive.analysisResults.pendingRecoveryHashes()
            if (pending.isEmpty() && !drive.people.state.value.pendingSync) return@withContext Result.success()
            setForeground(transferForeground(applicationContext, 71911, "Saving encrypted analysis recovery"))
            drive.analysisResults.beginBatch()
            withTimeoutOrNull(90_000) {
                for (hash in pending.take(24)) {
                    currentCoroutineContext().ensureActive()
                    if (MediaIntelligenceScheduling.runningLane != null || app.intelligencePaused || drive.privateVaultBinding() != binding) break
                    try {
                        withTimeout(15_000) { drive.analysisResults.sync(hash) }
                    } catch (_: TimeoutCancellationException) {
                        break
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // One unreadable result cannot prevent later valid records from syncing.
                    }
                }
            }
            if (MediaIntelligenceScheduling.runningLane == null && drive.people.state.value.pendingSync) {
                withTimeoutOrNull(30_000) { drive.people.sync() }
            }
            if (drive.analysisResults.syncPending || drive.people.state.value.pendingSync) Result.retry() else Result.success()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
