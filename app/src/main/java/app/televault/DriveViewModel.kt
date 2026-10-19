package app.televault

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.televault.data.DriveRepository
import app.televault.data.recoveringMemory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DriveViewModel(private val repository: DriveRepository) : ViewModel() {
    internal val scenes = repository.scenes
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
    private val pendingRemovals = app.televault.data.PendingRemovals()
    internal val localContent = combine(repository.localMedia.changes, pendingRemovals.ids) { files, hidden -> if (hidden.isEmpty()) files else files.filterNot { it.uri.toString() in hidden } }.distinctUntilChanged().flowOn(Dispatchers.Default).recoveringMemory { recoverMemory() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repository.localMedia.changes.value)
    internal suspend fun trashDeviceConfirmed(file: app.televault.data.LocalMedia, origin: String, epoch: Long) {
        check(playbackEpoch == epoch && state.value.stage == app.televault.data.VaultStage.READY)
        pendingRemovals.run(setOf(file.uri.toString())) {
            repository.deviceFileTrash.trash(listOf(file), origin)
            repository.localMedia.confirmedTrash(setOf(file.uri), removed = true)
        }
    }

    // Progress collectors live in small transfer components, not the album grid/player tree.
    val contentState = run {
        var previousFiles: List<app.televault.data.DriveFile>? = null
        var previousHidden = emptySet<String>()
        var visibleFiles = state.value.files
        combine(state, pendingRemovals.ids) { value, pending ->
            val hidden = pending + value.transfers.mapNotNull { app.televault.data.PendingRemovals.queuedPurge(it.id, it.phase) }
            // Transfer percentages must not repeatedly filter a large unchanged gallery.
            if (value.files !== previousFiles || hidden != previousHidden) {
                previousFiles = value.files
                previousHidden = hidden
                visibleFiles = if (hidden.isEmpty()) value.files else value.files.filterNot { it.id in hidden }
            }
            value.copy(files = visibleFiles, notice = if (pending.isNotEmpty()) "Updating selected files…" else value.notice, indexLoading = value.indexLoading && !value.indexLoaded, transfers = value.transfers.filter { it.phase !in hiddenTransferPhases && !app.televault.domain.ActivityProgress.active(it.phase) }.map { it.copy(progress = 0, total = 0) }, syncProgress = null, backupVerification = null, deviceCopyProgress = value.deviceCopyProgress?.copy(completed = 0, total = 0))
        }.distinctUntilChanged().flowOn(Dispatchers.Default).recoveringMemory { recoverMemory() }.stateIn(viewModelScope, SharingStarted.Eagerly, state.value)
    }
    internal val backupVerification = state.map { it.backupVerification }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    internal val backupBadgeTimes = state.map { it.files to it.backedDeviceFiles }.distinctUntilChanged().map { (files, links) ->
        val times = files.associate { it.id to it.backedUpAt }
        times + links.mapValues { times[it.value] ?: 0L }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).recoveringMemory { recoverMemory() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val playerState = state.map { app.televault.data.DriveSnapshot(stage = it.stage, localFavorites = it.localFavorites, privateProfile = it.privateProfile) }.distinctUntilChanged().recoveringMemory { recoverMemory() }.stateIn(viewModelScope, SharingStarted.Eagerly, state.value)
    internal val transferOverview = state.map { it.transfers }.distinctUntilChanged().map { app.televault.data.transferOverviews(it) }.flowOn(Dispatchers.Default).recoveringMemory { recoverMemory() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    internal val backupOverview = state.map { if (it.stage == app.televault.data.VaultStage.READY) it.transfers.filter { job -> !job.download } else emptyList() }.distinctUntilChanged().runningFold(app.televault.data.BackupOverview()) { previous, jobs -> app.televault.data.backupOverview(jobs, previous) }.flowOn(Dispatchers.Default).recoveringMemory { recoverMemory() }.stateIn(viewModelScope, SharingStarted.Eagerly, app.televault.data.BackupOverview())
    private var previewJob: Job? = null
    private val operations = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private data class Mutation(val epoch: Long, val kind: String, val ids: Set<String>, val value: Any? = null)
    private val mutations = app.televault.data.BoundedOperations<Mutation>(viewModelScope, { report("Several file changes are still running. Wait for them to finish, then retry.") }, parallelism = 1)
    private fun mutation(kind: String, ids: Set<String>, value: Any? = null, block: suspend (Long) -> Unit): Job {
        val epoch = playbackEpoch
        return mutations.submit(Mutation(epoch, kind, ids, value)) {
            performAction {
                check(epoch == playbackEpoch && state.value.stage == app.televault.data.VaultStage.READY)
                block(epoch)
            }
        }
    }
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
                        } catch (_: Exception) { } catch (_: OutOfMemoryError) {
                            recoverMemory()
                        }
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
                        people.activate(syncRemote = false)
                        ocr.activate()
                        scenes.activate()
                        val application = repository.localMediaContext() as TeleVaultApplication
                        if (people.state.value.enabled) application.faceModels.install()
                        if (ocr.state.value.enabled) application.ocrModels.install()
                        if (scenes.state.value.enabled) application.memoryModels.install()
                        requestIntelligence()
                        people.cleanPrivateSources()
                        ocr.cleanPrivateSources()
                        if (people.state.value.enabled) {
                            try {
                                people.sync()
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) { }
                        }
                        try {
                            repository.retryPrivateIntelligenceCleanup()
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) { }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) { } catch (_: OutOfMemoryError) {
                        recoverMemory()
                    }
                } else {
                    mutations.cancel()
                    pendingRemovals.clear()
                    people.lock()
                    ocr.lock()
                    scenes.lock()
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
                    } catch (_: Exception) { } catch (_: OutOfMemoryError) {
                        recoverMemory()
                    }
                    delay(if (active) 1000 else 15000)
                }
            }
        }
    }

    internal fun intelligenceActive(value: Boolean) {
        (repository.localMediaContext() as app.televault.TeleVaultApplication).intelligencePaused = !value
    }
    internal fun setPeopleEnabled(value: Boolean) = peopleAction {
        people.enable(value)
        if (value) {
            (repository.localMediaContext() as TeleVaultApplication).faceModels.install()
            requestIntelligence()
        }
    }
    internal fun requestIntelligence() = app.televault.data.MediaIntelligenceScheduling.request(repository.localMediaContext())
    internal fun indexPeople(cloud: List<app.televault.data.DriveFile>, local: List<app.televault.data.LocalMedia>, restart: Boolean = true) {
        requestIntelligence()
    }
    internal fun indexText(cloud: List<app.televault.data.DriveFile>, local: List<app.televault.data.LocalMedia>, restart: Boolean = true) {
        requestIntelligence()
    }
    internal fun peopleAction(block: suspend () -> Unit) = action(block)
    internal suspend fun photoText(item: app.televault.ui.MediaEntry, preferCached: Boolean = false): app.televault.ocr.OcrText {
        check(item.mime.startsWith("image/") && (item.cloud == null || item.cloud.trashedAt == 0L) && item.local?.trashed != true)
        return ocr.analyze(item.id, item.local?.fingerprint ?: item.cloud!!.hash, { item.local?.uri ?: repository.preferredDeviceSource(item.id) ?: repository.privateMediaUri(item.id) }, thorough = true, preferCached = preferCached)
    }
    internal val mediaStorage = repository.mediaStorage.usage
    fun refreshMediaStorage() = action { repository.mediaStorage.refresh() }
    fun mediaCacheQuota(bytes: Long) = action { repository.mediaStorage.setQuota(bytes) }
    internal val offlineDownloads = repository.offlineDownloads
    internal val offlineIds = offlineDownloads.map { it.ids }.distinctUntilChanged().recoveringMemory { recoverMemory() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
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
    internal val selectedPhoto = repository.photoPriority.selected
    internal suspend fun scrubPreview(item: app.televault.ui.MediaEntry, width: Int, height: Int, adjacent: Boolean, allowCloud: Boolean, owner: app.televault.data.PreviewRequestOwner) = repository.scrubPreview(item.id, item.id + ":" + (item.local?.fingerprint ?: item.cloud?.hash.orEmpty()), item.local?.uri, item.cloud != null, width, height, adjacent, allowCloud, owner)
    internal suspend fun tilePreview(item: app.televault.ui.MediaEntry, width: Int, height: Int, owner: app.televault.data.PreviewRequestOwner) = repository.scrubPreview(item.id, "tile:" + item.id + ":" + (item.local?.fingerprint ?: item.cloud?.hash.orEmpty()), item.local?.uri, item.cloud != null, width, height, false, repository.canPrefetchPhotos() && item.size <= 16L * 1024 * 1024, owner, cover = true)
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
    internal val browsePositions = app.televault.data.BrowsePositionStore(repository.localMediaContext(), repository)
    internal val memorySeen = app.televault.data.MemorySeenStore(repository.localMediaContext(), repository)
    private var browseSaveJob: Job? = null
    internal fun saveBrowsePosition(position: app.televault.data.BrowsePosition, epoch: Long) {
        browseSaveJob?.cancel()
        browseSaveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                browsePositions.save(position, epoch)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) { }
        }
    }
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
    fun favorite(id: String, value: Boolean) = mutation("favorite", setOf(id), value) { epoch -> repository.changeFiles(setOf(id), expectedEpoch = epoch, favorite = value) }
    fun trash(ids: Set<String>, deleted: Boolean = true, origin: String = "") = mutation("trash", ids, deleted to origin) { epoch -> pendingRemovals.run(ids) { repository.changeFiles(ids, expectedEpoch = epoch, trash = deleted, trashOrigin = origin) } }
    internal suspend fun trashConfirmed(ids: Set<String>, origin: String, epoch: Long) {
        check(playbackEpoch == epoch && state.value.stage == app.televault.data.VaultStage.READY)
        pendingRemovals.run(ids) { repository.changeFiles(ids, trash = true, trashOrigin = origin, expectedEpoch = epoch) }
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
    fun purge(ids: Set<String>) = mutation("purge", ids) { epoch -> pendingRemovals.run(ids) { repository.purgeTrash(ids, epoch) } }
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
    suspend fun overrideLimit() = repository.overrideMobileLimit()
    suspend fun saveBackupOptions(limit: Long, minutes: Int, photosFirst: Boolean) = repository.saveBackupOptions(limit, minutes, photosFirst)
    suspend fun setUnlimitedBackup(enabled: Boolean) = repository.setUnlimitedBackup(enabled)
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
    fun sync() = action {
        repository.sync()
        repository.resumeAutomaticUploads()
    }
    fun retry(id: String) = action { repository.retry(id) }
    fun cancel(id: String) = action { repository.cancel(id) }
    fun lock() {
        mutations.cancel()
        pendingRemovals.clear()
        previewJob?.cancel()
        operations.toList().forEach { it.cancel() }
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
    private fun recoverMemory() {
        repository.trimMemory()
        // Discard partially restored derived indexes, never their encrypted files or session keys.
        people.lock()
        ocr.lock()
        scenes.lock()
        repository.notice("Background indexing paused to free memory. Your files and saved tags are kept; indexing will retry.")
        runCatching { requestIntelligence() }
    }
    private suspend fun performAction(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repository.notice("Could not complete the operation. Check access, connection and available space.")
        } catch (_: OutOfMemoryError) {
            recoverMemory()
            repository.notice("Memory is busy. Check Trash or Transfers for partial completion, then retry remaining changes.")
        }
    }
    private fun action(block: suspend () -> Unit): Job = viewModelScope.launch { performAction(block) }.also {
        operations.add(it)
        val task = it
        task.invokeOnCompletion { operations.remove(task) }
    }
    class Factory(private val repository: DriveRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DriveViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return DriveViewModel(repository) as T
        }
    }
}
