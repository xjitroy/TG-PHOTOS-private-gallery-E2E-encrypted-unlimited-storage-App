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
    val status = kotlinx.coroutines.flow.MutableStateFlow("")
    private const val NAME = "normal-media-intelligence"

    @Volatile var runningLane: String? = null
    fun ensure(context: Context, rearm: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("$NAME-periodic")
        val trigger = Constraints.Builder().setRequiresStorageNotLow(true)
            .addContentUriTrigger(android.provider.MediaStore.Files.getContentUri("external"), true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS).setTriggerContentMaxDelay(30, TimeUnit.SECONDS).build()
        val work = OneTimeWorkRequestBuilder<MediaIntelligenceWorker>().setInputData(workDataOf("watch" to true))
            .setConstraints(trigger).addTag(NAME).build()
        manager.enqueueUniqueWork("$NAME-watch", if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, work)
    }
    private fun constraints() = Constraints.Builder().setRequiresStorageNotLow(true)
    fun whenCharging(context: Context) {
        val work = OneTimeWorkRequestBuilder<MediaIntelligenceWorker>().setInputData(workDataOf("lane" to "$NAME-charge"))
            .setConstraints(constraints().setRequiresCharging(true).build()).addTag(NAME).build()
        WorkManager.getInstance(context).enqueueUniqueWork("$NAME-charge", ExistingWorkPolicy.KEEP, work)
    }
    fun whenUnmetered(context: Context) {
        val lane = "$NAME-wifi"
        val work = OneTimeWorkRequestBuilder<MediaIntelligenceWorker>().setInputData(workDataOf("lane" to lane))
            .setConstraints(constraints().setRequiredNetworkType(NetworkType.UNMETERED).build()).addTag(NAME).build()
        WorkManager.getInstance(context).enqueueUniqueWork(lane, ExistingWorkPolicy.KEEP, work)
    }
    fun request(context: Context) {
        // At most one active batch and one coalesced follow-up; do not append one job per photo.
        val lane = if (runningLane == NAME) "$NAME-next" else NAME
        val work = OneTimeWorkRequestBuilder<MediaIntelligenceWorker>().setInputData(workDataOf("lane" to lane))
            .setConstraints(constraints().apply { if (!BackgroundBattery.analysis(context)) setRequiresCharging(true) }.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(NAME).build()
        WorkManager.getInstance(context).enqueueUniqueWork(lane, ExistingWorkPolicy.KEEP, work)
    }
}

class MediaIntelligenceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (inputData.getBoolean("watch", false)) {
            MediaIntelligenceScheduling.request(applicationContext)
            MediaIntelligenceScheduling.ensure(applicationContext, rearm = true)
            return Result.success()
        }
        return MediaIntelligenceScheduling.gate.withLock {
            MediaIntelligenceScheduling.runningLane = inputData.getString("lane")
            try {
                runBatch()
            } finally {
                MediaIntelligenceScheduling.runningLane = null
            }
        }
    }
    private suspend fun runBatch(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as TeleVaultApplication
        val drive = app.drive
        if (drive.state.value.stage != VaultStage.READY) return@withContext Result.success()
        if (!drive.state.value.indexLoaded) {
            MediaIntelligenceScheduling.status.value = "Waiting for the encrypted library index…"
            return@withContext Result.retry()
        }
        if (!BackgroundBattery.analysis(applicationContext)) {
            MediaIntelligenceScheduling.status.value = "Automatic analysis paused for battery protection; resumes while charging."
            MediaIntelligenceScheduling.whenCharging(applicationContext)
            return@withContext Result.success()
        }
        if (app.intelligencePaused || !app.ocrModels.backgroundAllowed(false)) {
            MediaIntelligenceScheduling.status.value = "Analysis pauses during viewing, Private Vault or battery saver; it will retry."
            return@withContext Result.retry()
        }
        val epoch = drive.playbackEpoch
        val binding = drive.privateVaultBinding()
        val people = drive.people
        val ocr = drive.ocr
        val scenes = drive.scenes
        fun current() = BackgroundAnalysisPolicy.sessionAllowed(drive.state.value.stage == VaultStage.READY, epoch, drive.playbackEpoch, app.intelligencePaused)
        // Subscribe only while work is alive; LocalMediaRepository restores its encrypted device
        // index and observes new photos. No new Android storage permission is requested here.
        val observer = launch { drive.localMedia.changes.collect { } }
        try {
            drive.privateAi.activate()
            people.activate(syncRemote = false)
            ocr.activate()
            scenes.activate()
            if (!people.state.value.enabled && !ocr.state.value.enabled && !scenes.state.value.enabled) return@withContext Result.success()
            setForeground(transferForeground(applicationContext, 71908, "Indexing photos privately · on-device"))
            val retryNeeded = withTimeout(8 * 60 * 1000L) {
                delay(500)
                withTimeoutOrNull(30_000) { drive.localMedia.indexReady.first { it } }
                withTimeoutOrNull(30_000) { drive.localMedia.scanningDocuments.first { !it } }
                MediaIntelligenceScheduling.status.value = "Preparing on-device face, text and subject analysis…"
                val enabled = listOf(people.state.value.enabled, ocr.state.value.enabled, scenes.state.value.enabled)
                val prepared = prepareIntelligence(
                    enabled,
                    prepare = listOf(
                        { people.prepareAnalysis() },
                        { ocr.models.prepareInstalled() },
                        { app.memoryModels.prepareInstalled() }
                    )
                )
                var faceReady = prepared[0]
                var textReady = prepared[1]
                var sceneReady = prepared[2]
                var nativeUnavailable = false
                val waiting = enabled.indices.any { enabled[it] && !prepared[it] }
                if (enabled[0] && !faceReady) app.faceModels.install()
                if (enabled[1] && !textReady) app.ocrModels.install()
                if (enabled[2] && !sceneReady) app.memoryModels.install()
                var incomplete = false
                var cloudWaiting = false
                if (!faceReady && !textReady && !sceneReady) {
                    MediaIntelligenceScheduling.status.value = "Waiting for verified models or encrypted People references; automatic retry is scheduled."
                    return@withTimeout waiting
                }
                suspend fun scan(id: String, stamp: String, mime: String, original: android.net.Uri?, preview: suspend () -> android.graphics.Bitmap?) {
                    currentCoroutineContext().ensureActive()
                    if (!BackgroundBattery.analysis(applicationContext)) throw BatteryPaused()
                    if (!current() || !app.ocrModels.backgroundAllowed(false) || drive.privateVaultBinding() != binding) throw AnalysisPaused()
                    if (!drive.normalIntelligenceMedia(id)) return
                    val photo = mime.startsWith("image/")
                    val video = mime.startsWith("video/")
                    if (!photo && !video) return
                    if (original == null && !app.ocrModels.backgroundAllowed()) cloudWaiting = true
                    drive.photoPriority.await("background-intelligence")
                    if (faceReady && people.state.value.enabled) {
                        try {
                            val changed = people.analyze(id, stamp, ownedSource = photo) {
                                if (photo) drive.analysisPhoto(id, app.ocrModels.backgroundAllowed()) else preview()
                            }
                            if (changed) delay(120)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: LinkageError) {
                            faceReady = false
                            nativeUnavailable = true
                            incomplete = true
                        } catch (_: Exception) {
                            incomplete = true
                        }
                    }
                    if (photo && textReady && ocr.state.value.enabled && app.ocrModels.backgroundAllowed(original == null)) {
                        try {
                            val before = ocr.state.value.processed
                            ocr.analyze(id, stamp, { original ?: drive.privateMediaUri(id) })
                            if (ocr.state.value.processed > before) delay(250)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: LinkageError) {
                            textReady = false
                            nativeUnavailable = true
                            incomplete = true
                        } catch (_: Exception) {
                            incomplete = true
                        }
                    }
                    if ((photo || video) && sceneReady && scenes.state.value.enabled && !scenes.state.value.capacityReached && app.ocrModels.backgroundAllowed(original == null)) {
                        try {
                            val changed = scenes.analyze(id, stamp, preview = if (video) preview else null) { original ?: drive.privateMediaUri(id) }
                            if (changed) delay(120)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: LinkageError) {
                            sceneReady = false
                            nativeUnavailable = true
                            incomplete = true
                        } catch (_: Exception) {
                            incomplete = true
                        }
                    }
                }

                // One per-photo pipeline; refreshed candidates let new arrivals interrupt the old backlog.
                data class Candidate(val id: String, val stamp: String, val mime: String, val time: Long, val local: LocalMedia? = null)
                val visited = hashSetOf<Pair<String, String>>()
                var locals: List<LocalMedia>? = null
                var clouds: List<DriveFile>? = null
                var candidates = emptyList<Candidate>()
                var cursor = 0
                var checked = 0
                while (true) {
                    val nextLocal = drive.localMedia.changes.value
                    val nextCloud = drive.state.value.files
                    if (nextLocal !== locals || nextCloud !== clouds) {
                        locals = nextLocal
                        clouds = nextCloud
                        val links = drive.state.value.backedDeviceFiles
                        candidates = (
                            nextLocal.asSequence().filter { !it.trashed && !it.galleryHidden && links[it.fingerprint] == null }.map { Candidate(it.uri.toString(), it.fingerprint, it.mime, maxOf(it.date, it.addedAt), it) } +
                                nextCloud.asSequence().filter { it.trashedAt == 0L }.map { Candidate(it.id, it.hash, it.mime, maxOf(it.date, it.backedUpAt)) }
                            )
                            .filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }.distinctBy { it.id }
                            .sortedWith(compareByDescending<Candidate> { it.time }.thenBy { it.id }).toList()
                        cursor = 0
                    }
                    while (cursor < candidates.size && (candidates[cursor].id to candidates[cursor].stamp) in visited) cursor++
                    val item = candidates.getOrNull(cursor++) ?: break
                    visited += item.id to item.stamp
                    checked++
                    if (checked == 1 || checked % 10 == 0) MediaIntelligenceScheduling.status.value = "Checking media privately · $checked / ${candidates.size}"
                    val device = item.local
                    scan(item.id, item.stamp, item.mime, device?.uri ?: drive.preferredDeviceSource(item.id)) {
                        if (device != null) drive.localMedia.thumbnail(device, 768) else drive.thumbnail(item.id, 384)
                    }
                }
                people.finishScan()
                scenes.finish()
                if (people.state.value.pendingSync && app.ocrModels.backgroundAllowed()) {
                    withTimeoutOrNull(30_000) { runCatching { people.sync() } }
                }
                MediaIntelligenceScheduling.status.value = when {
                    nativeUnavailable -> "An analysis engine is unavailable on this phone. Other available analysis continues; originals and saved results are kept."
                    waiting -> "Available engines finished; waiting for models or encrypted People restore. Retrying automatically."
                    incomplete -> "Some media could not be read. Successful results are kept; retry from these controls."
                    cloudWaiting -> "Phone media checked. Cloud-only originals resume on Wi-Fi; existing results are kept."
                    else -> "Available media checked. New photos are analysed automatically."
                }
                if (cloudWaiting) MediaIntelligenceScheduling.whenUnmetered(applicationContext)
                waiting || incomplete && runAttemptCount < 3
            }
            if (retryNeeded) Result.retry() else Result.success()
        } catch (_: BatteryPaused) {
            MediaIntelligenceScheduling.whenCharging(applicationContext)
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
                runCatching { scenes.finish() }
                runCatching { people.pauseEngine() }
                runCatching { ocr.pauseEngine() }
            }
        }
    }
    private class BatteryPaused : Exception()
    private class AnalysisPaused : Exception()
}
