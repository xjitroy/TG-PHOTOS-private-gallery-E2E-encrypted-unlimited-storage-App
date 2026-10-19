package app.televault.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.televault.TeleVaultApplication
import kotlinx.coroutines.CancellationException

class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("job") ?: return Result.failure()
        try {
            val drive = (applicationContext as TeleVaultApplication).drive
            if (drive.redirectQueuedUpload(id)) return Result.success()
            setForeground(transferForeground(applicationContext, id.hashCode()))
            return if (drive.executeJob(id)) {
                Result.success()
            } else if (drive.waitingForOwner(id)) {
                Result.success()
            } else if (!inputData.getBoolean("wifiContinuation", false) && drive.waitingForWifi(id)) {
                drive.continueOnWifi(id)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        }
    }
}

class FolderBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("folder") ?: return Result.failure()
        return try {
            (applicationContext as TeleVaultApplication).drive.scanBackup(id)
            MediaIntelligenceScheduling.request(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

class TrashRetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext as TeleVaultApplication).drive.expireTrash()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }
}

class CameraChangeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("folder") ?: return Result.failure()
        val drive = (applicationContext as TeleVaultApplication).drive
        return try {
            drive.scanBackup(id)
            MediaIntelligenceScheduling.request(applicationContext)
            drive.watchCamera(id, rearm = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal fun transferForeground(context: Context, id: Int, text: String = "Transferring encrypted files"): ForegroundInfo {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(NotificationChannel("tgdrive-transfers", "TG Photos transfers", NotificationManager.IMPORTANCE_LOW))
    val notification = AppNotifications.builder(context, "tgdrive-transfers").setContentText(text).setOngoing(true).build()
    return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, notification)
}
