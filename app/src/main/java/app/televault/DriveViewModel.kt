package app.televault

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.televault.data.DriveRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DriveViewModel(private val repository: DriveRepository) : ViewModel() {
    internal val ocr = repository.ocr
    internal val people = repository.people
    val state = repository.state
    val cloudRestore = repository.cloudRestore
    val vaultChoices = repository.vaultChoices
    val selectedVaultId: String get() = repository.selectedVaultId
    fun newVaultName() = repository.newVaultName()
    fun beginNewVault() = action { repository.beginNewVault() }
    fun selectVault(id: String) = action { repository.selectVault(id) }
    fun findOtherVaults() = action { repository.findOtherVaults() }
    fun beginPhotoLoad(id: String): Long = repository.photoPriority.begin(id)
    fun finishPhotoLoad(token: Long) = repository.photoPriority.ready(token)
    suspend fun awaitPhotoLoad(id: String) = repository.photoPriority.await(id)

    private val hiddenTransferPhases = setOf("WAITING", "COMPLETE", "CANCELLED")

    // Progress collectors live in small transfer components, not the album grid/player tree.
    val contentState = state.map { value -> value.copy(indexLoading = value.indexLoading && !value.indexLoaded, transfers = value.transfers.filter { it.phase !in hiddenTransferPhases && !app.televault.domain.ActivityProgress.active(it.phase) }.map { it.copy(progress = 0, total = 0) }, syncProgress = null, deviceCopyProgress = value.deviceCopyProgress?.copy(completed = 0, total = 0)) }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, state.value)
    internal val backupBadgeTimes = state.map { it.files to it.backedDeviceFiles }.distinctUntilChanged().map { (files, links) ->
        val times = files.associate { it.id to it.backedUpAt }
        times + links.mapValues { times[it.value] ?: 0L }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val playerState = state.map { app.televault.data.DriveSnapshot(stage = it.stage, localFavorites = it.localFavorites, privateProfile = it.privateProfile) }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, state.value)
    internal val transferOverview = state.map { it.transfers }.distinctUntilChanged().map { app.televault.data.transferOverviews(it) }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    internal val backupOverview = state.map { if (it.stage == app.televault.data.VaultStage.READY) it.transfers.filter { job -> !job.download } else emptyList() }.distinctUntilChanged().runningFold(app.televault.data.BackupOverview()) { previous, jobs -> app.televault.data.backupOverview(jobs, previous) }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, app.televault.data.BackupOverview())
    private var previewJob: Job? = null
    private val operations = mutableListOf<Job>()
    init {
        viewModelScope.launch {
            state.map { it.stage }.distinctUntilChanged().collectLatest { stage ->
                if (stage == app.televault.data.VaultStage.READY) {
                    while (isActive) {
                        delay(60_000)
                        try {
                            repository.retryPrivateIntelligenceCleanup()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) { }
                    }
                }
            }
        }
        viewModelScope.launch {
            state.map { it.stage to it.vaultId }.distinctUntilChanged().collectLatest { (stage, _) ->
                if (stage == app.televault.data.VaultStage.READY) {
                    try {
                        repository.privateAi.activate()
                        try {
                            repository.privateAi.sync()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) { }
                        people.activate()
                        ocr.activate()
                        people.cleanPrivateSources()
                        ocr.cleanPrivateSources()
                        try {
                            repository.retryPrivateIntelligenceCleanup()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) { }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) { }
                } else {
                    peopleIndex?.cancel()
                    people.lock()
                    ocr.lock()
                }
            }
        }
        viewModelScope.launch {
            state.map { it.transfers.any { job -> job.running } || it.busy }.distinctUntilChanged().collectLatest { active ->
                while (isActive) {
                    try {
                        repository.pollTransfers()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { }
                    delay(if (active) 1000 else 15000)
                }
            }
        }
    }

    @Volatile private var intelligenceActive = true
    internal fun intelligenceActive(value: Boolean) {
        intelligenceActive = value
        if (!value) {
            peopleIndex?.cancel()
            textIndex?.cancel()
            viewModelScope.launch(Dispatchers.IO) {
                people.pauseEngine()
                ocr.pauseEngine()
            }
        }
    }
    private var peopleIndex: Job? = null
    internal fun indexPeople(cloud: List<app.televault.data.DriveFile>, local: List<app.televault.data.LocalMedia>) {
        if (!intelligenceActive)return
        peopleIndex?.cancel()
        peopleIndex = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!people.state.value.enabled) return@launch
                val epoch = playbackEpoch
                val byCloud = state.value.backedDeviceFiles
                val localById = local.associateBy { it.uri.toString() }
                val originals = local.mapNotNull { item -> byCloud[item.fingerprint]?.let { it to item } }.toMap()
                for (file in cloud.filter { it.trashedAt == 0L && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) }.sortedByDescending { it.date }) {
                    if (epoch != playbackEpoch) break
                    repository.photoPriority.await("people-index")
                    val original = originals[file.id]
                    val changed = people.analyze(file.id, file.hash) {
                        if (original != null) localMedia.thumbnail(original, 768) else repository.thumbnail(file.id, 384)
                    }
                    if (changed) delay(160)
                }
                for (file in localById.values.filter { !it.trashed && !it.galleryHidden && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) && byCloud[it.fingerprint] == null }.sortedByDescending { it.date }) {
                    if (epoch != playbackEpoch) break
                    repository.photoPriority.await("people-index")
                    val changed = people.analyze(file.uri.toString(), file.fingerprint) { localMedia.thumbnail(file, 768) }
                    if (changed) delay(160)
                }
                people.sync()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: LinkageError) {
                repository.notice("People recognition is unavailable on this device. Your media is unchanged.")
            } catch (_: OutOfMemoryError) {
                people.pauseEngine()
                repository.notice("People indexing paused to free memory. Your media is unchanged.")
            } catch (_: Exception) {
                repository.notice("People indexing paused. Open Albums → People to sync or retry. Your media is unchanged.")
            }
        }
    }
    internal fun peopleAction(block: suspend () -> Unit) = action(block)
    private var textIndex: Job? = null
    internal fun indexText(cloud: List<app.televault.data.DriveFile>, local: List<app.televault.data.LocalMedia>, restart: Boolean = true) {
        if (!intelligenceActive)return
        if (!restart && textIndex?.isActive == true)return
        textIndex?.cancel()
        textIndex = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!ocr.state.value.enabled)return@launch
                val epoch = playbackEpoch
                for (file in cloud.filter { it.trashedAt == 0L && it.mime.startsWith("image/") }.sortedByDescending { it.date }) {
                    if (epoch != playbackEpoch)break
                    if (!ocr.models.backgroundAllowed()) continue
                    repository.photoPriority.await("text-index")
                    val before = ocr.state.value.indexed
                    ocr.analyze(file.id, file.hash, { repository.preferredDeviceSource(file.id) ?: repository.privateMediaUri(file.id) })
                    if (ocr.state.value.indexed > before)delay(500)
                }
                for (file in local.filter { !it.trashed && !it.galleryHidden && it.mime.startsWith("image/") && state.value.backedDeviceFiles[it.fingerprint] == null }.sortedByDescending { it.date }) {
                    if (epoch != playbackEpoch)break
                    repository.photoPriority.await("text-index")
                    val before = ocr.state.value.indexed
                    ocr.analyze(file.uri.toString(), file.fingerprint, { file.uri })
                    if (ocr.state.value.indexed > before)delay(500)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: LinkageError) {
                repository.notice("On-device OCR is unavailable on this device. Your photos are unchanged.")
            } catch (_: OutOfMemoryError) {
                ocr.pauseEngine()
                repository.notice("Text indexing paused to free memory. Your photos are unchanged.")
            } catch (_: Exception) {
                repository.notice("Text indexing paused. Open Search to check language downloads or retry. Original photos are unchanged.")
            }
        }
    }
    internal suspend fun photoText(item: app.televault.ui.MediaEntry): app.televault.ocr.OcrText {
        check(item.mime.startsWith("image/") && (item.cloud == null || item.cloud.trashedAt == 0L) && item.local?.trashed != true)
        return ocr.analyze(item.id, item.local?.fingerprint ?: item.cloud!!.hash, { item.local?.uri ?: repository.preferredDeviceSource(item.id) ?: repository.privateMediaUri(item.id) }, thorough = true)
    }
    internal val mediaStorage = repository.mediaStorage.usage
    fun refreshMediaStorage() = action { repository.mediaStorage.refresh() }
    fun mediaCacheQuota(bytes: Long) = action { repository.mediaStorage.setQuota(bytes) }
    internal val offlineDownloads = repository.offlineDownloads
    internal val offlineIds = offlineDownloads.map { it.ids }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    private var offlineJob: Job? = null
    fun cancelOfflineDownload() {
        offlineJob?.cancel()
    }
    fun pinOffline(ids: Set<String>): Job? {
        if (offlineJob?.isActive == true) {
            report("An encrypted offline download is already running.")
            return null
        }
        return action {
            try {
                repository.pinOffline(ids)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                report("Offline download incomplete. Check the quota, free storage, Data Saver and connection. Cloud files are kept.")
            }
        }.also { offlineJob = it }
    }
    fun removeOffline(ids: Set<String>) = action { repository.removeOffline(ids) }
    fun offlineQuota(bytes: Long) = action { repository.setOfflineQuota(bytes) }
    internal val cloudExports = repository.cloudExports
    fun refreshCloudExports() = action { cloudExports.refresh() }
    internal fun exportCloud(options: app.televault.data.CloudExportOptions, destination: Uri? = null) = action {
        check(state.value.indexLoaded && !state.value.indexNeedsRepair)
        cloudExports.start(options, destination)
    }
    internal fun cancelCloudExport(id: String) = action { cloudExports.cancel(id) }
    internal fun retryCloudExport(id: String) = action { cloudExports.retry(id) }
    internal fun acknowledgeCloudExport(id: String) = action { cloudExports.acknowledge(id) }
    val preferences = repository.preferences
    val guidanceScope: String get() = repository.guidanceScope
    val localMedia = repository.localMedia
    internal val deviceFileTrash = repository.deviceFileTrash
    val photoPreviews = repository.photoPreviews
    internal val documentPreviews = repository.documentPreviews
    fun activeTelegramSessions(finished: (List<app.televault.telegram.TelegramSessionInfo>?) -> Unit) = action {
        try {
            finished(repository.activeTelegramSessions())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            finished(null)
        }
    }
    fun terminateOtherTelegramSessions(finished: (Boolean) -> Unit) = action {
        try {
            repository.terminateOtherTelegramSessions()
            finished(true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repository.notice("Telegram did not allow closing other sessions. A recently created session may need to wait; try again later or use Telegram's Devices settings.")
            finished(false)
        }
    }
    suspend fun ensureOwnerProfile() = repository.ensureOwnerProfile()
    suspend fun saveProfile(profile: app.televault.data.PrivateProfile) = repository.saveProfile(profile)
    fun quickUnlock() = action { repository.quickUnlock() }
    fun shareMedia(source: Uri?, cloudId: String?, name: String, mime: String, size: Long) = repository.shareMedia(source, cloudId, name, mime, size)
    suspend fun openMedia(id: String): Uri = repository.privateMediaUri(id)
    suspend fun preferredDeviceSource(id: String): Uri? = repository.preferredDeviceSource(id)
    suspend fun verifiedDeviceCopies(ids: Set<String>? = null, sources: Set<String>? = null) = repository.verifiedDeviceCopies(ids, sources)
    fun backupAndTrash(items: List<app.televault.data.LocalMedia>, origin: String) = action { repository.backupAndTrash(items, origin) }
    fun finishDeviceCleanup(ids: Set<String>) = action { repository.finishDeviceCleanup(ids) }
    fun localFavorite(uri: String, value: Boolean) = action { repository.localFavorite(uri, value) }
    val playbackEpoch get() = repository.playbackEpoch
    suspend fun playbackPosition(identity: String, epoch: Long) = repository.playbackPosition(identity, epoch)
    fun savePlaybackPosition(identity: String, position: Long, duration: Long, ended: Boolean, epoch: Long) {
        viewModelScope.launch {
            try {
                repository.savePlaybackPosition(identity, position, duration, ended, epoch)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* A bookmark failure never interrupts playback or exposes data. */ }
        }
    }
    suspend fun recordMediaView(id: String) = repository.recordMediaView(id)
    suspend fun warmTimeline(ids: List<String>) {
        try {
            repository.warmTimeline(ids)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { /* Optional warming never interrupts the library. */ }
    }
    fun markVideoPosition(id: String, position: Long, duration: Long, epoch: Long) = repository.markVideoPosition(id, position, duration, epoch)
    suspend fun videoWindow(id: String, position: Long, duration: Long) {
        try {
            repository.videoWindow(id, position, duration)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { /* Cache maintenance never interrupts playback. */ }
    }
    fun mediaSourceFactory() = repository.mediaSourceFactory()
    suspend fun prefetch(id: String) = repository.prefetch(id)
    fun favorite(id: String, value: Boolean) = action { repository.changeFiles(setOf(id), favorite = value) }
    fun trash(ids: Set<String>, deleted: Boolean = true, origin: String = "") = action { repository.changeFiles(ids, trash = deleted, trashOrigin = origin) }
    internal suspend fun trashConfirmed(ids: Set<String>, origin: String, epoch: Long) {
        check(playbackEpoch == epoch && state.value.stage == app.televault.data.VaultStage.READY)
        repository.changeFiles(ids, trash = true, trashOrigin = origin, expectedEpoch = epoch)
    }
    internal suspend fun deviceTrashOriginConfirmed(uris: List<Uri>, origin: String, epoch: Long) {
        check(playbackEpoch == epoch && state.value.stage == app.televault.data.VaultStage.READY)
        repository.deviceTrashOrigin(uris, origin)
    }
    fun deviceTrashOrigin(uris: List<Uri>, origin: String) = action { repository.deviceTrashOrigin(uris, origin) }
    fun trashAllCloud(phrase: CharArray, finished: (Boolean?) -> Unit) = action {
        try {
            finished(repository.trashAllCloud(phrase))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repository.notice("Could not queue cloud Trash. Nothing was permanently deleted. Check the connection and retry.")
            finished(null)
        } finally {
            phrase.fill('\u0000')
        }
    }
    fun purge(ids: Set<String>) = action { repository.purgeTrash(ids) }
    fun verifyRecoveryPhrase(phrase: CharArray, finished: (Boolean) -> Unit) = action {
        try {
            finished(repository.verifyRecoveryPhrase(phrase))
        } finally {
            phrase.fill('\u0000')
        }
    }
    fun renameFile(id: String, name: String) = action { repository.changeFiles(setOf(id), displayName = name) }
    fun folder(ids: Set<String>, name: String, copy: Boolean) = action { if (copy) repository.copyCloud(ids, name) else repository.changeFiles(ids, folder = name) }
    fun album(name: String, ids: Set<String>, folder: Boolean = false) = action { repository.createAlbum(name, ids, folder) }
    fun settings(limit: Long, videos: Boolean, camera: Boolean) = action { repository.updateUploadPolicy(limit, videos, camera) }
    fun resumeAutomaticBackups() = action { repository.resumeAutomaticUploads() }
    fun acknowledgeAutomaticVideo(id: String) = action { repository.acknowledgeAutomaticVideo(id) }
    suspend fun privateNote(id: String) = repository.privateNote(id)
    fun savePrivateNote(id: String, text: String) = action { repository.savePrivateNote(id, text) }
    fun dataSaver(enabled: Boolean) = action { repository.setDataSaver(enabled) }
    fun clearMediaCache() = action { repository.clearMediaCache() }
    fun rotateDeviceKey() = action { repository.rotateDeviceKey() }
    fun overrideLimit() = action { repository.overrideMobileLimit() }
    fun addDeviceDocumentFolder(uri: Uri) = action { repository.addDeviceDocumentFolder(uri) }
    suspend fun reconcileDeviceMedia(items: List<app.televault.data.LocalMedia>): Boolean = try {
        repository.reconcileDeviceMedia(items)
        true
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        false
    }
    fun report(message: String) {
        repository.notice(message)
    }
    private var restoreIndexJob: Job? = null
    fun restoreVisibleIndex() {
        if (restoreIndexJob?.isActive == true) return
        restoreIndexJob = action { repository.restoreVisibleIndex() }
    }
    fun discover() = action { repository.discover() }
    fun unlock(phrase: CharArray) = action { repository.unlock(phrase) }
    fun unlockFromGate(phrase: CharArray, finished: (Boolean) -> Unit) = action {
        try {
            val verified = repository.unlock(phrase, syncAfter = false)
            finished(verified)
            if (verified) {
                try {
                    repository.sync()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    repository.notice("Unlocked locally. Cloud sync will retry when the connection is available.")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            finished(false)
        } finally {
            phrase.fill('\u0000')
        }
    }
    fun passwordManagerAccountFor(secret: CharArray): String = passwordManagerAccount + if (app.televault.crypto.RecoveryCredentials.kind(secret) == app.televault.crypto.RecoveryCredentials.Kind.PATTERN) " · App pattern" else ""
    val passwordManagerAccount: String get() = repository.passwordManagerAccount()
    fun create(phrase: CharArray, name: String = "", finished: (Boolean) -> Unit = {}) = action {
        try {
            finished(repository.create(phrase, name))
        } finally {
            phrase.fill('\u0000')
        }
    }

    /** Call only from an already-open vault after fresh LocalDeviceAuthentication success. */
    fun resetRecoveryPhrase(next: CharArray, finished: (Boolean) -> Unit) = action {
        try {
            repository.resetRecoveryPhrase(next)
            finished(true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repository.notice("Could not finish password reset. Check connection and keep your recovery copy; no files were re-encrypted.")
            finished(false)
        } finally {
            next.fill('\u0000')
        }
    }
    fun changeRecoveryPhrase(current: CharArray, next: CharArray, finished: (Boolean) -> Unit) = action {
        try {
            repository.changeRecoveryPhrase(current, next)
            finished(true)
        } catch (e: CancellationException) {
            finished(false)
            throw e
        } catch (_: Exception) {
            repository.notice("Could not change recovery password. Check the current password and connection; your files were kept.")
            finished(false)
        } finally {
            current.fill('\u0000')
            next.fill('\u0000')
        }
    }
    fun upload(uris: List<Uri>, forceCopy: Boolean = false) = action { repository.enqueueUpload(uris, allowDuplicate = forceCopy) }
    fun download(ids: Set<String>, destination: Uri) = action { repository.enqueueDownload(ids, destination) }
    fun backup(uri: Uri, wifi: Boolean) = action { repository.addBackup(uri, wifi) }
    fun deviceFolderBackup(path: String, enabled: Boolean) = action { repository.setDeviceFolderBackup(path, enabled) }
    fun stopBackup(id: String) = action { repository.stopBackup(id) }
    fun sync() = action { repository.sync() }
    fun retry(id: String) = action { repository.retry(id) }
    fun cancel(id: String) = action { repository.cancel(id) }
    fun lock() {
        previewJob?.cancel()
        operations.forEach { it.cancel() }
        operations.clear()
        repository.lock()
    }
    fun cachedThumbnail(id: String, pixels: Int) = repository.cachedThumbnail(id, pixels)
    fun canPrefetchPhotos() = repository.canPrefetchPhotos()
    suspend fun thumbnail(id: String, pixels: Int = 384): Bitmap? = repository.thumbnail(id, pixels)
    fun preview(id: String, ready: (Uri?) -> Unit) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                ready(repository.openPreview(id))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                repository.notice("Preview could not open. Check the connection, file integrity or available space.")
                ready(null)
            }
        }
    }
    fun closePreview() {
        previewJob?.cancel()
    }
    private fun action(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repository.notice("Could not complete the operation. Check access, connection and available space.")
        }
    }.also {
        operations.removeAll { job -> job.isCompleted }
        operations.add(it)
    }
    class Factory(private val repository: DriveRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriveViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return DriveViewModel(repository) as T
        }
    }
}
