package app.televault.data

import android.content.Context
import androidx.work.*
import app.televault.TeleVaultApplication
import app.televault.domain.BackgroundAnalysisPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

/** No key is placed in WorkManager. A locked vault always waits for an explicit unlock. */
internal object MediaIntelligenceScheduling {
    val gate = kotlinx.coroutines.sync.Mutex()
    private const val NAME = "normal-media-intelligence"
    fun ensure(context: Context) {
        val work = PeriodicWorkRequestBuilder<MediaIntelligenceWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints()).addTag(NAME).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("$NAME-periodic", ExistingPeriodicWorkPolicy.KEEP, work)
    }
    private fun constraints() = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
    fun request(context: Context) {
        val work = OneTimeWorkRequestBuilder<MediaIntelligenceWorker>().setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(NAME).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, work)
    }
}

class MediaIntelligenceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = MediaIntelligenceScheduling.gate.withLock { runBatch() }
    private suspend fun runBatch(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as TeleVaultApplication
        val drive = app.drive
        if (drive.state.value.stage != VaultStage.READY || !drive.state.value.indexLoaded) return@withContext Result.success()
        if (app.intelligencePaused || !app.ocrModels.backgroundAllowed(false)) return@withContext Result.retry()
        val epoch = drive.playbackEpoch
        val binding = drive.privateVaultBinding()
        val people = drive.people
        val ocr = drive.ocr
        fun current() = BackgroundAnalysisPolicy.sessionAllowed(drive.state.value.stage == VaultStage.READY, epoch, drive.playbackEpoch, app.intelligencePaused)
        // Subscribe only while work is alive; LocalMediaRepository restores its encrypted device
        // index and observes new photos. No new Android storage permission is requested here.
        val observer = launch { drive.localMedia.changes.collect { } }
        try {
            drive.privateAi.activate()
            people.activate(syncRemote = false)
            ocr.activate()
            if (!people.state.value.enabled && !ocr.state.value.enabled) return@withContext Result.success()
            setForeground(transferForeground(applicationContext, 71908, "Indexing photos privately · on-device"))
            withTimeout(8 * 60 * 1000L) {
                withTimeoutOrNull(30_000) { drive.localMedia.indexReady.first { it } }
                val faceReady = people.state.value.enabled && try {
                    people.prepareAnalysis()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    false
                }
                val textReady = ocr.state.value.enabled && try {
                    ocr.models.prepare(false)
                    true
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    false
                }
                if (!faceReady && !textReady) return@withTimeout
                val local = drive.localMedia.changes.value.filter { !it.trashed && !it.galleryHidden }.distinctBy { it.uri }
                val cloud = drive.state.value.files.filter { it.trashedAt == 0L }.sortedByDescending { it.date }
                val links = drive.state.value.backedDeviceFiles
                suspend fun scan(id: String, stamp: String, mime: String, original: android.net.Uri?, preview: suspend () -> android.graphics.Bitmap?) {
                    currentCoroutineContext().ensureActive()
                    if (!current() || !app.ocrModels.backgroundAllowed(false) || drive.privateVaultBinding() != binding) throw AnalysisPaused()
                    if (!drive.normalIntelligenceMedia(id)) return
                    val photo = mime.startsWith("image/")
                    val video = mime.startsWith("video/")
                    if (!photo && !video) return
                    drive.photoPriority.await("background-intelligence")
                    if (faceReady && people.state.value.enabled) {
                        try {
                            val changed = people.analyze(id, stamp, ownedSource = photo) {
                                if (photo) drive.analysisPhoto(id, app.ocrModels.backgroundAllowed()) else preview()
                            }
                            if (changed) delay(120)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) { /* Skip an inaccessible/corrupt item, then continue. */ }
                    }
                    if (photo && textReady && ocr.state.value.enabled && app.ocrModels.backgroundAllowed(original == null)) {
                        try {
                            val before = ocr.state.value.processed
                            ocr.analyze(id, stamp, { original ?: drive.privateMediaUri(id) })
                            if (ocr.state.value.processed > before) delay(250)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) { /* Per-photo isolation; successful records stay encrypted. */ }
                    }
                }
                // Device originals are available offline and precede speculative cloud downloads.
                for (file in local.filter { links[it.fingerprint] == null }.sortedByDescending { it.date }) scan(file.uri.toString(), file.fingerprint, file.mime, file.uri) { drive.localMedia.thumbnail(file, 768) }
                for (file in cloud) scan(file.id, file.hash, file.mime, drive.preferredDeviceSource(file.id)) { drive.thumbnail(file.id, 384) }
                people.finishScan()
                if (people.state.value.pendingSync && app.ocrModels.backgroundAllowed()) runCatching { people.sync() }
            }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            Result.retry()
        } catch (_: AnalysisPaused) {
            Result.retry()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: OutOfMemoryError) {
            Result.retry()
        } catch (_: LinkageError) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            observer.cancel()
            withContext(NonCancellable) {
                runCatching { people.finishScan() }
                runCatching { people.pauseEngine() }
                runCatching { ocr.pauseEngine() }
            }
        }
    }
    private class AnalysisPaused : Exception()
}
