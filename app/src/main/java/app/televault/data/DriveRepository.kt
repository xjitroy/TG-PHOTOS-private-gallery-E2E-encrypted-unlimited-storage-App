package app.televault.data

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Base64
import android.util.LruCache
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.work.*
import app.televault.PreferencesRepository
import app.televault.crypto.AuthenticatedRecords
import app.televault.crypto.CipherParts
import app.televault.crypto.CipherRangeCache
import app.televault.crypto.NewPasswordPolicy
import app.televault.crypto.OfflineCipherStore
import app.televault.crypto.RecoveryCredentials
import app.televault.crypto.RecoveryEnvelope
import app.televault.crypto.VaultCrypto
import app.televault.domain.AdaptiveMediaPolicy
import app.televault.domain.AuthProtocol
import app.televault.domain.AutomaticBackupPolicy
import app.televault.domain.BackedCopyVerifier
import app.televault.domain.BackupBadge
import app.televault.domain.BackupCopyIndex
import app.televault.domain.BackupIdentity
import app.televault.domain.CaptureTime
import app.televault.domain.DeviceBackupFolder
import app.televault.domain.DeviceFileVisibility
import app.televault.domain.ForgottenVaultPolicy
import app.televault.domain.LibraryPath
import app.televault.domain.LocalBackupReceipts
import app.televault.domain.MediaBackupPolicy
import app.televault.domain.MediaCategories
import app.televault.domain.PasswordAttempts
import app.televault.domain.ProfileIdentity
import app.televault.domain.ScrubPreviewQuality
import app.televault.domain.ThumbnailQuality
import app.televault.domain.TrashCooldown
import app.televault.domain.UploadQueuePolicy
import app.televault.domain.VaultNames
import app.televault.domain.VaultScope
import app.televault.domain.VideoPresentation
import app.televault.memories.SceneStore
import app.televault.ocr.OcrStore
import app.televault.ocr.decodeOcrPhoto
import app.televault.people.PeopleStore
import app.televault.telegram.RemoteDocument
import app.televault.telegram.TelegramFailure
import app.televault.telegram.TelegramGateway
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

class DriveRepository(context: Context, private val telegram: TelegramGateway, private val protection: app.televault.telegram.TransportProtection = app.televault.telegram.OpenTransportProtection) {
    private val app = context.applicationContext
    private val database = Room.databaseBuilder(app, DriveDatabase::class.java, File(app.noBackupFilesDir, "drive-cache.sqlite").absolutePath).build()
    private val dao = database.cache()
    private val work = WorkManager.getInstance(app)
    private val deviceKey = DeviceVaultKey(app)
    private val mutable = MutableStateFlow(DriveSnapshot())
    val state = mutable.asStateFlow()
    private val restoreMutable = MutableStateFlow<CloudRestoreStatus?>(null)
    val cloudRestore = restoreMutable.asStateFlow()
    internal val photoPriority = PhotoLoadPriority()
    private val vaultChoicesMutable = MutableStateFlow<List<VaultChoice>>(emptyList())
    val vaultChoices = vaultChoicesMutable.asStateFlow()
    val selectedVaultId: String get() = vault
    private var pendingVaultId = UUID.randomUUID().toString()
    fun newVaultName(): String = VaultNames.generated(System.currentTimeMillis(), java.time.ZoneId.systemDefault(), pendingVaultId)
    val preferences = PreferencesRepository(app)
    val guidanceScope: String get() = "$account:$vault"
    private val initialization = Mutex()
    private val recoveryRoots = Mutex()
    private val cloudSync = Mutex()
    private val edits = Mutex()
    private val backupControl = Mutex()
    private val backupScanning = Mutex()
    private val uploadQueue = Mutex()
    private val uploadRunner = Mutex()
    private val uploadQueueChanges = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var backupMediaSnapshot: List<LocalMedia>? = null
    private var backupMediaStamp = ""

    @Volatile private var backupMediaAt = 0L
    private val contentGates = Array(64) { Mutex() }
    private val playbackHistory = Mutex()
    private val prefetches = Semaphore(2)
    private val viewing = Mutex()
    private val videoTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val retentionDeadlines = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val startupWarm = Mutex()
    private val transferDisplayCache = object : LruCache<String, DriveTransfer>(8192) {}
    private val transferRefresh = Mutex()
    private val purgeQueue = Mutex()
    private val metadataCache = java.util.concurrent.ConcurrentHashMap<String, DriveFile>()
    private val reconciliation = Mutex()
    private val transferLocks = Array(64) { Mutex() }
    internal val cloudExports by lazy { CloudExports(app, database, telegram, protection, { account to vault }, { unlockedKey() }, { backgroundKey(it) }, { releaseWorkKey(it) }, { state.value.stage == VaultStage.READY }, { sync(requireComplete = true, backgroundTask = it) }, { file(it) }) }
    internal val privateAi by lazy { PrivateIntelligenceExclusions(app, this, telegram) }
    internal val scenes by lazy { SceneStore(app, this, (app as app.televault.TeleVaultApplication).memoryModels) }
    internal val ocr by lazy { OcrStore(app, this, (app as app.televault.TeleVaultApplication).ocrModels) }
    internal val people by lazy { PeopleStore(app, this, telegram) }
    internal fun localMediaContext(): Context = app
    val localMedia = LocalMediaRepository(app)
    internal val deviceFileTrash = LocalFileTrash(app)
    private val deviceCopies = DeviceCopyResolver(app)
    private val deviceCopyChecks = Mutex()
    val photoPreviews = DecodedPhotoCache(MediaMemory.budget(app, 24, 6))
    internal val documentPreviews = DocumentPreviewCache()
    val policy = UploadPolicy(app)
    private val encryptedCache = File(app.noBackupFilesDir, "photo-cipher-cache").apply { mkdirs() }
    private val offlineStore = OfflineCipherStore(File(app.noBackupFilesDir, "offline-cipher"))
    private val offlineControl = Mutex()
    private val offlineMutable = MutableStateFlow(OfflineDownloads())
    internal val offlineDownloads = offlineMutable.asStateFlow()
    internal val mediaStorage = MediaStorage(app)
    private val streamCache = mediaStorage.cache
    private var nativePrunedAt = 0L
    private var retentionCancelled = false

    @Volatile private var cloudIndexNeedsRepair = false
    private val reconciledHashes = object : android.util.LruCache<String, String>(2048) {}
    private val passwordAttempts = PasswordAttempts(android.os.SystemClock::elapsedRealtime)
    private val accessEpoch = java.util.concurrent.atomic.AtomicLong()
    private val activeWorkKeys = java.util.concurrent.atomic.AtomicInteger()
    private val activeReaders = java.util.concurrent.ConcurrentHashMap.newKeySet<AuthenticatedRecords>()
    private val runningTransfers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val uploads = Semaphore(1)
    private val downloads = Semaphore(2)
    private var account = 0L
    private var vault = ""
    private var root: JSONObject? = null
    private var master: ByteArray? = null
    private val thumbnails = object : LruCache<String, Bitmap>(MediaMemory.budget(app, 16, 4)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val cache = File(app.noBackupFilesDir, "drive-cache").apply { mkdirs() }
    private val preview = File(app.noBackupFilesDir, "drive-preview").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }

    fun trimMemory() {
        backupMediaSnapshot = null
        photoPreviews.clear()
        documentPreviews.clear()
        thumbnails.evictAll()
        intelligenceHashes.evictAll()
        localMedia.trimThumbnails()
        metadataCache.clear()
        transferDisplayCache.evictAll()
        reconciledHashes.evictAll()
        deviceCopies.clear()
    }
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
    private fun context(kind: String, accountId: Long = account, vaultId: String = vault) = "TGDrive/v1/$accountId/$vaultId/$kind".toByteArray()
    private fun rootFile(id: Long) = AtomicFile(File(app.noBackupFilesDir, "drive-root-$id.json"))
    private fun archivedRoot(owner: Long, id: String) = AtomicFile(File(app.noBackupFilesDir, "drive-roots/$owner/${UUID.fromString(id)}.json").apply { parentFile!!.mkdirs() })
    private fun archiveRoot(data: JSONObject, owner: Long) {
        val file = archivedRoot(owner, validateRoot(data, owner))
        val output = file.startWrite()
        try {
            output.write(data.toString().toByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
    private fun localVaultChoices() {
        val roots = File(app.noBackupFilesDir, "drive-roots/$account").listFiles().orEmpty().filter { it.extension == "json" }
        vaultChoicesMutable.value = roots.mapNotNull { stored ->
            runCatching {
                check(stored.length() in 1..65536)
                val data = JSONObject(AtomicFile(stored).openRead().use { it.bufferedReader().readText() })
                val id = validateRoot(data, account)
                val created = data.optLong("createdAt")
                var name = if (created > 0) VaultNames.generated(created, java.time.ZoneId.systemDefault(), id) else "TG Photos vault · ${id.take(8)}"
                if (id == vault && master != null && data.has("sealedName")) {
                    val key = unlockedKey()
                    try {
                        val plain = VaultCrypto.open(key, decode(data.getString("sealedName")), context("vault-name", account, id))
                        try {
                            name = VaultNames.clean(plain.toString(Charsets.UTF_8)).ifBlank { name }
                        } finally {
                            plain.fill(0)
                        }
                    } finally {
                        key.fill(0)
                    }
                }
                VaultChoice(id, name, created)
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }
    private fun saveRoot(descriptor: JSONObject, id: Long) {
        archiveRoot(descriptor, id)
        val file = rootFile(id)
        val output = file.startWrite()
        try {
            output.write(descriptor.toString().toByteArray())
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
    private fun validateRoot(descriptor: JSONObject, id: Long): String {
        check(descriptor.getInt("version") == 1 && descriptor.getLong("account") == id)
        check(descriptor.getString("kdf") == "argon2id-m65536-t3-p1")
        check(decode(descriptor.getString("salt")).size == 16 && decode(descriptor.getString("wrapped")).size == 60)
        val primary = primaryCredential(descriptor)
        alternateCredential(descriptor)?.let { check(primary.kind() != it.kind() && it.kind() != RecoveryCredentials.Kind.UNKNOWN) }
        return UUID.fromString(descriptor.getString("vault")).toString()
    }

    private fun primaryCredential(descriptor: JSONObject) = RecoveryCredentials.Slot(
        RecoveryCredentials.Kind.valueOf(descriptor.optString("credentialKind", "UNKNOWN")),
        decode(descriptor.getString("salt")),
        decode(descriptor.getString("wrapped"))
    )
    private fun alternateCredential(descriptor: JSONObject) = descriptor.optJSONObject("alternateCredential")?.let {
        RecoveryCredentials.Slot(RecoveryCredentials.Kind.valueOf(it.getString("kind")), decode(it.getString("salt")), decode(it.getString("wrapped")))
    }
    private fun putCredentials(descriptor: JSONObject, credentials: RecoveryCredentials.Updated) {
        descriptor.put("credentialKind", credentials.primary().kind().name).put("salt", encode(credentials.primary().salt())).put("wrapped", encode(credentials.primary().wrapped()))
        credentials.alternate()?.let { slot -> descriptor.put("alternateCredential", JSONObject().put("kind", slot.kind().name).put("salt", encode(slot.salt())).put("wrapped", encode(slot.wrapped()))) } ?: descriptor.remove("alternateCredential")
    }
    suspend fun discover() = withContext(Dispatchers.IO) {
        protection.requireAllowed()
        initialization.withLock {
            if (state.value.stage == VaultStage.READY) return@withLock
            mutable.update { it.copy(stage = VaultStage.DISCOVERING, quickUnlockAvailable = false, busy = true, notice = "") }
            try {
                val discoveredAccount = telegram.accountId()
                val local = rootFile(discoveredAccount)
                val descriptor = if (local.baseFile.exists() || File(local.baseFile.path + ".bak").exists()) {
                    local.openRead().use { JSONObject(it.bufferedReader().readText().also { text -> check(text.length < 65536) }) }
                } else {
                    val roots = telegram.findDocuments("TGDRIVE_V1_ROOT:")
                    roots.maxByOrNull { it.messageId }?.let { reference ->
                        check(reference.bytes <= 65536)
                        JSONObject(telegram.downloadDocument(reference) { _, _ -> }.readText())
                    }
                }
                val discoveredVault = descriptor?.let { validateRoot(it, discoveredAccount) }.orEmpty()
                check(VaultScope.maySelect(account, vault, discoveredAccount, discoveredVault, activeWorkKeys.get())) { "Background work is using the current vault; retry discovery after it finishes" }
                account = discoveredAccount
                vault = discoveredVault
                root = descriptor
                if (descriptor == null) {
                    mutable.update { it.copy(stage = VaultStage.CREATE) }
                } else {
                    saveRoot(descriptor, account)
                    mutable.update { it.copy(vaultId = vault, stage = VaultStage.LOCKED, quickUnlockAvailable = deviceKey.available(account, vault), patternRecoveryAvailable = root?.let { it.optString("credentialKind", "UNKNOWN") != "PASSWORD" || it.has("alternateCredential") } == true) }
                }
                localVaultChoices()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(stage = VaultStage.ERROR, notice = "Vault discovery failed. Check your connection and retry; no new vault was created.") }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    suspend fun activeTelegramSessions() = withContext(Dispatchers.IO) { telegram.activeSessions() }
    suspend fun terminateOtherTelegramSessions() = withContext(Dispatchers.IO) {
        check(state.value.stage == VaultStage.READY)
        telegram.terminateOtherSessions()
        mutable.update { it.copy(notice = "Other Telegram sessions logged out. This app remains signed in.") }
    }

    fun passwordManagerAccount(): String = "TG Photos vault $account" + if (root?.optBoolean("separateCredential") == true) " · $vault" else ""

    suspend fun beginNewVault() = withContext(Dispatchers.IO) {
        initialization.withLock {
            protection.requireAllowed()
            check(telegram.accountId() == account && activeWorkKeys.get() == 0 && !cloudSync.isLocked) { "Wait for active backup or restore to finish before changing vaults" }
            root?.let { archiveRoot(it, account) }
            lock()
            root = null
            pendingVaultId = UUID.randomUUID().toString()
            mutable.update { it.copy(stage = VaultStage.CREATE, quickUnlockAvailable = false, notice = "Your existing vault remains available separately. The new vault uses an independent key.") }
        }
    }
    suspend fun selectVault(id: String) = withContext(Dispatchers.IO) {
        initialization.withLock {
            protection.requireAllowed()
            requireVaultUsable(account, id)
            check(activeWorkKeys.get() == 0 && !cloudSync.isLocked && telegram.accountId() == account)
            val file = archivedRoot(account, id)
            check(file.baseFile.length() in 1..65536)
            val data = JSONObject(file.openRead().use { it.bufferedReader().readText() })
            check(validateRoot(data, account) == id)
            lock()
            root = data
            vault = id
            saveRoot(data, account)
            localVaultChoices()
            mutable.update { it.copy(vaultId = vault, stage = VaultStage.LOCKED, quickUnlockAvailable = deviceKey.available(account, vault)) }
        }
    }
    suspend fun findOtherVaults() = withContext(Dispatchers.IO) {
        initialization.withLock {
            check(state.value.stage != VaultStage.READY && telegram.accountId() == account)
            protection.requireAllowed()
            val found = telegram.findDocuments("TGDRIVE_V1_ROOT:").sortedByDescending { it.messageId }.distinctBy { it.marker }
            for (reference in found) {
                check(reference.bytes in 1..65536)
                val data = JSONObject(telegram.downloadDocument(reference) { _, _ -> }.readText())
                val id = validateRoot(data, account)
                check(reference.marker == "TGDRIVE_V1_ROOT:$id")
                archiveRoot(data, account)
                if (id == vault) {
                    root = data
                    saveRoot(data, account)
                }
            }
            localVaultChoices()
        }
    }

    suspend fun create(phrase: CharArray, requestedName: String = ""): Boolean = withContext(Dispatchers.IO) {
        protection.requireAllowed()
        var created = false
        val epoch = accessEpoch.get()
        initialization.withLock {
            check(state.value.stage == VaultStage.CREATE)
            check(activeWorkKeys.get() == 0) { "Wait for background work before creating another vault" }
            require(NewPasswordPolicy.accepts(phrase)) { "Choose a strong password; new patterns are disabled" }
            mutable.update { it.copy(busy = true, notice = "") }
            val key = VaultCrypto.randomKey()
            val salt = VaultCrypto.randomBytes(16)
            vault = pendingVaultId
            var derived: ByteArray? = null
            try {
                derived = VaultCrypto.recoveryKey(phrase, salt)
                val descriptor = JSONObject().put("version", 1).put("account", account).put("vault", vault)
                    .put("kdf", "argon2id-m65536-t3-p1").put("salt", encode(salt))
                    .put("wrapped", encode(VaultCrypto.seal(derived, key, context("root"))))
                    .put("credentialKind", RecoveryCredentials.kind(phrase).name)
                val createdAt = System.currentTimeMillis()
                val name = VaultNames.forCreation(requestedName, createdAt, java.time.ZoneId.systemDefault(), vault).toByteArray()
                try {
                    descriptor.put("createdAt", createdAt).put("separateCredential", vaultChoicesMutable.value.isNotEmpty()).put("sealedName", encode(VaultCrypto.seal(key, name, context("vault-name"))))
                } finally {
                    name.fill(0)
                }
                val file = File(cache, "${UUID.randomUUID()}.bin")
                try {
                    file.writeText(descriptor.toString())
                    telegram.sendDocument(file, "TGDRIVE_V1_ROOT:$vault") { _, _ -> }
                } finally {
                    file.delete()
                }
                saveRoot(descriptor, account)
                root = descriptor
                protection.requireAllowed()
                // A convenience wrapper failure is not a password failure. The verified
                // master remains usable for this session; the next session may ask again.
                val quickAccess = runCatching { deviceKey.save(account, vault, key) }.isSuccess
                synchronized(this@DriveRepository) {
                    coroutineContext.ensureActive()
                    check(accessEpoch.get() == epoch) { "Setup request expired; recover the confirmed Drive" }
                    master = key.copyOf()
                    mutable.update { it.copy(vaultId = vault, stage = VaultStage.READY, quickUnlockAvailable = quickAccess) }
                    created = true
                }
                migrateCameraBackups()
                refreshLocal()
                scheduleRetention()
                localVaultChoices()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update { it.copy(notice = if (created) "Vault created and password accepted. Retry cloud sync to finish loading the library." else "Vault setup was interrupted. Retry discovery before creating another vault.", stage = if (created) VaultStage.READY else VaultStage.ERROR) }
            } finally {
                key.fill(0)
                derived?.fill(0)
                phrase.fill('\u0000')
                mutable.update { it.copy(busy = false) }
            }
        }
        created
    }

    suspend fun unlock(phrase: CharArray, syncAfter: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        protection.requireAllowed()
        var verified = false
        var credentialRejected = false
        val epoch = accessEpoch.get()
        initialization.withLock {
            mutable.update { it.copy(busy = true, notice = "") }
            var derived: ByteArray? = null
            var key: ByteArray? = null
            try {
                if (passwordAttempts.remainingMillis() > 0) {
                    mutable.update { it.copy(notice = "Too many password attempts. Wait ${((passwordAttempts.remainingMillis() + 999) / 1000)} seconds and try again.") }
                    return@withLock
                }
                val descriptor = checkNotNull(root)
                requireVaultUsable(account, vault)
                check(validateRoot(descriptor, account) == vault)
                key = try {
                    RecoveryCredentials.open(phrase, primaryCredential(descriptor), alternateCredential(descriptor), context("root"))
                } catch (e: java.security.GeneralSecurityException) {
                    credentialRejected = true
                    passwordAttempts.failed()
                    throw e
                }
                passwordAttempts.succeeded()
                check(key.size == 32)
                coroutineContext.ensureActive()
                protection.requireAllowed()
                val quickAccess = runCatching { deviceKey.save(account, vault, key) }.isSuccess
                synchronized(this@DriveRepository) {
                    coroutineContext.ensureActive()
                    check(accessEpoch.get() == epoch) { "Unlock request expired" }
                    master?.fill(0)
                    master = key.copyOf()
                    mutable.update { it.copy(stage = VaultStage.READY, quickUnlockAvailable = quickAccess) }
                    verified = true
                }
                migrateCameraBackups()
                refreshLocal()
                localVaultChoices()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.update {
                    it.copy(
                        notice = when {
                            credentialRejected -> "This password could not unlock the selected vault. Check the password and selected vault."
                            verified -> "Password accepted. The local library could not finish loading; retry cloud sync."
                            else -> "Vault initialization could not finish. Retry without closing the app; your password has not been marked incorrect."
                        }
                    )
                }
            } finally {
                derived?.fill(0)
                key?.fill(0)
                phrase.fill('\u0000')
                mutable.update { it.copy(busy = false) }
            }
        }
        if (verified) scheduleCloudRestore()
        if (verified && syncAfter) sync()
        verified
    }

    suspend fun verifyRecoveryPhrase(phrase: CharArray): Boolean = withContext(Dispatchers.IO) {
        protection.requireAllowed()
        var current: ByteArray? = null
        try {
            initialization.withLock {
                recoveryRoots.withLock {
                    if (passwordAttempts.remainingMillis() > 0) return@withLock false
                    current = unlockedKey()
                    val descriptor = checkNotNull(root)
                    check(validateRoot(descriptor, account) == vault)
                    val verified = RecoveryCredentials.verify(current!!, phrase, primaryCredential(descriptor), alternateCredential(descriptor), context("root"))
                    if (verified) passwordAttempts.succeeded() else passwordAttempts.failed()
                    coroutineContext.ensureActive()
                    verified && state.value.stage == VaultStage.READY
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            current?.fill(0)
            phrase.fill('\u0000')
        }
    }

    suspend fun changeRecoveryPhrase(currentPhrase: CharArray, nextPhrase: CharArray) = replaceRecoveryPhrase(currentPhrase, nextPhrase)
    suspend fun resetRecoveryPhrase(nextPhrase: CharArray) = replaceRecoveryPhrase(null, nextPhrase)
    private suspend fun replaceRecoveryPhrase(currentPhrase: CharArray?, nextPhrase: CharArray) = withContext(Dispatchers.IO) {
        initialization.withLock {
            recoveryRoots.withLock {
                val key = unlockedKey()
                var changed: RecoveryCredentials.Updated? = null
                mutable.update { it.copy(busy = true, notice = "") }
                try {
                    val descriptor = checkNotNull(root)
                    check(validateRoot(descriptor, account) == vault)
                    if (currentPhrase != null) {
                        check(passwordAttempts.remainingMillis() == 0L) { "Wait before trying another password" }
                        val verified = RecoveryCredentials.verify(key, currentPhrase.copyOf(), primaryCredential(descriptor), alternateCredential(descriptor), context("root"))
                        if (verified) passwordAttempts.succeeded() else passwordAttempts.failed()
                        check(verified) { "Incorrect existing credential" }
                    }
                    val replacement = RecoveryCredentials.passwordOnly(key, primaryCredential(descriptor), alternateCredential(descriptor), null, nextPhrase, context("root"))
                    changed = replacement
                    val previous = telegram.findDocuments("TGDRIVE_V1_ROOT:$vault").filter { it.bytes in 1..65536 }
                    val next = JSONObject(descriptor.toString()).apply {
                        remove("pendingRootRetirement")
                        putCredentials(this, replacement)
                    }
                    val upload = File(cache, "${UUID.randomUUID()}.bin")
                    try {
                        upload.writeText(next.toString())
                        telegram.sendDocument(upload, "TGDRIVE_V1_ROOT:$vault") { _, _ -> }
                        // Once Telegram confirms the new root, commit it even if the UI locks.
                        withContext(NonCancellable) {
                            next.put("pendingRootRetirement", JSONArray(previous.map { reference -> JSONObject().put("chat", reference.chatId).put("message", reference.messageId).put("bytes", reference.bytes) }))
                            saveRoot(next, account)
                            root = next
                        }
                    } finally {
                        upload.delete()
                    }
                    val cleaned = cleanupRecoveryRoots()
                    mutable.update { it.copy(notice = if (cleaned) "Strong password saved. App-pattern recovery has been removed from the current Drive root." else "Strong password saved. Old password/pattern wrappers will be retired when sync can finish; keep the new password.") }
                } finally {
                    key.fill(0)
                    currentPhrase?.fill('\u0000')
                    nextPhrase.fill('\u0000')
                    changed?.primary()?.salt()?.fill(0)
                    changed?.primary()?.wrapped()?.fill(0)
                    changed?.alternate()?.salt()?.fill(0)
                    changed?.alternate()?.wrapped()?.fill(0)
                    mutable.update { it.copy(busy = false) }
                }
            }
        }
    }
    private suspend fun cleanupRecoveryRoots(): Boolean {
        val descriptor = root ?: return true
        val pending = descriptor.optJSONArray("pendingRootRetirement") ?: return true
        return try {
            val old = (0 until pending.length()).map { index -> pending.getJSONObject(index).let { RemoteDocument(it.getLong("chat"), it.getLong("message"), it.getLong("bytes")) } }
            telegram.retireRecoveryRoots(old, vault)
            val cleaned = JSONObject(descriptor.toString()).apply { remove("pendingRootRetirement") }
            saveRoot(cleaned, account)
            root = cleaned
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    fun lock() {
        val closing: List<AuthenticatedRecords>
        synchronized(this) {
            accessEpoch.incrementAndGet()
            photoPriority.clear()
            people.lock()
            ocr.lock()
            scenes.lock()
            privateAi.lock()
            restoreMutable.value = null
            vaultChoicesMutable.update { choices -> choices.map { it.copy(name = if (it.createdAt > 0) VaultNames.generated(it.createdAt, java.time.ZoneId.systemDefault(), it.id) else "TG Photos vault · ${it.id.take(8)}") } }
            PrivateMediaGrants.clear()
            closing = activeReaders.toList()
            activeReaders.clear()
            cloudExports.clear()
            master?.fill(0)
            master = null
            metadataCache.clear()
            videoTimes.clear()
            retentionDeadlines.clear()
            backupMediaSnapshot = null
            transferDisplayCache.evictAll()
            offlineMutable.value = OfflineDownloads()
            photoPreviews.clear()
            documentPreviews.clear()
            thumbnails.evictAll()
            intelligenceHashes.evictAll()
            val nextStage = if (root != null) {
                VaultStage.LOCKED
            } else if (state.value.stage == VaultStage.CREATE) {
                VaultStage.CREATE
            } else {
                VaultStage.ERROR
            }
            // Never perform disk/Keystore IPC on the UI's immediate lock path. Unlock verifies it again.
            mutable.value = DriveSnapshot(vaultId = vault, stage = nextStage, quickUnlockAvailable = state.value.quickUnlockAvailable, patternRecoveryAvailable = root?.let { it.optString("credentialKind", "UNKNOWN") != "PASSWORD" || it.has("alternateCredential") } == true, notice = if (nextStage == VaultStage.ERROR) "Drive discovery was interrupted. Retry discovery; no new Drive was created." else "")
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { closing.forEach { it.close() } }
        localMedia.clearCache()
        work.cancelAllWorkByTag("tgdrive-download")
        preview.listFiles()?.forEach { it.delete() }
    }

    /** Narrow capability for the second, account-bound Private Vault envelope. Never exposes the Photos master. */
    internal fun privateVaultBinding(): Pair<Long, String> = synchronized(this) {
        protection.requireAllowed()
        check(state.value.stage == VaultStage.READY && master != null && account != 0L)
        account to vault
    }
    internal fun privateVaultEnvelope(bytes: ByteArray, objectId: String, binding: Pair<Long, String>, open: Boolean): ByteArray {
        val epoch = accessEpoch.get()
        check(privateVaultBinding() == binding)
        val key = unlockedKey()
        try {
            val aad = context("private-vault-v2/$objectId", binding.first, binding.second)
            val result = if (open) VaultCrypto.open(key, bytes, aad) else VaultCrypto.seal(key, bytes, aad)
            var returned = false
            try {
                check(accessEpoch.get() == epoch && privateVaultBinding() == binding) { "Private Vault account changed or locked" }
                returned = true
                return result
            } finally {
                if (!returned) result.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    /** Independent AAD namespace for optional encrypted People metadata; master never leaves repository. */
    internal fun peopleEnvelope(bytes: ByteArray, objectId: String, binding: Pair<Long, String>, open: Boolean): ByteArray {
        val epoch = accessEpoch.get()
        check(privateVaultBinding() == binding)
        val key = unlockedKey()
        try {
            val aad = context("people-v1/$objectId", binding.first, binding.second)
            val result = if (open) VaultCrypto.open(key, bytes, aad) else VaultCrypto.seal(key, bytes, aad)
            try {
                check(accessEpoch.get() == epoch && privateVaultBinding() == binding)
                return result
            } catch (failure: Throwable) {
                result.fill(0)
                throw failure
            }
        } finally {
            key.fill(0)
        }
    }

    internal fun normalIntelligenceMedia(id: String): Boolean {
        check(state.value.stage == VaultStage.READY)
        val cloud = state.value.files.find { it.id == id }
        if (cloud != null)return cloud.trashedAt == 0L && !privateAi.excluded(id, cloud.hash) && (cloud.mime.startsWith("image/") || cloud.mime.startsWith("video/"))
        return !privateAi.excluded(id) && localMedia.changes.value.any { it.uri.toString() == id && !it.trashed && !it.galleryHidden && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) }
    }
    internal suspend fun analysisPhoto(id: String, allowCloud: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        if (!normalIntelligenceMedia(id)) return@withContext null
        val cloud = state.value.files.find { it.id == id }
        val uri = if (cloud != null) preferredDeviceSource(id) ?: if (allowCloud) privateMediaUri(id) else return@withContext null else localMedia.changes.value.find { it.uri.toString() == id && !it.trashed && !it.galleryHidden }?.uri ?: return@withContext null
        decodeOcrPhoto(app, uri)
    }
    private val intelligenceHashes = LruCache<String, String>(256)
    internal suspend fun intelligenceContentHash(id: String): String = withContext(Dispatchers.IO) {
        val scope = privateVaultBinding()
        state.value.files.find { it.id == id }?.let { return@withContext it.hash }
        val local = localMedia.changes.value.find { it.uri.toString() == id && !it.trashed } ?: return@withContext ""
        if (!local.mime.startsWith("image/") || local.size !in 1..64L * 1024 * 1024)return@withContext ""
        val epoch = playbackEpoch
        val cacheKey = "$epoch:${local.fingerprint}"
        intelligenceHashes.get(cacheKey)?.let { return@withContext it }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        app.contentResolver.openInputStream(local.uri)!!.use { input ->
            val block = ByteArray(65536)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    check(privateVaultBinding() == scope)
                    val count = input.read(block)
                    if (count < 0)break
                    total += count
                    check(total <= local.size)
                    digest.update(block, 0, count)
                }
            } finally {
                block.fill(0)
            }
        }
        check(total == local.size && privateVaultBinding() == scope)
        synchronized(this@DriveRepository) {
            check(epoch == playbackEpoch && privateVaultBinding() == scope)
            encode(digest.digest()).also { intelligenceHashes.put(cacheKey, it) }
        }
    }
    internal suspend fun notePrivateIntelligenceHash(hash: ByteArray) {
        privateAi.activate()
        privateAi.block(emptySet(), setOf(encode(hash)))
        ocr.cleanPrivateSources()
        scenes.cleanPrivateSources()
        people.cleanPrivateSources()
    }
    internal suspend fun retryPrivateIntelligenceCleanup() {
        privateAi.activate()
        if (!privateAi.needsCleanup())return
        privateAi.sync()
        people.cleanPrivateSources()
        ocr.cleanPrivateSources()
        scenes.cleanPrivateSources()
        people.sync()
        privateAi.finishCleanup()
    }
    internal suspend fun retireIntelligenceForPrivateMove(source: Uri, size: Long, hash: ByteArray, extraSource: Uri? = null) = withContext(Dispatchers.IO) {
        val scope = privateVaultBinding()
        privateAi.activate()
        val encoded = encode(hash)
        val cloud = state.value.files.filter { it.size == size && MessageDigest.isEqual(decode(it.hash), hash) }
        val cloudIds = cloud.mapTo(hashSetOf()) { it.id }
        val sourceKeys = setOfNotNull(source.toString(), extraSource?.toString())
        val local = localMedia.changes.value.filter { it.uri.toString() in sourceKeys || state.value.backedDeviceFiles[it.fingerprint] in cloudIds || cloud.any { file -> file.sourceUri == it.uri.toString() } }
        val ids = sourceKeys + cloudIds + local.map { it.uri.toString() }
        privateAi.block(ids, setOf(encoded)) // Persist before network/deletion; failed cleanup cannot re-enable scanning.
        ocr.cleanPrivateSources()
        scenes.cleanPrivateSources()
        people.cleanPrivateSources()
        privateAi.sync() // Read back the encrypted exclusion before removing owned remote references/originals.
        people.sync()
        privateAi.finishCleanup()
        check(privateVaultBinding() == scope)
    }
    internal fun ocrEnvelope(bytes: ByteArray, objectId: String, binding: Pair<Long, String>, open: Boolean): ByteArray {
        val epoch = accessEpoch.get()
        check(privateVaultBinding() == binding)
        val key = unlockedKey()
        try {
            val aad = context("ocr-v1/$objectId", binding.first, binding.second)
            val result = if (open) VaultCrypto.open(key, bytes, aad) else VaultCrypto.seal(key, bytes, aad)
            try {
                check(accessEpoch.get() == epoch && privateVaultBinding() == binding)
                return result
            } catch (failure: Throwable) {
                result.fill(0)
                throw failure
            }
        } finally {
            key.fill(0)
        }
    }

    internal fun intelligencePrivacyEnvelope(bytes: ByteArray, objectId: String, binding: Pair<Long, String>, open: Boolean): ByteArray {
        val epoch = accessEpoch.get()
        check(privateVaultBinding() == binding)
        val key = unlockedKey()
        try {
            val aad = context("ai-private-v1/$objectId", binding.first, binding.second)
            val result = if (open) VaultCrypto.open(key, bytes, aad) else VaultCrypto.seal(key, bytes, aad)
            try {
                check(accessEpoch.get() == epoch && privateVaultBinding() == binding)
                return result
            } catch (failure: Throwable) {
                result.fill(0)
                throw failure
            }
        } finally {
            key.fill(0)
        }
    }

    private fun requireVaultUsable(owner: Long, scope: String) {
        check((app as app.televault.TeleVaultApplication).forgottenVaults.tickets.value.none { it.account == owner && it.photos == scope && it.privateVault == null && it.phase in setOf("STARTED", "DONE") }) { "This vault is being permanently deleted; choose another vault" }
    }
    private fun unlockedKey() = synchronized(this) {
        protection.requireAllowed()
        requireVaultUsable(account, vault)
        checkNotNull(master) { "Unlock the vault first" }.copyOf()
    }
    private fun openJson(key: ByteArray, sealed: ByteArray, aad: ByteArray): JSONObject {
        protection.requireAllowed()
        val bytes = VaultCrypto.open(key, sealed, aad)
        return try {
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }
    private fun payload(row: CacheRow, key: ByteArray) = openJson(key, row.sealed, context("local/${row.id}", row.account, row.vault))
    private fun sealJson(key: ByteArray, json: JSONObject, aad: ByteArray): ByteArray {
        protection.requireAllowed()
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        return try {
            VaultCrypto.seal(key, bytes, aad)
        } finally {
            bytes.fill(0)
        }
    }
    private fun metadataCachePut(identity: String, value: DriveFile, epoch: Long) = synchronized(this) {
        if (master != null && state.value.stage == VaultStage.READY && accessEpoch.get() == epoch) {
            if (metadataCache.size >= 4096 && !metadataCache.containsKey(identity)) metadataCache.keys.firstOrNull()?.let { metadataCache.remove(it) }
            metadataCache[identity] = value
        }
    }
    private fun publishUnlocked(epoch: Long, update: (DriveSnapshot) -> DriveSnapshot) = synchronized(this) {
        if (master != null && state.value.stage == VaultStage.READY && accessEpoch.get() == epoch) mutable.update(update)
    }
    private fun row(id: String, kind: String, json: JSONObject, key: ByteArray, phase: String = ""): CacheRow = CacheRow().also {
        it.id = id
        it.kind = kind
        it.account = account
        it.vault = vault
        it.phase = phase
        it.sealed = sealJson(key, json, context("local/$id"))
    }
    private fun savePayload(row: CacheRow, data: JSONObject, key: ByteArray) {
        row.sealed = sealJson(key, data, context("local/${row.id}", row.account, row.vault))
        if (row.kind == "job") {
            if (dao.updateJobPayload(row.id, row.sealed, row.phase, row.progress, row.total, row.retryAt) == 0) throw CancellationException("Transfer cancelled")
        } else {
            dao.put(row)
        }
    }
    private fun file(json: JSONObject): DriveFile {
        check(json.getInt("version") == 1)
        val id = UUID.fromString(json.getString("id")).toString()
        val length = json.getLong("size")
        VaultCrypto.encryptedLength(length)
        val contentKey = decode(json.getString("key"))
        try {
            check(contentKey.size == 32 && decode(json.getString("hash")).size == 32)
        } finally {
            contentKey.fill(0)
        }
        check(json.getString("name").length <= 1024 && json.getString("thumbnail").length <= 1024 * 1024)
        val facts = MediaFacts.from(json.optJSONObject("facts"))
        return DriveFile(
            id, json.getString("name"), MediaCategories.videoMime(json.getString("name"), json.getString("mime")), length, CaptureTime.backup(facts.capturedAt, 0, json.getLong("date"), 0), json.optString("album", "Uploads"),
            json.getString("hash"), RemoteDocument(json.getLong("chat"), json.getLong("message"), json.getLong("cipherSize")),
            "", json.optString("source"),
            json.optJSONArray("parts")?.let { parts ->
                check(parts.length() in 1..64)
                (0 until parts.length()).map { i ->
                    val part = parts.getJSONObject(i)
                    RemoteDocument(part.getLong("chat"), part.getLong("message"), part.getLong("bytes")).also { check(it.bytes in 1..(1536L * 1024 * 1024)) }
                }.also { check(it.sumOf { part -> part.bytes } == VaultCrypto.encryptedLength(length)) }
            } ?: emptyList(),
            json.optString("sourceUri"), json.optBoolean("favorite"), json.optLong("trashedAt"),
            json.optJSONArray("albums")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() } ?: emptySet(),
            facts,
            sourceAddedAt = json.optLong("sourceAddedAt").coerceAtLeast(0),
            sourceModifiedAt = json.optLong("sourceModifiedAt").coerceAtLeast(0),
            backedUpAt = json.optLong("backedUpAt").coerceAtLeast(0)
        )
    }

    /** A bad encrypted row stays on disk for recovery, but never exposes unverified metadata. */
    private fun <T> verifiedRecord(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    private fun profileEventId() = ProfileIdentity.id(account, vault)
    private val profileEdits = kotlinx.coroutines.sync.Mutex()
    suspend fun ensureOwnerProfile() = withContext(Dispatchers.IO) {
        profileEdits.withLock {
            if (state.value.stage != VaultStage.READY || state.value.privateProfile != null) return@withLock
            val epoch = accessEpoch.get()
            val key = synchronized(this@DriveRepository) { unlockedKey().also { activeWorkKeys.incrementAndGet() } }
            try {
                val owner = telegram.ownerProfile()
                check(epoch == accessEpoch.get() && state.value.stage == VaultStage.READY && owner.accountId == account)
                val profile = PrivateProfile(owner.name.ifBlank { owner.username }, owner.username, avatar = owner.avatarPreview)
                dao.put(row("owner-profile:$account:$vault", "owner-profile", profile.json(), key))
                refreshLocal()
            } finally {
                releaseWorkKey(key)
            }
        }
    }
    suspend fun saveProfile(profile: PrivateProfile) = withContext(Dispatchers.IO) {
        profileEdits.withLock {
            val data = profile.json()
            val epoch = accessEpoch.get()
            val key = synchronized(this@DriveRepository) { unlockedKey().also { activeWorkKeys.incrementAndGet() } }
            try {
                check(epoch == accessEpoch.get() && state.value.stage == VaultStage.READY && !state.value.indexNeedsRepair)
                publishEvent(JSONObject().put("version", 1).put("kind", "state").put("id", profileEventId()).put("privateProfile", data), key)
                refreshLocal()
            } finally {
                releaseWorkKey(key)
            }
        }
    }
    private val indexRefresh = Mutex()
    suspend fun restoreVisibleIndex() {
        if (state.value.stage == VaultStage.READY && (!state.value.indexLoaded || state.value.files.isEmpty())) refreshLocal()
    }
    private suspend fun refreshLocal() = indexRefresh.withLock {
        withContext(Dispatchers.IO) {
            val epoch = accessEpoch.get()
            if (state.value.stage != VaultStage.READY) return@withContext
            val key = synchronized(this@DriveRepository) { master?.copyOf() } ?: return@withContext
            publishUnlocked(epoch) { it.copy(indexLoading = true, indexFailed = false) }
            try {
                // A small authenticated snapshot can paint immediately while the full index
                // is verified. It never marks indexLoaded, so uploads/cleanup cannot trust it.
                if (!state.value.indexLoaded && state.value.files.isEmpty()) {
                    val snapshot = verifiedRecord { dao.get("startup-index:$account:$vault")?.let { payload(it, key).getJSONArray("ids") } }
                    val first = (0 until minOf(snapshot?.length() ?: 0, 70)).mapNotNull { index ->
                        verifiedRecord {
                            val id = UUID.fromString(snapshot!!.getString(index)).toString()
                            val record = checkNotNull(dao.get("file:$id"))
                            check(record.account == account && record.vault == vault)
                            val props = dao.get("state:$id")?.let { payload(it, key) }
                            if (props?.optBoolean("purged") == true || (props?.optLong("trashedAt") ?: 0L) != 0L) {
                                null
                            } else {
                                file(payload(record, key)).let { original ->
                                    original.copy(name = props?.optString("displayName", original.name) ?: original.name, favorite = props?.optBoolean("favorite", original.favorite) ?: original.favorite, album = props?.optString("album", original.album) ?: original.album)
                                }
                            }
                        }
                    }
                    if (first.isNotEmpty()) publishUnlocked(epoch) { it.copy(files = first) }
                }
                var damaged = 0
                fun <T> verified(block: () -> T): T? = verifiedRecord(block).also { if (it == null) damaged++ }
                val files = dao.ids(account, vault, "file").mapNotNull { rowId ->
                    verified {
                        val id = rowId.removePrefix("file:")
                        val identity = "$account:$vault:$id"
                        val original = metadataCache[identity] ?: file(payload(checkNotNull(dao.get(rowId)), key)).also { metadataCachePut(identity, it, epoch) }
                        val props = dao.get("state:$id")?.let { payload(it, key) }
                        val value = original.copy(
                            name = props?.optString("displayName", original.name) ?: original.name,
                            favorite = props?.optBoolean("favorite", original.favorite) ?: original.favorite,
                            trashedAt = props?.optLong("trashedAt", original.trashedAt) ?: original.trashedAt,
                            trashOrigin = props?.optString("trashOrigin").orEmpty(),
                            album = props?.optString("album", original.album) ?: original.album,
                            albumIds = props?.optJSONArray("albums")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() } ?: original.albumIds
                        )
                        value to (props?.optBoolean("purged") != true)
                    }
                }.filter { it.second }.map { it.first }.sortedByDescending { it.date }
                var cloudDamage = damaged
                val activeIds = files.map { it.id }.toSet()
                // A restored cloud URI/size/mtime can belong to different bytes on this device.
                // Only local receipts issued after content verification may suppress a scan.
                val localReceipts = mutableMapOf<String, String>()
                val liveFiles = files.filter { it.trashedAt == 0L }
                val liveIds = liveFiles.map { it.id }.toSet()
                dao.list(account, vault, "source-link").forEach { item ->
                    verified {
                        val link = payload(item, key)
                        localReceipts[link.getString("source")] = link.getString("file")
                    }
                }
                val backedSources = LocalBackupReceipts.retained(activeIds, localReceipts).keys
                val deviceLinks = LocalBackupReceipts.retained(liveIds, localReceipts)
                val damageBeforeAlbums = damaged
                val albums = (dao.list(account, vault, "album") + dao.list(account, vault, "folder")).mapNotNull { item ->
                    verified {
                        val data = payload(item, key)
                        CloudAlbum(data.getString("id"), data.getString("name"), item.kind == "folder")
                    }
                }
                cloudDamage += damaged - damageBeforeAlbums
                cloudIndexNeedsRepair = cloudDamage > 0
                val backups = dao.list(account, vault, "backup").mapNotNull { item ->
                    verified {
                        val data = payload(item, key)
                        BackupFolder(item.id, data.getString("name"), data.optBoolean("wifi", true), data.optString("deviceFolder").takeIf { it.isNotBlank() }, data.optString("uri").takeIf { it != "camera" }.orEmpty())
                    }
                }
                localMedia.setDocumentRoots(dao.list(account, vault, "device-library-root").mapNotNull { item -> verified { Uri.parse(payload(item, key).getString("uri")) } })
                refreshOffline(key, files, epoch)
                val jobs = transferList(key)
                val trash = dao.list(account, vault, "local-trash").mapNotNull { item ->
                    verified {
                        payload(item, key).also {
                            it.getString("uri")
                            it.getLong("trashedAt")
                        }
                    }
                }
                val trashDates = trash.associate { it.getString("uri") to it.getLong("trashedAt") }
                val trashOrigins = trash.associate { it.getString("uri") to it.optString("origin") }
                val savedProfile = dao.get("state:${profileEventId()}")?.let { item -> verified { payload(item, key).optJSONObject("privateProfile")?.let(PrivateProfile::read) } }
                val ownerProfile = dao.get("owner-profile:$account:$vault")?.let { item -> verified { PrivateProfile.read(payload(item, key)) } }
                val favorites = dao.list(account, vault, "local-favorite").mapNotNull { item -> verified { payload(item, key).getString("uri") } }.toSet()
                val settings = policy.settings()
                val cleanups = dao.list(account, vault, "device-cleanup").mapNotNull { item ->
                    verified {
                        val value = payload(item, key)
                        val age = System.currentTimeMillis() - value.getLong("requestedAt")
                        if (age !in 0 until 86400000L) {
                            dao.remove(item.id)
                            null
                        } else {
                            DeviceCleanupRequest(item.id, value.getString("uri"), value.getString("source"), value.optString("origin"))
                        }
                    }
                }
                if (dao.get("startup-index:$account:$vault") == null && files.isNotEmpty()) {
                    dao.put(row("startup-index:$account:$vault", "startup-index", JSONObject().put("ids", JSONArray(liveFiles.filter { it.mime.startsWith("image/") || it.mime.startsWith("video/") }.take(70).map { it.id })), key))
                }
                publishUnlocked(epoch) { it.copy(indexLoaded = true, indexLoading = false, indexFailed = false, indexNeedsRepair = damaged > 0, dualRecoveryAvailable = root?.has("alternateCredential") == true, patternRecoveryAvailable = root?.let { it.optString("credentialKind", "UNKNOWN") != "PASSWORD" || it.has("alternateCredential") } == true, files = files, backups = backups, transfers = jobs, albums = albums, uploadSettings = settings, backedSources = backedSources, backedDeviceFiles = deviceLinks, deviceCleanupRequests = cleanups, deviceTrashDates = trashDates, deviceTrashOrigins = trashOrigins, localFavorites = favorites, privateProfile = savedProfile ?: ownerProfile, notice = if (damaged > 0) "Some local records could not be verified. Valid files remain available; sync to recover the cloud index. Automatic backup is paused until verification succeeds." else it.notice) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                publishUnlocked(epoch) { it.copy(indexFailed = true, notice = "The encrypted local index could not be restored. Retry; existing backups are kept.") }
                throw error
            } finally {
                key.fill(0)
                publishUnlocked(epoch) { it.copy(indexLoading = false) }
            }
        }
    }
    private fun activeJobs(owner: Long, scope: String) = dao.activeJobIds(owner, scope).asSequence().mapNotNull { dao.get(it)?.takeIf { row -> row.account == owner && row.vault == scope && row.kind == "job" && row.phase !in setOf("COMPLETE", "CANCELLED") } }
    private fun transferList(key: ByteArray) = dao.transfers(account, vault).map { transfer ->
        verifiedRecord {
            val identity = "$account:$vault:${transfer.id}"
            // Display fields are immutable for a queued job. Avoid decrypting its thumbnail/key
            // checkpoint on every byte update. Never cache content keys or the decrypted payload.
            val display = transferDisplayCache[identity] ?: payload(checkNotNull(dao.get(transfer.id)), key).let { data ->
                DriveTransfer(transfer.id, data.optString("name", "File"), "", 0, 0, data.optString("action") == "download", batchId = data.optString("batchId"), batchName = data.optString("album"), batchCount = data.optInt("batchCount"), batchBytes = data.optLong("batchBytes", -1), fileBytes = data.optLong("initialSize", -1))
                    .also { if (state.value.stage == VaultStage.READY) transferDisplayCache.put(identity, it) }
            }
            display.copy(phase = transfer.phase, progress = transfer.progress, total = transfer.total, running = transfer.id in runningTransfers)
        } ?: DriveTransfer(transfer.id, "Unverified local job", "FAILED", 0, 0, false)
    }.sortedByDescending { it.id }
    private suspend fun refreshTransfers() = transferRefresh.withLock {
        val epoch = accessEpoch.get()
        if (state.value.stage != VaultStage.READY) return@withLock
        val key = synchronized(this) { master?.copyOf() } ?: return@withLock
        try {
            val transfers = transferList(key)
            val settings = policy.settings()
            publishUnlocked(epoch) { it.copy(transfers = transfers, uploadSettings = settings) }
        } finally {
            key.fill(0)
        }
    }

    suspend fun sync(requireComplete: Boolean = false, backgroundTask: CacheRow? = null) = withContext(Dispatchers.IO) {
        if (requireComplete) {
            cloudSync.lock()
        } else if (!cloudSync.tryLock()) {
            return@withContext
        }
        var key: ByteArray? = null
        val epoch = accessEpoch.get()
        try {
            if (backgroundTask == null && state.value.stage != VaultStage.READY) {
                check(!requireComplete) { "Unlock before syncing the encrypted library" }
                return@withContext
            }
            val syncKey = if (backgroundTask == null) unlockedKey() else backgroundKey(backgroundTask)
            key = syncKey
            val firstRestore = state.value.files.isEmpty()
            publishUnlocked(epoch) { it.copy(busy = true, notice = "", syncProgress = WorkProgress("Syncing cloud", 0, 0)) }
            if (backgroundTask == null) {
                recoveryRoots.withLock { cleanupRecoveryRoots() }
                edits.withLock {
                    dao.list(account, vault, "vault-source-retired").filter { it.phase == "REVOKED" }.forEach { journal ->
                        ensureKeyActive(syncKey)
                        check(accessEpoch.get() == epoch)
                        val event = payload(journal, syncKey)
                        check(event.getString("kind") == "state" && event.getBoolean("purged"))
                        publishEvent(event, syncKey)
                        dao.remove(journal.id)
                    }
                }
            }
            if (backgroundTask == null && firstRestore) restoreFirstScreen(syncKey, epoch)
            val remote = telegram.findDocuments("TGDRIVE_V1_FILE:$vault:").sortedBy { it.messageId }
            val allEvents = telegram.findDocuments("TGDRIVE_V1_EVENT:$vault:").sortedBy { it.messageId }
            val cursorId = "event-sync:$account:$vault"
            val cursor = if (cloudIndexNeedsRepair) 0L else dao.get(cursorId)?.let { runCatching { payload(it, syncKey).optLong("message") }.getOrDefault(0L) } ?: 0L
            val events = allEvents.filter { it.messageId > cursor }
            val remoteTimes = remote.associate { it.messageId to it.confirmedAtMillis }
            val known = dao.ids(account, vault, "file").mapNotNull { id ->
                verifiedRecord {
                    val item = checkNotNull(dao.get(id))
                    val metadata = payload(item, syncKey).also { file(it) }
                    val message = metadata.optLong("catalogMessage")
                    val completion = BackupBadge.completedAt(metadata.optLong("backedUpAt"), remoteTimes[message] ?: 0L)
                    if (completion != metadata.optLong("backedUpAt")) {
                        savePayload(item, metadata.put("backedUpAt", completion), syncKey)
                        metadataCache.remove("$account:$vault:${id.removePrefix("file:")}")
                    }
                    message
                }
            }.toSet()
            val pending = remote.filter { it.messageId !in known }
            val total = pending.size.toLong() + events.size
            var done = 0L
            fun progress() {
                if (state.value.stage == VaultStage.READY && accessEpoch.get() == epoch) restoreMutable.value = CloudRestoreStatus("$account:$vault", done, total, firstRestore)
                // Per-file progress has its own small collector. Do not re-project every
                // queued backup and gallery item for each restored manifest.
            }
            progress()
            val appliedEvents = dao.list(account, vault, "state").mapNotNull { row ->
                runCatching { payload(row, syncKey).optLong("eventMessage").takeIf { it > 0 } }.getOrNull()
            }.toSet()
            for (reference in events) {
                coroutineContext.ensureActive()
                if (reference.messageId in appliedEvents) {
                    done++
                    progress()
                    continue
                }
                check(reference.bytes <= 65536)
                val eventFile = telegram.downloadDocument(reference) { _, _ -> }
                val event = openJson(syncKey, eventFile.readBytes(), context("event"))
                applyEvent(event, reference.messageId, syncKey)
                done++
                progress()
            }
            // Apply deletion/state events before publishing any newly recovered media.
            // Newest manifests paint the first screen while older thumbnails keep restoring.
            for (reference in pending.asReversed()) {
                coroutineContext.ensureActive()
                check(reference.bytes <= 1024 * 1024)
                val source = telegram.downloadDocument(reference) { _, _ -> }
                val metadata = openJson(syncKey, source.readBytes(), context("manifest"))
                metadata.put("catalogMessage", reference.messageId).put("backedUpAt", BackupBadge.completedAt(metadata.optLong("backedUpAt"), reference.confirmedAtMillis))
                val value = file(metadata)
                dao.put(row("file:${value.id}", "file", metadata, syncKey))
                metadataCachePut("$account:$vault:${value.id}", value, epoch)
                done++
                progress()
                if (state.value.stage == VaultStage.READY && accessEpoch.get() == epoch && (done == events.size.toLong() + 12 || (done - events.size) % 32 == 0L)) refreshLocal()
            }
            events.maxOfOrNull { it.messageId }?.let { dao.put(row(cursorId, "event-sync", JSONObject().put("message", it), syncKey)) }
            // Written only after every authenticated manifest and state event has applied.
            // After reinstall the first upload must cross this barrier, even in a worker.
            dao.put(row("cloud-baseline:$account:$vault", "cloud-baseline", JSONObject().put("complete", true), syncKey))
            refreshLocal()
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            if (requireComplete) throw error
            publishUnlocked(epoch) { it.copy(notice = "Sync could not finish. Your verified local index is still available; retry when online.") }
        } finally {
            key?.let { if (backgroundTask == null) it.fill(0) else releaseWorkKey(it) }
            publishUnlocked(epoch) { it.copy(busy = false, syncProgress = null) }
            if (accessEpoch.get() == epoch) restoreMutable.value = null
            cloudSync.unlock()
        }
    }

    /** A bounded first page paints before enumerating every cloud file. Tombstones apply
     * first, and the complete baseline barrier is still written only by the full sync. */
    private suspend fun restoreFirstScreen(key: ByteArray, epoch: Long) = coroutineScope {
        val latest = async { telegram.findNewestDocuments("TGDRIVE_V1_FILE:$vault:", 12) }
        val changes = async { telegram.findDocuments("TGDRIVE_V1_EVENT:$vault:").sortedBy { it.messageId } }
        val references = latest.await()
        if (references.isEmpty()) {
            changes.cancel()
            return@coroutineScope
        }
        if (accessEpoch.get() == epoch) restoreMutable.value = CloudRestoreStatus("$account:$vault", 0, 0, true)
        for (reference in changes.await()) {
            ensureActive()
            ensureKeyActive(key)
            check(reference.bytes in 1..65536)
            val source = telegram.downloadDocument(reference) { _, _ -> }
            applyEvent(openJson(key, source.readBytes(), context("event")), reference.messageId, key)
        }
        for ((index, reference) in references.withIndex()) {
            ensureActive()
            ensureKeyActive(key)
            check(accessEpoch.get() == epoch && reference.bytes in 1..1048576)
            val source = telegram.downloadDocument(reference) { _, _ -> }
            val data = openJson(key, source.readBytes(), context("manifest"))
            data.put("catalogMessage", reference.messageId).put("backedUpAt", BackupBadge.completedAt(data.optLong("backedUpAt"), reference.confirmedAtMillis))
            val item = file(data)
            dao.put(row("file:${item.id}", "file", data, key))
            metadataCachePut("$account:$vault:${item.id}", item, epoch)
            if (index == 3 || index == references.lastIndex) refreshLocal()
        }
    }

    private fun scheduleCloudRestore() {
        val request = OneTimeWorkRequestBuilder<CloudRestoreWorker>().setInputData(workDataOf("account" to account, "vault" to vault))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(20, TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        work.enqueueUniqueWork("tgphotos-cloud-restore:$account:$vault", ExistingWorkPolicy.KEEP, request)
    }
    internal suspend fun restoreCloudInBackground(owner: Long, scope: String) {
        UUID.fromString(scope)
        sync(
            requireComplete = true,
            backgroundTask = CacheRow().apply {
                account = owner
                vault = scope
            }
        )
    }
    internal suspend fun deleteForgottenPhotos(owner: Long, scope: String, access: suspend () -> Unit) = withContext(Dispatchers.IO) {
        initialization.withLock {
            protection.requireAllowed()
            check(telegram.accountId() == owner && activeWorkKeys.get() == 0 && !cloudSync.isLocked) { "Wait for active vault work before deletion" }
            if (account == owner && vault == scope) {
                (app as app.televault.TeleVaultApplication).privateVault.lock()
                lock()
            }
            val references = telegram.findDocuments("TGDRIVE_V1_").filter { ForgottenVaultPolicy.owns(it.marker, scope, null) }
            for (batch in references.chunked(50)) {
                access()
                telegram.deleteVaultNamespace(batch, scope, null)
            }
            access()
            retireForgottenPhotosFiles(owner, scope)
        }
    }
    private suspend fun retireForgottenPhotosFiles(owner: Long, scope: String) {
        (app as app.televault.TeleVaultApplication).privateVault.retireForgottenPhotosLocal(owner, scope)
        dao.list(owner, scope, "job").forEach { work.cancelUniqueWork("transfer:${it.id}") }
        dao.ids(owner, scope, "file").map { it.removePrefix("file:") }.forEach { id ->
            UUID.fromString(id)
            offlineStore.remove("$owner:$scope:$id")
            streamCache.removeGroup("$owner/$scope/$id")
            val cipher = File(encryptedCache, "$id.bin")
            check(!cipher.exists() || cipher.delete())
        }
        people.retire(owner, scope)
        ocr.retire(owner, scope)
        scenes.retire(owner, scope)
        privateAi.retire(owner, scope)
        dao.removeVault(owner, scope)
        archivedRoot(owner, scope).delete()
        deviceKey.erase(owner, scope)
        val selected = rootFile(owner)
        if (selected.baseFile.exists() || File(selected.baseFile.path + ".bak").exists()) {
            val data = selected.openRead().use {
                check(selected.baseFile.length() in 1..65536)
                JSONObject(it.bufferedReader().readText())
            }
            if (validateRoot(data, owner) == scope) selected.delete()
        }
        if (account == owner && vault == scope) {
            root = null
            vault = ""
            mutable.value = DriveSnapshot(stage = VaultStage.CREATE)
        }
        if (account == owner) localVaultChoices()
    }
    internal suspend fun retireCompletedDeletion(ticket: ForgottenVaultTicket) = withContext(Dispatchers.IO) {
        check(telegram.accountId() == ticket.account && ticket.phase == "DONE")
        if (ticket.privateVault != null) {
            (app as app.televault.TeleVaultApplication).privateVault.retireForgottenLocal(ticket.account, ticket.photos, ticket.privateVault)
        } else {
            initialization.withLock {
                if (account == ticket.account && vault == ticket.photos) {
                    (app as app.televault.TeleVaultApplication).privateVault.lock()
                    lock()
                }
                dao.list(ticket.account, ticket.photos, "job").forEach { work.cancelUniqueWork("transfer:${it.id}") }
                check(activeWorkKeys.get() == 0 && !cloudSync.isLocked)
                retireForgottenPhotosFiles(ticket.account, ticket.photos)
            }
        }
    }
    internal suspend fun deleteForgottenPrivate(owner: Long, photos: String, privateId: String, access: suspend () -> Unit) = withContext(Dispatchers.IO) {
        check(!(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked)
        check(telegram.accountId() == owner)
        protection.requireAllowed()
        val key = initialization.withLock {
            // This maintenance lease uses an explicit old scope without swapping the
            // active Photos vault or borrowing its master for another vault.
            val key = if (account == owner && vault == photos) synchronized(this@DriveRepository) { master?.copyOf() } ?: deviceKey.load(owner, photos) else deviceKey.load(owner, photos)
            activeWorkKeys.incrementAndGet()
            key
        }
        try {
            (app as app.televault.TeleVaultApplication).privateVault.revokeForgotten(owner, photos, privateId)
            val binding = owner to photos
            val store = PrivateVaultCloudStore(app, this@DriveRepository, telegram, binding) { bytes, scope ->
                protection.requireAllowed()
                VaultCrypto.open(key, bytes, context("private-vault-v2/root", scope.first, scope.second))
            }
            // Gate each remote batch via a suspend-aware outer validation; the store's
            // synchronous guard enforces account/device protection between native calls.
            access()
            store.deleteAll(binding, UUID.fromString(privateId), access) { protection.requireAllowed() }
            access()
            (app as app.televault.TeleVaultApplication).privateVault.retireForgottenLocal(owner, photos, privateId)
        } finally {
            releaseWorkKey(key)
        }
    }

    suspend fun enqueueUpload(uris: List<Uri>, album: String = "Uploads", sourceTokens: Map<String, String> = emptyMap(), displayNames: Map<String, String> = emptyMap(), allowDuplicate: Boolean = false) = withContext(Dispatchers.IO) {
        uploadQueue.withLock {
            val key = unlockedKey()
            try {
                val queued = activeJobs(account, vault).filter { !UploadQueuePolicy.terminalFailure(it.phase) }.associateBy { payload(it, key).optString("source") }
                val promoted = mutableListOf<CacheRow>()
                val tasks = uris.distinct().mapNotNull { uri ->
                    coroutineContext.ensureActive()
                    val source = sourceTokens[uri.toString()] ?: sourceFingerprint(uri)
                    (if (allowDuplicate) null else queued[source])?.let { existing ->
                        // A separate authenticated ticket never overwrites a running upload checkpoint.
                        putUploadTicket(existing, key, manual = true, wifi = false)
                        promoted += existing
                        return@mapNotNull null
                    }
                    // Default manual backup verifies content again; a reused URI/name is not proof.
                    // Force-copy bypasses both source and content suppression explicitly.
                    val name = displayNames[uri.toString()]?.take(1024) ?: displayName(uri)
                    val times = MediaTools.sourceTimes(app, uri)
                    val data = JSONObject().put("action", "upload").put("uri", uri.toString()).put("name", name).put("album", album).put("source", source).put("allowDuplicate", allowDuplicate)
                        .put("sourceAddedAt", times.first).put("sourceModifiedAt", times.second)
                    row(UUID.randomUUID().toString(), "job", data, key, "WAITING").also { it.total = sizeHint(uri) }
                }
                persistBatch(tasks, key, manual = true)
                (promoted + tasks).firstOrNull()?.let { scheduleUploadQueue(it, manual = true) }
                refreshTransfers()
            } finally {
                key.fill(0)
            }
        }
    }
    private fun sizeHint(uri: Uri): Long = if (uri.scheme == "file") {
        File(uri.path!!).length()
    } else {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }
    private suspend fun persistBatch(tasks: List<CacheRow>, key: ByteArray, manual: Boolean, wifi: Boolean = false) {
        if (tasks.isEmpty()) return
        val batchId = UUID.randomUUID().toString()
        val bytes = if (tasks.all { it.total >= 0 }) tasks.sumOf { it.total } else -1L
        tasks.forEach { task ->
            coroutineContext.ensureActive()
            val data = payload(task, key).put("batchId", batchId).put("batchCount", tasks.size).put("batchBytes", bytes).put("initialSize", task.total)
            task.sealed = sealJson(key, data, context("local/${task.id}", task.account, task.vault))
        }
        database.runInTransaction {
            tasks.forEach {
                dao.put(it)
                putUploadTicket(it, key, manual, wifi)
            }
        }
    }
    private suspend fun queueAutomaticBatch(candidates: List<CacheRow>, wifi: Boolean, key: ByteArray) {
        if (candidates.isEmpty()) return
        val index = backupCopyIndex(key)
        val epoch = accessEpoch.get()
        val pending = mutableListOf<CacheRow>()
        var linked = 0
        try {
            for ((position, task) in candidates.withIndex()) {
                coroutineContext.ensureActive()
                if (!BackgroundBattery.backup(app)) {
                    BackupScheduling.whenCharging(app)
                    throw DataPolicyBlocked("WAITING")
                }
                if (position % 16 == 0) publishUnlocked(epoch) { it.copy(backupVerification = WorkProgress("Checking existing backups on this phone", position.toLong(), candidates.size.toLong())) }
                val matched = try {
                    matchExistingBackup(task, payload(task, key), key, index)
                } catch (_: java.io.IOException) {
                    false // The durable upload job reports an unreadable source and offers Retry.
                } catch (_: SecurityException) {
                    false
                }
                if (matched) linked++ else pending += task
                if ((position + 1) % 16 == 0) {
                    enqueueAutomaticCandidates(pending, wifi, key)
                    pending.clear()
                }
                yield()
            }
        } finally {
            publishUnlocked(epoch) { it.copy(backupVerification = null) }
            if (linked > 0) refreshLocal()
        }
        enqueueAutomaticCandidates(pending, wifi, key)
    }
    private suspend fun enqueueAutomaticCandidates(pending: List<CacheRow>, wifi: Boolean, key: ByteArray) = uploadQueue.withLock {
        if (pending.isEmpty()) return@withLock
        // Cold workers and the UI use the same device-local content receipts.
        // Never import source tokens from restored cloud manifests as backup proof.
        val known = verifiedBackupSources(account, vault, key)
        known += activeJobs(account, vault).map { payload(it, key).optString("source") }
        val selected = pending.filter { task ->
            val data = payload(task, key)
            val folder = dao.get(data.getString("backupId"))
            folder != null && folder.kind == "backup" && folder.account == task.account && folder.vault == task.vault && known.add(data.optString("source"))
        }
        if (selected.isEmpty()) return@withLock
        // Each small admitted group has a durable, fixed count/byte plan.
        persistBatch(selected, key, manual = false, wifi = wifi)
        scheduleUploadQueue(selected.first(), manual = false)
        refreshTransfers()
    }

    /** Recovered manifests already contain authenticated names, lengths and hashes; no original
     * cloud media is downloaded to decide whether this phone has the exact same bytes. */
    private fun backupCopyIndex(key: ByteArray): BackupCopyIndex = BackupCopyIndex(
        dao.ids(account, vault, "file").map { id ->
            val item = file(payload(checkNotNull(dao.get(id)), key))
            val props = dao.get("state:${item.id}")?.let { payload(it, key) }
            BackupCopyIndex.Copy(item.id, item.name, item.size, item.hash, item.trashedAt == 0L && props?.optBoolean("purged") != true && (props?.optLong("trashedAt") ?: 0L) == 0L)
        }
    )
    private suspend fun matchExistingBackup(task: CacheRow, data: JSONObject, key: ByteArray, index: BackupCopyIndex, display: Boolean = false): Boolean {
        if (data.optBoolean("allowDuplicate") || data.has("metadata")) return false
        check(task.account == account && task.vault == vault)
        val uri = Uri.parse(data.getString("uri"))
        val bytes = task.total.takeIf { it >= 0 } ?: size(uri)
        if (!index.hasCandidates(bytes)) return false
        if (display) stage(task, "VERIFYING_DEVICE", 0, bytes)
        val before = sourceFingerprint(uri)
        val scope = coroutineContext
        val hash = app.contentResolver.openInputStream(uri)?.use { input ->
            BackedCopyVerifier.digest(input, bytes) {
                scope.ensureActive()
                protection.requireAllowed()
            }?.let(::encode)
        } ?: return false
        val match = index.match(data.getString("name"), bytes, hash) ?: return false
        if (sourceFingerprint(uri) != before || size(uri) != bytes) return false
        val current = dao.get("file:${match.id()}")?.let { file(payload(it, key)) } ?: return false
        val props = dao.get("state:${current.id}")?.let { payload(it, key) }
        if (!BackupIdentity.same(current.hash, current.size, hash, bytes, current.trashedAt == 0L && props?.optBoolean("purged") != true && (props?.optLong("trashedAt") ?: 0L) == 0L, false)) return false
        protection.requireAllowed()
        if (data.optString("source").isNotBlank()) saveSourceLink(data.getString("source"), current.id, key)
        return true
    }

    /** Repair pre-update blocked automatic jobs too, without bypassing their upload policy. */
    private suspend fun reconcileWaitingBackups(anchor: CacheRow, key: ByteArray) {
        val index = backupCopyIndex(key)
        var changed = false
        val epoch = accessEpoch.get()
        val candidates = activeJobs(anchor.account, anchor.vault).toList()
        try {
            for ((position, candidate) in candidates.withIndex()) {
                publishUnlocked(epoch) { it.copy(backupVerification = WorkProgress("Checking existing backups on this phone", position.toLong(), candidates.size.toLong())) }
                if (!BackgroundBattery.backup(app)) break
                if (candidate.id in runningTransfers) continue
                transferLocks[candidate.id.hashCode().ushr(1) % transferLocks.size].withLock {
                    val task = dao.get(candidate.id) ?: return@withLock
                    if (task.phase in setOf("COMPLETE", "CANCELLED") || task.id in runningTransfers) return@withLock
                    val data = payload(task, key)
                    if (data.optString("action") != "upload" || data.optString("backupId") != anchor.id || data.optBoolean("allowDuplicate") || data.has("metadata")) return@withLock
                    val matched = try {
                        matchExistingBackup(task, data, key, index)
                    } catch (_: java.io.IOException) {
                        false
                    } catch (_: SecurityException) {
                        false
                    }
                    if (matched && dao.get(task.id)?.phase != "CANCELLED") {
                        task.phase = "COMPLETE"
                        task.progress = task.total
                        saveTransfer(task)
                        dao.remove("upload-queue:${task.id}")
                        changed = true
                    }
                }
                yield()
            }
        } finally {
            publishUnlocked(epoch) { it.copy(backupVerification = null) }
            if (changed) refreshLocal()
        }
    }
    suspend fun enqueueDownload(ids: Set<String>, destination: Uri) = withContext(Dispatchers.IO) {
        val key = unlockedKey()
        try {
            for (id in ids) {
                val item = checkNotNull(dao.get("file:$id"))
                val metadata = payload(item, key)
                val jobId = UUID.randomUUID().toString()
                val data = JSONObject().put("action", "download").put("file", metadata).put("name", metadata.getString("name")).put("destination", destination.toString())
                val task = row(jobId, "job", data, key, "WAITING")
                dao.put(task)
                schedule(task, download = true)
            }
            refreshTransfers()
        } finally {
            key.fill(0)
        }
    }
    private fun putUploadTicket(task: CacheRow, key: ByteArray, manual: Boolean, wifi: Boolean) {
        val id = "upload-queue:${task.id}"
        val previous = dao.get(id)?.let { payload(it, key) }
        val source = payload(task, key)
        val mime = source.optJSONObject("metadata")?.optString("mime")?.takeIf { it.isNotBlank() } ?: source.optString("sourceMime").ifBlank { MediaTools.mime(app, Uri.parse(source.getString("uri"))) }
        val data = JSONObject().put("mime", mime).put("camera", source.optString("backupId") == "camera:${task.account}:${task.vault}").put("duration", source.optLong("sourceDuration")).put("job", task.id).put("manual", manual || previous?.optBoolean("manual") == true)
            .put("wifi", wifi && previous?.optBoolean("manual") != true).put("requestedAt", if (manual && previous?.optBoolean("manual") != true) System.currentTimeMillis() else previous?.optLong("requestedAt") ?: System.currentTimeMillis())
        dao.put(row(id, "upload-queue", data, key))
        uploadQueueChanges.incrementAndGet()
    }
    private fun scheduleUploadQueue(task: CacheRow, manual: Boolean, wifi: Boolean = false, delaySeconds: Long = 0, daily: Boolean = false): Operation {
        val builder = OneTimeWorkRequestBuilder<UploadQueueWorker>().setInputData(workDataOf("job" to task.id, "queueDelay" to delaySeconds))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).apply { if (!manual && !BackgroundBattery.backup(app)) setRequiresCharging(true) }.build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("tgdrive").addTag("tgdrive-transfer")
        if (manual && delaySeconds == 0L) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (delaySeconds > 0) builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        val lane = if (daily) "daily:${java.time.LocalDate.now().plusDays(1)}" else UploadQueuePolicy.lane(manual, wifi, delaySeconds)
        return work.enqueueUniqueWork("upload-queue:${task.account}:${task.vault}:$lane", if (daily) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
    }
    suspend fun deferUploads(seed: String, previousDelay: Long) = withContext(Dispatchers.IO) {
        dao.get(seed)?.let { task ->
            scheduleUploadQueue(task, manual = false, delaySeconds = UploadQueuePolicy.nextRetry(previousDelay)).result.get(5, TimeUnit.SECONDS)
        }
    }

    /** Migrates already-scheduled per-file workers without replacing their encrypted checkpoints. */
    suspend fun redirectQueuedUpload(id: String): Boolean = withContext(Dispatchers.IO) {
        val task = dao.get(id) ?: return@withContext false
        if (task.phase in setOf("COMPLETE", "CANCELLED")) return@withContext false
        val key = backgroundKey(task)
        try {
            val data = payload(task, key)
            if (data.optString("action") != "upload") return@withContext false
            uploadQueue.withLock {
                if (dao.get("upload-queue:$id") == null) {
                    val wifi = data.optString("backupId").takeIf { it.isNotBlank() }?.let { dao.get(it) }?.let { payload(it, key).optBoolean("wifi", true) } ?: false
                    putUploadTicket(task, key, manual = !data.has("backupId"), wifi = wifi)
                }
                scheduleUploadQueue(task, manual = !data.has("backupId"))
            }
            true
        } finally {
            releaseWorkKey(key)
        }
    }

    /** Bounded drain: select again between files so a manual request gets the next safe slot. */
    suspend fun drainUploads(seed: String): Boolean = withContext(Dispatchers.IO) {
        // Another lane may be completing its last poll; retain this durable wake on contention.
        if (!uploadRunner.tryLock()) return@withContext false
        var key: ByteArray? = null
        var anchor: CacheRow? = null
        try {
            val selected = dao.get(seed) ?: return@withContext true
            anchor = selected
            key = backgroundKey(selected)
            val started = android.os.SystemClock.elapsedRealtime()
            var completed = 0
            val attempted = mutableSetOf<String>()
            var generation = uploadQueueChanges.get()
            var pending = pendingUploads(selected, key)
            while (completed < 16 && android.os.SystemClock.elapsedRealtime() - started < 120000) {
                if ((app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) return@withContext false
                if (generation != uploadQueueChanges.get()) {
                    generation = uploadQueueChanges.get()
                    pending = pendingUploads(selected, key)
                }
                val next = pending.firstOrNull { (task, ticket) -> task.id !in attempted && uploadEligible(task, ticket) } ?: break
                attempted += next.first.id
                executeJob(next.first.id)
                // Completion/failure can release photo priority without changing the ticket generation.
                pending = pendingUploads(selected, key)
                completed++
            }
            true
        } finally {
            val finishingKey = key
            val finishingAnchor = anchor
            finishUploadDrain(uploadQueue, uploadRunner) {
                if (finishingKey != null && finishingAnchor != null) {
                    try {
                        val pending = pendingUploads(finishingAnchor, finishingKey)
                        val ready = pending.firstOrNull { !(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked && uploadEligible(it.first, it.second) }
                        if (ready != null) {
                            // Backoff for temporary failures; fresh manual requests use a separate expedited lane.
                            val wait = if (ready.first.phase in setOf("RETRYING", "WAITING_TELEGRAM", "INTERRUPTED")) 30L else 0L
                            scheduleUploadQueue(ready.first, ready.second.optBoolean("manual"), delaySeconds = wait)
                        } else {
                            pending.firstOrNull { !UploadQueuePolicy.terminalFailure(it.first.phase) && (it.second.optBoolean("wifi") || it.first.phase in setOf("DAILY_PHOTO_LIMIT", "MOBILE_VIDEO_DISABLED")) }?.let { scheduleUploadQueue(it.first, manual = false, wifi = true) }
                            if (pending.any { it.first.phase == "DAILY_PHOTO_LIMIT" }) {
                                val midnight = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                                scheduleUploadQueue(pending.first().first, manual = false, delaySeconds = ((midnight - System.currentTimeMillis()) / 1000 + 1).coerceAtLeast(1), daily = true)
                            }
                            pending.filter { !UploadQueuePolicy.terminalFailure(it.first.phase) && it.first.retryAt > System.currentTimeMillis() }.minByOrNull { it.first.retryAt }?.let {
                                scheduleUploadQueue(it.first, manual = false, delaySeconds = ((it.first.retryAt - System.currentTimeMillis()) / 1000 + 1).coerceAtLeast(30))
                            }
                        }
                    } finally {
                        releaseWorkKey(finishingKey)
                    }
                }
            }
        }
    }
    private fun pendingUploads(anchor: CacheRow, key: ByteArray): List<Pair<CacheRow, JSONObject>> {
        val settings = policy.settings()
        val pending = dao.list(anchor.account, anchor.vault, "upload-queue").mapNotNull { ticket ->
            val data = payload(ticket, key)
            val task = dao.get(data.getString("job"))
            if (task == null || task.account != anchor.account || task.vault != anchor.vault || task.phase in setOf("COMPLETE", "CANCELLED")) {
                dao.remove(ticket.id)
                null
            } else {
                if (!data.has("mime")) {
                    val source = payload(task, key)
                    data.put("mime", source.optJSONObject("metadata")?.optString("mime") ?: source.optString("sourceMime").ifBlank { MediaTools.mime(app, Uri.parse(source.getString("uri"))) })
                    data.put("camera", source.optString("backupId") == "camera:${task.account}:${task.vault}").put("duration", source.optLong("sourceDuration"))
                }
                task to data
            }
        }
        val photosPending = settings.photosFirst && pending.any { !UploadQueuePolicy.terminalFailure(it.first.phase) && AutomaticBackupPolicy.priority(it.second.optString("mime")) == 0 }
        pending.forEach { it.second.put("photosPending", photosPending) }
        return pending.sortedWith { a, b ->
            val manualA = a.second.optBoolean("manual")
            val manualB = b.second.optBoolean("manual")
            AutomaticBackupPolicy.compare(manualA, a.second.optString("mime"), a.second.optLong("requestedAt"), a.first.id, manualB, b.second.optString("mime"), b.second.optLong("requestedAt"), b.first.id, settings.photosFirst)
        }
    }
    private fun uploadEligible(task: CacheRow, ticket: JSONObject): Boolean {
        val manual = ticket.optBoolean("manual")
        if (!manual && !BackgroundBattery.backup(app)) return false
        val settings = policy.settings()
        val video = ticket.optString("mime").startsWith("video/")
        if (!manual && video && ticket.optBoolean("photosPending")) return false
        if (!manual && task.phase == "CAMERA_VIDEO_LIMIT" && !AutomaticBackupPolicy.cameraVideoAllowed(ticket.optLong("duration"), settings.cameraVideoMinutes, settings.mobileVideos, settings.unlimited, policy.cellular())) return false
        val required = if (task.total >= 0) VaultCrypto.encryptedLength(task.total) + 1024 * 1024 else -1L
        val allowed = manual || settings.unlimited || video && settings.mobileVideos || UploadQueuePolicy.photoAllowance(settings.dailyLimitBytes, settings.usedBytes, required, false)
        val phase = if (task.phase == "DAILY_PHOTO_LIMIT" && allowed || task.phase == "CAMERA_VIDEO_LIMIT") "WAITING" else task.phase
        return UploadQueuePolicy.eligible(phase, task.retryAt, System.currentTimeMillis(), task.id in runningTransfers, manual, ticket.optBoolean("wifi"), policy.cellular() && !manual, settings.dataSaver, settings.mobileVideos || settings.unlimited || ticket.optBoolean("camera"), AutomaticVideoNotice.available(app))
    }
    private fun schedule(task: CacheRow, wifi: Boolean = false, download: Boolean = false, restart: Boolean = false) {
        if (!download && dao.get("upload-queue:${task.id}") != null) {
            scheduleUploadQueue(task, manual = true, wifi = wifi)
            return
        }
        val request = OneTimeWorkRequestBuilder<TransferWorker>().setInputData(workDataOf("job" to task.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("tgdrive")
            .addTag(if (download) "tgdrive-download" else "tgdrive-transfer").build()
        work.enqueueUniqueWork("transfer:${task.id}", if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }
    suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        dao.get(id)?.let {
            if (id in runningTransfers || work.getWorkInfosForUniqueWork("transfer:$id").get(5, TimeUnit.SECONDS).any { info -> info.state == WorkInfo.State.RUNNING }) return@withContext
            if (it.retryAt > System.currentTimeMillis()) return@withContext
            val key = unlockedKey()
            try {
                it.phase = "WAITING"
                dao.updateTransfer(it.id, it.phase, it.progress, it.total, it.retryAt)
                schedule(it, download = payload(it, key).optString("action") == "download", restart = true)
                refreshTransfers()
            } finally {
                key.fill(0)
            }
        }
    }
    suspend fun acknowledgeAutomaticVideo(id: String) = withContext(Dispatchers.IO) {
        val key = unlockedKey()
        try {
            val task = checkNotNull(dao.get(id))
            check(task.account == account && task.vault == vault && task.phase == "VIDEO_NOTICE_REQUIRED")
            val data = payload(task, key)
            check(data.getString("action") == "upload" && data.has("backupId"))
            data.put("videoNoticeAcknowledged", true)
            task.phase = "WAITING"
            task.retryAt = 0
            savePayload(task, data, key)
            schedule(task, restart = true)
            refreshTransfers()
        } finally {
            key.fill(0)
        }
    }
    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        dao.get(id)?.let {
            it.phase = "CANCELLED"
            dao.put(it)
            work.cancelUniqueWork("transfer:$id")
            dao.remove("upload-queue:$id")
            refreshTransfers()
        }
    }
    private fun displayName(uri: Uri): String = if (uri.scheme == "file") {
        File(uri.path!!).name
    } else {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else "File"
        }?.take(1024) ?: "File"
    }
    private fun size(uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path!!).length()
        val known = app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1
        } ?: -1
        if (known >= 0) return known
        return app.contentResolver.openInputStream(uri)!!.use { stream ->
            val buffer = ByteArray(65536)
            var count = 0L
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                count += n
                check(count <= 64L * 1024 * 1024 * 1024)
            }
            count
        }
    }

    private fun releaseWorkKey(key: ByteArray) {
        key.fill(0)
        activeWorkKeys.decrementAndGet()
    }
    private suspend fun backgroundKey(task: CacheRow): ByteArray {
        check((app as app.televault.TeleVaultApplication).forgottenVaults.tickets.value.none { it.account == task.account && it.photos == task.vault && it.privateVault == null && it.phase in setOf("STARTED", "DONE") }) { "This vault is being permanently deleted" }
        if ((app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) throw DataPolicyBlocked("WAITING_UNLOCK")
        telegram.reconnect()
        withTimeout(45_000) { telegram.authorization.first { it.stage == AuthProtocol.State.READY || it.stage == AuthProtocol.State.CLOSED || it.stage == AuthProtocol.State.PHONE } }
        if (telegram.authorization.value.stage != AuthProtocol.State.READY) throw DataPolicyBlocked("WAITING_TELEGRAM")
        check(telegram.accountId() == task.account) { "Telegram account changed" }
        protection.requireAllowed()
        return initialization.withLock {
            // A worker cannot swap the active vault or borrow another vault's master key.
            val selection = rootFile(task.account)
            val persisted = if (account == 0L || vault.isEmpty()) {
                if (selection.baseFile.exists() || File(selection.baseFile.path + ".bak").exists()) {
                    selection.openRead().use {
                        check(selection.baseFile.length() in 1..65536)
                        validateRoot(JSONObject(it.bufferedReader().readText()), task.account)
                    }
                } else {
                    ""
                }
            } else {
                vault
            }
            if (!VaultScope.workerMayUse(account, vault, task.account, task.vault, persisted)) throw DataPolicyBlocked("WAITING_VAULT")
            account = task.account
            vault = task.vault
            protection.requireAllowed()
            val key = synchronized(this) { master?.copyOf() } ?: deviceKey.load(task.account, task.vault)
            activeWorkKeys.incrementAndGet()
            key
        }
    }
    suspend fun executeJob(id: String): Boolean = withContext(Dispatchers.IO) {
        transferLocks[id.hashCode().ushr(1) % transferLocks.size].withLock {
            val task = dao.get(id) ?: return@withContext true
            if (task.phase == "COMPLETE" || task.phase == "CANCELLED") return@withContext true
            if ((app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) {
                task.phase = "WAITING_UNLOCK"
                saveTransfer(task)
                return@withContext false
            }
            if (task.retryAt > System.currentTimeMillis()) return@withContext false
            var key: ByteArray? = null
            try {
                key = backgroundKey(task)
                val data = payload(task, key)
                val ticket = dao.get("upload-queue:$id")?.let { payload(it, key) }
                if (ticket?.optBoolean("manual") == true) data.put("manualRequested", true)
                if (data.getString("action") == "upload") {
                    if (dao.get("file:${task.id}") == null) uploads.withPermit { withRunningTransfer(task.id) { upload(task, data, key) } }
                } else if (data.getString("action") == "trash-all") {
                    withRunningTransfer(task.id) { moveCloudBatchToTrash(task, data, key) }
                } else if (data.getString("action") == "purge") {
                    val metadata = data.getJSONObject("file")
                    val props = dao.get("state:${metadata.getString("id")}")?.let { payload(it, key) }
                    if (props != null && !props.optBoolean("purged") && props.optLong("trashedAt") > 0) {
                        val date = props.optLong("trashedAt")
                        val remaining = TrashCooldown.remaining(date, System.currentTimeMillis(), preferences.trashCooldownEnabled.first())
                        if (remaining > 0) {
                            task.phase = "WAITING_TRASH_COOLDOWN"
                            task.retryAt = System.currentTimeMillis() + remaining
                            saveTransfer(task)
                            refreshTransfers()
                            return@withContext false
                        }
                        purgeRemote(metadata, key)
                    }
                } else {
                    downloads.withPermit { withRunningTransfer(task.id) { export(task, data, key) } }
                }
                coroutineContext.ensureActive()
                if (dao.get(id)?.phase == "CANCELLED") return@withContext true
                task.phase = "COMPLETE"
                task.progress = task.total
                saveTransfer(task)
                if (state.value.stage == VaultStage.READY) {
                    when (data.optString("action")) {
                        "download" -> refreshTransfers()
                        "upload" -> {
                            publishUploadResult(task, key)
                            MediaIntelligenceScheduling.request(app)
                        }
                        else -> refreshLocal()
                    }
                }
                true
            } catch (e: DataPolicyBlocked) {
                task.phase = e.reason
                saveTransfer(task)
                refreshTransfers()
                false
            } catch (_: TimeoutCancellationException) {
                task.phase = "RETRYING"
                saveTransfer(task)
                refreshTransfers()
                false
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (dao.get(id)?.phase != "CANCELLED") {
                        task.phase = "INTERRUPTED"
                        saveTransfer(task)
                    }
                }
                throw e
            } catch (e: TelegramFailure) {
                task.phase = if (e.safe.kind() == AuthProtocol.Failure.FLOOD_WAIT) "WAITING_TELEGRAM" else "RETRYING"
                val now = System.currentTimeMillis()
                val seconds = e.safe.retryAfterSeconds()
                task.retryAt = if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
                saveTransfer(task)
                refreshTransfers()
                false
            } catch (e: Exception) {
                val temporary = (e is java.io.IOException && e !is java.io.FileNotFoundException) || e is java.security.ProviderException ||
                    e is java.security.KeyStoreException || e is android.security.keystore.UserNotAuthenticatedException
                task.phase = when {
                    e is java.io.FileNotFoundException -> "SOURCE_UNAVAILABLE"
                    e is SecurityException -> "SOURCE_ACCESS_REQUIRED"
                    temporary -> "RETRYING"
                    else -> "FAILED"
                }
                saveTransfer(task)
                refreshTransfers()
                !temporary
            } finally {
                key?.let { releaseWorkKey(it) }
                if (dao.get(id)?.phase in setOf("COMPLETE", "CANCELLED")) {
                    dao.remove("upload-queue:$id")
                    File(cache, "$id.bin").delete()
                    File(cache, "$id-part.bin").delete()
                }
            }
        }
    }
    private suspend fun publishUploadResult(task: CacheRow, key: ByteArray) {
        if (task.account != account || task.vault != vault || state.value.stage != VaultStage.READY) return
        val epoch = accessEpoch.get()
        val record = dao.get("file:${task.id}")
        if (record == null) {
            // Deduplicated sources need their authenticated source-link reconciliation.
            refreshLocal()
            return
        }
        indexRefresh.withLock {
            val original = file(payload(record, key))
            val props = dao.get("state:${original.id}")?.let { payload(it, key) }
            if (props?.optBoolean("purged") == true) {
                refreshTransfers()
                return
            }
            val value = original.copy(
                name = props?.optString("displayName", original.name) ?: original.name,
                favorite = props?.optBoolean("favorite", original.favorite) ?: original.favorite,
                trashedAt = props?.optLong("trashedAt", original.trashedAt) ?: original.trashedAt,
                trashOrigin = props?.optString("trashOrigin").orEmpty(),
                album = props?.optString("album", original.album) ?: original.album,
                albumIds = props?.optJSONArray("albums")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() } ?: original.albumIds
            )
            metadataCachePut("$account:$vault:${value.id}", original, epoch)
            // A committed upload changes one entry. Avoid rereading/decrypting the complete
            // library (and every folder/profile/Trash record) after each photo in a batch.
            publishUnlocked(epoch) { snapshot ->
                if (snapshot.files.any { it.id == value.id }) {
                    snapshot
                } else {
                    snapshot.copy(
                        files = (snapshot.files + value).sortedByDescending { it.date },
                        backedSources = if (value.sourceToken.isBlank()) snapshot.backedSources else snapshot.backedSources + value.sourceToken,
                        backedDeviceFiles = if (value.sourceToken.isBlank() || value.trashedAt != 0L) snapshot.backedDeviceFiles else snapshot.backedDeviceFiles + (value.sourceToken to value.id)
                    )
                }
            }
            refreshTransfers()
        }
    }
    private suspend fun <T> withRunningTransfer(id: String, block: suspend () -> T): T {
        val epoch = accessEpoch.get()
        runningTransfers.add(id)
        try {
            return block()
        } finally {
            runningTransfers.remove(id)
            publishUnlocked(epoch) { it.copy(transfers = it.transfers.map { task -> if (task.id == id) task.copy(running = false) else task }) }
        }
    }
    private fun saveTransfer(task: CacheRow) = dao.updateTransfer(task.id, task.phase, task.progress, task.total, task.retryAt)
    private suspend fun stage(task: CacheRow, value: String, progress: Long = task.progress, total: Long = task.total) {
        task.phase = value
        task.progress = progress
        task.total = total
        if (saveTransfer(task) == 0) throw CancellationException("Transfer cancelled")
        refreshTransfers()
    }
    private fun enforceAutomaticPolicy(data: JSONObject, mime: String) {
        if (!data.has("backupId") || data.optBoolean("manualRequested")) return
        if (!BackgroundBattery.backup(app)) throw DataPolicyBlocked("WAITING")
        val settings = policy.settings()
        if (!MediaBackupPolicy.automaticAllowed(settings.dataSaver, data.getString("backupId") == "camera:$account:$vault", mime, data.optLong("capturedAt", Long.MIN_VALUE), settings.saverSince)) throw DataPolicyBlocked("DATA_SAVER_AUTO_PAUSED")
        if (data.getString("backupId") == "camera:$account:$vault" && mime.startsWith("video/") && !AutomaticBackupPolicy.cameraVideoAllowed(data.optLong("sourceDuration"), settings.cameraVideoMinutes, settings.mobileVideos, settings.unlimited, policy.cellular())) throw DataPolicyBlocked("CAMERA_VIDEO_LIMIT")
    }
    private suspend fun upload(task: CacheRow, data: JSONObject, masterKey: ByteArray) {
        var contentGate: Mutex? = null
        try {
            val uri = Uri.parse(data.getString("uri"))
            if (uri.authority == "${app.packageName}.private-media" && state.value.stage != VaultStage.READY) throw DataPolicyBlocked("WAITING_UNLOCK")
            if (data.has("backupId") && !data.optBoolean("manualRequested") && !BackgroundBattery.backup(app)) throw DataPolicyBlocked("WAITING")
            val checkpoint = data.optJSONObject("metadata")
            val bytes = checkpoint?.getLong("size") ?: size(uri)
            VaultCrypto.encryptedLength(bytes)
            val detectedMime = checkpoint?.getString("mime") ?: MediaTools.mime(app, uri)
            if (!data.optBoolean("allowDuplicate")) {
                val baseline = dao.get("cloud-baseline:$account:$vault")
                if (baseline == null || !payload(baseline, masterKey).optBoolean("complete")) sync(requireComplete = true, backgroundTask = task)
                if (checkpoint == null && matchExistingBackup(task, data, masterKey, backupCopyIndex(masterKey), display = true)) return
            }
            if (data.optString("backupId") == "camera:$account:$vault" && !data.optBoolean("manualRequested") && detectedMime.startsWith("video/") && data.optLong("sourceDuration") <= 0) {
                data.put("sourceDuration", MediaFacts.read(app, uri, detectedMime).durationMs)
                savePayload(task, data, masterKey)
                dao.get("upload-queue:${task.id}")?.let { ticket ->
                    val value = payload(ticket, masterKey).put("duration", data.optLong("sourceDuration"))
                    dao.put(row(ticket.id, "upload-queue", value, masterKey))
                }
            }
            enforceAutomaticPolicy(data, detectedMime)
            if (data.has("backupId") && !data.optBoolean("manualRequested") && detectedMime.startsWith("video/") && !data.optBoolean("videoNoticeAcknowledged")) {
                if (!AutomaticVideoNotice.post(app)) {
                    notice("Automatic video backup is waiting. Allow notifications or acknowledge it in Library → Transfers before it uploads.")
                    throw DataPolicyBlocked("VIDEO_NOTICE_REQUIRED")
                }
                data.put("videoNoticeAcknowledged", true)
                savePayload(task, data, masterKey)
            }
            check(bytes <= 1536L * 1024 * 1024 || detectedMime.startsWith("video/")) { "Only video files support multipart upload above 1.5 GB" }
            val partBytes = minOf(1536L * 1024 * 1024, telegram.uploadLimit() - 1024)
            task.total = bytes
            val content = File(cache, "${task.id}.bin")
            if (!data.has("metadata") || (!content.exists() && (!data.getJSONObject("metadata").has("message") || data.getJSONObject("metadata").optBoolean("multipartPending")))) {
                streamCache.makeRoom(VaultCrypto.encryptedLength(bytes) + minOf(partBytes, VaultCrypto.encryptedLength(bytes)) + 16L * 1024 * 1024)
                if (app.noBackupFilesDir.usableSpace <= VaultCrypto.encryptedLength(bytes) + minOf(partBytes, VaultCrypto.encryptedLength(bytes)) + 16L * 1024 * 1024) throw DataPolicyBlocked("WAITING_STORAGE")
                stage(task, "ENCRYPTING", 0, bytes)
                val contentKey = VaultCrypto.randomKey()
                try {
                    val mime = MediaTools.mime(app, uri)
                    val currentContext = coroutineContext
                    var progressAt = 0L
                    val hash = app.contentResolver.openInputStream(uri)!!.use { input ->
                        FileOutputStream(content).use { output ->
                            val value = VaultCrypto.encrypt(input, output, contentKey, UUID.fromString(task.vault), UUID.fromString(task.id), bytes) { done ->
                                protection.requireAllowed()
                                currentContext.ensureActive()
                                task.progress = done
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - progressAt >= 500) {
                                    if (saveTransfer(task) == 0) throw CancellationException("Transfer cancelled")
                                    progressAt = now
                                }
                            }
                            output.fd.sync()
                            value
                        }
                    }
                    val hashText = encode(hash)
                    if (!data.optBoolean("allowDuplicate")) {
                        val gate = contentGates[(hashText.hashCode().ushr(1)) % contentGates.size]
                        gate.lock()
                        contentGate = gate
                    }
                    val duplicate = if (data.optBoolean("allowDuplicate")) {
                        null
                    } else {
                        dao.ids(account, vault, "file").asSequence().map { rowId ->
                            val id = rowId.removePrefix("file:")
                            metadataCache["$account:$vault:$id"] ?: file(payload(checkNotNull(dao.get(rowId)), masterKey))
                        }.firstOrNull { existing ->
                            if (existing.hash != hashText || existing.size != bytes) return@firstOrNull false
                            val props = dao.get("state:${existing.id}")?.let { payload(it, masterKey) }
                            BackupIdentity.same(existing.hash, existing.size, hashText, bytes, props?.optBoolean("purged") != true && (props?.optLong("trashedAt") ?: 0L) == 0L, false)
                        }
                    }
                    if (duplicate != null && data.optString("source").isNotBlank()) {
                        saveSourceLink(data.getString("source"), duplicate.id, masterKey)
                        content.delete()
                        if (uri.scheme == "file") {
                            val owned = File(checkNotNull(uri.path)).canonicalFile
                            if (owned.path.startsWith(File(app.cacheDir, "shared").canonicalPath + File.separator)) owned.delete()
                        }
                        return
                    }
                    val thumbnail = MediaTools.thumbnail(app, uri, mime)
                    val facts = MediaFacts.read(app, uri, mime)
                    val times = MediaTools.sourceTimes(app, uri)
                    val originalDate = CaptureTime.backup(facts.capturedAt, data.optLong("capturedAt"), if (facts.capturedAt > 0 || data.optLong("capturedAt") > 0) 0 else MediaTools.captureDate(app, uri), System.currentTimeMillis())
                    val metadata = JSONObject().put("version", 1).put("id", task.id).put("attempt", UUID.randomUUID().toString()).put("name", data.getString("name"))
                        .put("mime", mime).put("size", bytes).put("date", originalDate).put("sourceUri", uri.toString()).put("album", data.optString("album", "Uploads"))
                        .put("hash", encode(hash)).put("key", encode(contentKey)).put("source", data.optString("source"))
                        .put("thumbnail", if (thumbnail == null) "" else encode(VaultCrypto.seal(masterKey, thumbnail, context("thumbnail/${task.id}"))))
                        .put("facts", facts.json())
                        .put("sourceAddedAt", data.optLong("sourceAddedAt").takeIf { it > 0 } ?: times.first)
                        .put("sourceModifiedAt", data.optLong("sourceModifiedAt").takeIf { it > 0 } ?: times.second)
                    thumbnail?.fill(0)
                    data.put("metadata", metadata)
                    savePayload(task, data, masterKey)
                } finally {
                    contentKey.fill(0)
                }
            }
            val metadata = data.getJSONObject("metadata")
            if (contentGate == null && !data.optBoolean("allowDuplicate")) {
                val gate = contentGates[metadata.getString("hash").hashCode().ushr(1) % contentGates.size]
                gate.lock()
                contentGate = gate
                // A checkpoint may have waited while another job committed identical media.
                // Recheck before sending any parts; never abandon an already-sent manifest.
                if (!metadata.has("message") && (metadata.optJSONArray("parts")?.length() ?: 0) == 0 && data.optString("source").isNotBlank()) {
                    val duplicate = dao.ids(account, vault, "file").asSequence().map { rowId ->
                        val id = rowId.removePrefix("file:")
                        metadataCache["$account:$vault:$id"] ?: file(payload(checkNotNull(dao.get(rowId)), masterKey))
                    }.firstOrNull { existing ->
                        if (existing.hash != metadata.getString("hash") || existing.size != metadata.getLong("size")) return@firstOrNull false
                        val props = dao.get("state:${existing.id}")?.let { payload(it, masterKey) }
                        BackupIdentity.same(existing.hash, existing.size, metadata.getString("hash"), metadata.getLong("size"), props?.optBoolean("purged") != true && (props?.optLong("trashedAt") ?: 0L) == 0L, false)
                    }
                    if (duplicate != null) {
                        saveSourceLink(data.getString("source"), duplicate.id, masterKey)
                        content.delete()
                        return
                    }
                }
            }
            if (!metadata.has("message") || metadata.optBoolean("multipartPending")) {
                val parts = metadata.optJSONArray("parts") ?: JSONArray()
                val lengths = CipherParts.lengths(content.length(), partBytes)
                metadata.put("multipartPending", true)
                var offset = (0 until parts.length()).sumOf { parts.getJSONObject(it).getLong("bytes") }
                while (parts.length() < lengths.size) {
                    coroutineContext.ensureActive()
                    val piece = File(cache, "${task.id}-part.bin")
                    val count = lengths[parts.length()]
                    try {
                        val cancellation = coroutineContext
                        CipherParts.extract(content, piece, offset, count) { cancellation.ensureActive() }
                        val partMarker = "TGDRIVE_V1_DATA:${task.vault}:${task.id}:${metadata.optString("attempt", task.id)}:${parts.length()}:"
                        val reconciled = telegram.findDocuments(partMarker).filter { it.bytes == count }.maxByOrNull { it.messageId }
                        val mime = metadata.getString("mime")
                        var reservedMobile = policy.cellular()
                        if (reconciled == null) {
                            enforceAutomaticPolicy(data, mime)
                            policy.reserve(mime, count, data.optString("backupId") == "camera:$account:$vault", !data.has("backupId") || data.optBoolean("manualRequested"))
                        }
                        stage(task, "UPLOADING", offset, content.length())
                        var last = 0L
                        val start = offset
                        val reference = reconciled ?: telegram.sendDocument(piece, partMarker) { done, _ ->
                            enforceAutomaticPolicy(data, mime)
                            if (policy.cellular()) {
                                if (data.has("backupId") && !data.optBoolean("manualRequested") && mime.startsWith("video/") && data.optString("backupId") != "camera:$account:$vault" && !policy.settings().let { it.mobileVideos || it.unlimited }) throw DataPolicyBlocked("MOBILE_VIDEO_DISABLED")
                                if (!reservedMobile) {
                                    enforceAutomaticPolicy(data, mime)
                                    policy.reserve(mime, count, data.optString("backupId") == "camera:$account:$vault", !data.has("backupId") || data.optBoolean("manualRequested"))
                                    reservedMobile = true
                                }
                            }
                            task.progress = start + done
                            if (System.currentTimeMillis() - last > 500) {
                                if (saveTransfer(task) == 0) throw CancellationException("Transfer cancelled")
                                last = System.currentTimeMillis()
                            }
                        }
                        parts.put(JSONObject().put("chat", reference.chatId).put("message", reference.messageId).put("bytes", reference.bytes))
                        metadata.put("parts", parts)
                        val first = parts.getJSONObject(0)
                        metadata.put("chat", first.getLong("chat")).put("message", first.getLong("message")).put("cipherSize", first.getLong("bytes"))
                        savePayload(task, data, masterKey)
                        offset += count
                    } finally {
                        piece.delete()
                    }
                }
                metadata.remove("multipartPending")
                savePayload(task, data, masterKey)
            }
            stage(task, "COMMITTING_INDEX")
            val manifest = File(cache, "${UUID.randomUUID()}.bin")
            try {
                metadata.put("backedUpAt", System.currentTimeMillis())
                manifest.writeBytes(sealJson(masterKey, metadata, context("manifest")))
                enforceAutomaticPolicy(data, metadata.getString("mime"))
                policy.reserve(metadata.getString("mime"), manifest.length(), data.optString("backupId") == "camera:$account:$vault", !data.has("backupId") || data.optBoolean("manualRequested"))
                val committed = telegram.sendDocument(manifest, "TGDRIVE_V1_FILE:${task.vault}:${task.id}") { _, _ -> }
                metadata.put("catalogMessage", committed.messageId).put("backedUpAt", BackupBadge.completedAt(System.currentTimeMillis(), committed.confirmedAtMillis))
                dao.put(row("file:${task.id}", "file", metadata, masterKey))
                val source = data.optString("source")
                if (source.isNotBlank()) saveSourceLink(source, task.id, masterKey)
                // The next guarded refresh publishes non-secret display metadata only.
            } finally {
                manifest.delete()
            }
            content.delete()
            pruneCache()
            if (uri.scheme == "file") {
                val owned = File(checkNotNull(uri.path)).canonicalFile
                if (owned.path.startsWith(File(app.cacheDir, "shared").canonicalPath + File.separator)) owned.delete()
            }
        } finally {
            contentGate?.unlock()
        }
    }

    private suspend fun export(task: CacheRow, data: JSONObject, masterKey: ByteArray) {
        val item = file(data.getJSONObject("file"))
        stage(task, "DOWNLOADING", 0, item.size)
        var last = 0L
        if (state.value.stage != VaultStage.READY) throw DataPolicyBlocked("WAITING_UNLOCK")
        val reader = authenticatedReader(item.id)
        var destination: Uri? = null
        var successful = false
        try {
            stage(task, "EXPORTING", 0, item.size)
            destination = if (data.has("createdUri")) {
                Uri.parse(data.getString("createdUri"))
            } else {
                val tree = checkNotNull(DocumentFile.fromTreeUri(app, Uri.parse(data.getString("destination"))))
                val name = item.name.replace(Regex("[\\/\\\\\\u0000-\\u001f]"), "_").take(240).ifBlank { "File" }
                val created = checkNotNull(tree.createFile(item.mime, name))
                data.put("createdUri", created.uri.toString())
                savePayload(task, data, masterKey)
                created.uri
            }
            val currentContext = coroutineContext
            val digest = MessageDigest.getInstance("SHA-256")
            app.contentResolver.openOutputStream(destination, "wt")!!.use { output ->
                val buffer = ByteArray(65536)
                try {
                    var position = 0L
                    while (position < item.size) {
                        currentContext.ensureActive()
                        val count = reader.read(position, buffer, 0, minOf(buffer.size.toLong(), item.size - position).toInt())
                        check(count > 0)
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        position += count
                        task.progress = position
                        if (System.currentTimeMillis() - last > 500) {
                            if (saveTransfer(task) == 0) throw CancellationException("Transfer cancelled")
                            last = System.currentTimeMillis()
                        }
                    }
                    check(MessageDigest.isEqual(digest.digest(), decode(item.hash))) { "Exported plaintext differs" }
                } finally {
                    buffer.fill(0)
                }
            }
            val readBack = MessageDigest.getInstance("SHA-256")
            app.contentResolver.openInputStream(destination)!!.use { input ->
                val buffer = ByteArray(65536)
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        readBack.update(buffer, 0, count)
                    }
                } finally {
                    buffer.fill(0)
                }
            }
            check(MessageDigest.isEqual(readBack.digest(), decode(item.hash))) { "Exported file differs" }
            successful = true
        } finally {
            closeReader(reader)
            if (!successful && destination != null) {
                runCatching { DocumentFile.fromSingleUri(app, destination)?.delete() }
                data.remove("createdUri")
                runCatching { savePayload(task, data, masterKey) }
            }
        }
    }
    suspend fun openPreview(id: String): Uri = withContext(Dispatchers.IO) { privateMediaUri(id) }
    fun cachedThumbnail(id: String, pixels: Int): Bitmap? = synchronized(this) {
        if (state.value.stage != VaultStage.READY) return@synchronized null
        for (tier in intArrayOf(96, 192, 384, 640, 768, 960, 1024, 1280, 1536)) if (ThumbnailQuality.reusableTier(pixels, tier)) thumbnails.get("$id:$tier")?.let { return@synchronized it }
        null
    }
    fun canPrefetchPhotos() = runningTransfers.isEmpty() && !state.value.uploadSettings.dataSaver && !policy.cellular()
    suspend fun thumbnail(id: String, pixels: Int = 384): Bitmap? = withContext(Dispatchers.IO) {
        val epoch = accessEpoch.get()
        if (state.value.stage != VaultStage.READY) return@withContext null
        cachedThumbnail(id, pixels)?.let { return@withContext it }
        if (state.value.files.none { it.id == id }) return@withContext null
        val key = unlockedKey()
        try {
            val record = dao.get("file:$id") ?: return@withContext null
            val thumbnail = payload(record, key).getString("thumbnail")
            if (thumbnail.isEmpty()) return@withContext null
            val bytes = VaultCrypto.open(key, decode(thumbnail), context("thumbnail/$id"))
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = ThumbnailQuality.sample(bounds.outWidth, bounds.outHeight, pixels) })?.let { bitmap ->
                    synchronized(this@DriveRepository) {
                        if (master != null && state.value.stage == VaultStage.READY && accessEpoch.get() == epoch) {
                            thumbnails.put("$id:$pixels", bitmap)
                            bitmap
                        } else {
                            bitmap.recycle()
                            null
                        }
                    }
                }
            } finally {
                bytes.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    suspend fun addBackup(uri: Uri, wifiOnly: Boolean) = withContext(Dispatchers.IO) {
        backupControl.withLock {
            val key = unlockedKey()
            try {
                val directory = checkNotNull(DocumentFile.fromTreeUri(app, uri))
                require(uri.scheme == "content" && android.provider.DocumentsContract.isTreeUri(uri))
                check(directory.canRead())
                val existing = dao.list(account, vault, "backup").filter { payload(it, key).optString("uri") == uri.toString() }
                val id = existing.firstOrNull()?.id ?: UUID.randomUUID().toString()
                existing.drop(1).forEach { stopBackupLocked(it.id, key) }
                val backup = row(id, "backup", JSONObject().put("uri", uri.toString()).put("name", directory.name ?: "Backup").put("wifi", wifiOnly), key)
                dao.put(backup)
                work.cancelUniqueWork("backup:$id")
                val scan = OneTimeWorkRequestBuilder<FolderBackupWorker>().setInputData(workDataOf("folder" to id)).addTag("tgdrive").build()
                work.enqueueUniqueWork("backup-now:$id", ExistingWorkPolicy.KEEP, scan)
                watchCamera(id)
                refreshLocal()
            } finally {
                key.fill(0)
            }
        }
    }
    private suspend fun stopBackupLocked(id: String, key: ByteArray) = uploadQueue.withLock {
        work.cancelUniqueWork("backup:$id")
        work.cancelUniqueWork("backup-now:$id")
        work.cancelUniqueWork("camera-watch:$id")
        activeJobs(account, vault).filter { payload(it, key).optString("backupId") == id && dao.get("upload-queue:${it.id}")?.let { ticket -> payload(ticket, key).optBoolean("manual") } != true }.forEach { job ->
            job.phase = "CANCELLED"
            dao.put(job)
            work.cancelUniqueWork("transfer:${job.id}")
            dao.remove("upload-queue:${job.id}")
        }
        dao.remove(id)
    }
    suspend fun stopBackup(id: String) = withContext(Dispatchers.IO) {
        backupControl.withLock {
            val key = unlockedKey()
            try {
                stopBackupLocked(id, key)
                if (id.startsWith("camera:")) {
                    val current = policy.settings()
                    policy.update(current.photoLimitMb, current.mobileVideos, false)
                }
                refreshLocal()
            } finally {
                key.fill(0)
            }
        }
    }
    private suspend fun migrateCameraBackups() {
        backupControl.withLock {
            val key = unlockedKey()
            try {
                val duplicates = dao.list(account, vault, "backup").filter { row ->
                    val data = payload(row, key)
                    data.has("deviceFolder") && DeviceBackupFolder.matches(data.getString("deviceFolder"), "", true)
                }
                if (duplicates.isNotEmpty()) {
                    duplicates.forEach { stopBackupLocked(it.id, key) }
                    val current = policy.settings()
                    policy.update(current.photoLimitMb, current.mobileVideos, true)
                    startCameraLocked(key)
                    watchCamera("camera:$account:$vault")
                }
            } finally {
                key.fill(0)
            }
        }
    }
    private fun startCameraLocked(key: ByteArray) {
        val id = "camera:$account:$vault"
        dao.put(row(id, "backup", JSONObject().put("uri", "camera").put("name", "Camera").put("wifi", false), key))
        work.cancelUniqueWork("backup:$id")
        val now = OneTimeWorkRequestBuilder<FolderBackupWorker>().setInputData(workDataOf("folder" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("tgdrive").build()
        work.enqueueUniqueWork("backup-now:$id", ExistingWorkPolicy.KEEP, now)
    }
    private suspend fun backupMedia(): List<LocalMedia> {
        val now = android.os.SystemClock.elapsedRealtime()
        val mediaStamp = if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { android.provider.MediaStore.getExternalVolumeNames(app).sorted().joinToString { "$it:${android.provider.MediaStore.getGeneration(app, it)}" } }.getOrDefault("")
        } else {
            ""
        }
        val permissions = listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED").joinToString { "$it:${androidx.core.content.ContextCompat.checkSelfPermission(app, it)}" }
        val stamp = "$mediaStamp|$permissions"
        val limited = android.os.Build.VERSION.SDK_INT >= 34 && androidx.core.content.ContextCompat.checkSelfPermission(app, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == android.content.pm.PackageManager.PERMISSION_GRANTED
        val ttl = if (mediaStamp.isNotBlank() && !limited) 10000L else 1000L
        val cached = backupMediaSnapshot
        if (cached != null && stamp == backupMediaStamp && now - backupMediaAt < ttl) return cached
        return localMedia.load(includeAllFiles = false).also {
            backupMediaSnapshot = it
            backupMediaAt = now
            backupMediaStamp = stamp
            CoroutineScope(Dispatchers.IO).launch {
                delay(ttl)
                if (backupMediaAt == now) backupMediaSnapshot = null
            }
        }
    }
    suspend fun scanBackup(id: String) = withContext(Dispatchers.IO) {
        if (state.value.indexNeedsRepair) throw DataPolicyBlocked("INDEX_REPAIR_REQUIRED")
        if (!BackgroundBattery.backup(app)) {
            BackupScheduling.whenCharging(app)
            return@withContext
        }
        backupScanning.withLock {
            val record = dao.get(id) ?: return@withLock
            val key = backgroundKey(record)
            try {
                val data = payload(record, key)
                val saver = policy.settings()
                val baseline = dao.get("cloud-baseline:$account:$vault")
                if (baseline == null || !payload(baseline, key).optBoolean("complete")) sync(requireComplete = true, backgroundTask = record)
                reconcileWaitingBackups(record, key)
                // Reconsider the durable queue once, retaining limits, notices and retry timestamps.
                pendingUploads(record, key).firstOrNull { uploadEligible(it.first, it.second) }?.let { scheduleUploadQueue(it.first, it.second.optBoolean("manual")) }
                if (saver.dataSaver && !id.startsWith("camera:")) return@withLock
                if (data.optString("uri") == "camera" || data.has("deviceFolder")) {
                    val legacyCamera = data.optString("uri") == "camera"
                    val media = backupMedia().filter { !it.trashed && DeviceBackupFolder.matches(it.folder, data.optString("deviceFolder"), legacyCamera) && (it.mime.startsWith("image/") || it.mime.startsWith("video/")) }
                    val known = verifiedBackupSources(record.account, record.vault, key)
                    known += activeJobs(record.account, record.vault).map { payload(it, key).optString("source") }
                    val candidates = mutableListOf<CacheRow>()
                    media.filter { it.fingerprint !in known && MediaBackupPolicy.automaticAllowed(saver.dataSaver, id == "camera:$account:$vault", it.mime, it.date, saver.saverSince) }.forEach { item ->
                        coroutineContext.ensureActive()
                        if (dao.get(id) == null) return@withLock
                        val jobId = UUID.randomUUID().toString()
                        val job = JSONObject().put("action", "upload").put("uri", item.uri.toString()).put("name", item.name).put("album", data.getString("name")).put("source", item.fingerprint).put("backupId", id).put("sourceMime", item.mime).put("sourceDuration", item.durationMs).put("capturedAt", item.date).put("sourceAddedAt", item.addedAt).put("sourceModifiedAt", CaptureTime.milliseconds(item.modified))
                        val task = row(jobId, "job", job, key, "WAITING")
                        task.total = item.size
                        candidates += task
                    }
                    queueAutomaticBatch(candidates, data.optBoolean("wifi", false), key)
                    return@withLock
                }
                val directory = checkNotNull(DocumentFile.fromTreeUri(app, Uri.parse(data.getString("uri"))))
                check(directory.canRead()) { "Folder permission lost" }
                val known = verifiedBackupSources(record.account, record.vault, key)
                known += activeJobs(record.account, record.vault).map { payload(it, key).optString("source") }
                val candidates = mutableListOf<CacheRow>()
                var scanned = 0
                suspend fun visit(folder: DocumentFile, depth: Int) {
                    check(depth <= 32)
                    for (entry in folder.listFiles()) {
                        coroutineContext.ensureActive()
                        if (dao.get(id) == null) return
                        check(++scanned <= 50000) { "Folder scan limit reached" }
                        if (entry.isDirectory) {
                            if (!DeviceFileVisibility.visible(entry.name)) continue
                            visit(entry, depth + 1)
                            continue
                        }
                        if (!entry.isFile) continue
                        val length = sizeHint(entry.uri)
                        val token = encode(MessageDigest.getInstance("SHA-256").digest("$id|${entry.uri}|${length.coerceAtLeast(0)}|${entry.lastModified()}".toByteArray()))
                        if (token in known) continue
                        val jobId = UUID.randomUUID().toString()
                        val jobData = JSONObject().put("action", "upload").put("uri", entry.uri.toString()).put("name", entry.name ?: "File")
                            .put("album", data.getString("name")).put("sourceMime", MediaTools.mime(app, entry.uri)).put("source", token).put("backupId", id).put("sourceModifiedAt", entry.lastModified())
                        val task = row(jobId, "job", jobData, key, "WAITING")
                        task.total = length
                        candidates += task
                        known += token
                    }
                }
                visit(directory, 0)
                queueAutomaticBatch(candidates, data.optBoolean("wifi", true), key)
            } finally {
                releaseWorkKey(key)
            }
        }
    }
    private fun pruneCache() {
        val files = encryptedCache.listFiles()?.filter { it.extension == "bin" }?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        files.forEach {
            if (bytes > 192L * 1024 * 1024 || System.currentTimeMillis() - it.lastModified() >= CipherRangeCache.TTL) {
                bytes -= it.length()
                it.delete()
            }
        }
    }
    suspend fun preferredDeviceSource(id: String, localOnly: Boolean = false): Uri? = withContext(Dispatchers.IO) {
        val epoch = accessEpoch.get()
        if (state.value.stage != VaultStage.READY) return@withContext null
        val file = state.value.files.find { it.id == id && it.trashedAt == 0L } ?: return@withContext null
        val items = localMedia.changes.value.let { if (localOnly) it.filter { item -> onDevicePreviewSource(item.uri) } else it }
        val copy = deviceCopies.preferred(file, items, state.value.backedDeviceFiles) ?: return@withContext null
        copy.uri.takeIf { epoch == accessEpoch.get() && state.value.stage == VaultStage.READY && state.value.files.any { it.id == id && it.hash == file.hash && it.trashedAt == 0L } }
    }
    internal suspend fun scrubPreview(id: String, key: String, local: Uri?, cloud: Boolean, width: Int, height: Int, adjacent: Boolean, allowCloud: Boolean, owner: PreviewRequestOwner, cover: Boolean = false) {
        if (adjacent || cover) photoPriority.await(id)
        val epoch = photoPreviews.epoch()
        val access = accessEpoch.get()
        photoPreviews.get(key)?.let { frame ->
            val rotated = frame.orientation in 5..8
            val target = if (cover) ScrubPreviewQuality.cover(frame.width, frame.height, if (rotated) height else width, if (rotated) width else height, photoPreviews.tilePixelBudget()) else ScrubPreviewQuality.fit(frame.width, frame.height, if (rotated) height else width, if (rotated) width else height, photoPreviews.scrubPixelBudget(), adjacent)
            if (photoPreviews.sufficient(key, target.width(), target.height())) return
        }
        if ((adjacent || cover) && local != null && !onDevicePreviewSource(local)) return
        val device = local ?: if (cloud) preferredDeviceSource(id, localOnly = adjacent || cover) else null
        owner.ensureOpen()
        val source = device ?: if (cloud && allowCloud && access == accessEpoch.get() && state.value.stage == VaultStage.READY) privateMediaUri(id) else return
        if (access != accessEpoch.get()) return
        ScrubPhotoDecoder.decode(app, source, photoPreviews, key, epoch, width, height, adjacent, owner, cover) { !(adjacent || cover) || photoPriority.permits(id) }
    }
    private fun onDevicePreviewSource(uri: Uri) = uri.scheme == "file" || uri.scheme == "content" && uri.authority in setOf("media", "com.android.externalstorage.documents", "com.android.providers.media.documents", "com.android.providers.downloads.documents")
    suspend fun verifiedDeviceCopies(cloudIds: Set<String>? = null, sources: Set<String>? = null): List<BackedDeviceCopy> = withContext(Dispatchers.IO) {
        deviceCopyChecks.withLock {
            val epoch = accessEpoch.get()
            check(state.value.stage == VaultStage.READY && !state.value.indexNeedsRepair)
            val files = state.value.files.filter { it.trashedAt == 0L && (cloudIds == null || it.id in cloudIds) }.groupBy { it.size }
            val items = localMedia.changes.value.filter { !it.trashed && it.size in files && (sources == null || it.fingerprint in sources) }
            val result = mutableListOf<BackedDeviceCopy>()
            try {
                items.forEachIndexed { index, item ->
                    coroutineContext.ensureActive()
                    publishUnlocked(epoch) { it.copy(deviceCopyProgress = WorkProgress("Verifying device copies", index.toLong(), items.size.toLong())) }
                    val hash = deviceCopies.verifiedHash(item, true)
                    files[item.size]?.find { it.hash == hash }?.let { result += BackedDeviceCopy(item, it.id) }
                }
                if (epoch != accessEpoch.get() || state.value.stage != VaultStage.READY) return@withLock emptyList()
                result.filter { copy -> state.value.files.any { it.id == copy.cloudId && it.trashedAt == 0L } }
            } finally {
                publishUnlocked(epoch) { it.copy(deviceCopyProgress = null) }
            }
        }
    }
    suspend fun backupAndTrash(items: List<LocalMedia>, origin: String) = withContext(Dispatchers.IO) {
        val originals = items.filter { !it.trashed }.distinctBy { it.uri }
        require(originals.size <= 50000)
        check(state.value.stage == VaultStage.READY && !state.value.indexNeedsRepair)
        val key = unlockedKey()
        try {
            originals.forEach { item ->
                val id = "device-cleanup:" + UUID.randomUUID().toString()
                dao.put(row(id, "device-cleanup", JSONObject().put("uri", item.uri.toString()).put("source", item.fingerprint).put("origin", origin).put("requestedAt", System.currentTimeMillis()), key))
            }
            enqueueUpload(originals.map { it.uri }, sourceTokens = originals.associate { it.uri.toString() to it.fingerprint })
            refreshLocal()
        } finally {
            key.fill(0)
        }
    }
    suspend fun finishDeviceCleanup(ids: Set<String>) = withContext(Dispatchers.IO) {
        ids.forEach { id -> dao.get(id)?.takeIf { it.account == account && it.vault == vault && it.kind == "device-cleanup" }?.let { dao.remove(id) } }
        refreshLocal()
    }
    private fun sourceFingerprint(uri: Uri): String {
        if (uri.scheme == "file") {
            val file = File(checkNotNull(uri.path))
            return "$uri|${file.length()}|${file.lastModified() / 1000}"
        }
        val known = runCatching { app.contentResolver.query(uri, arrayOf("_size", "date_modified"), null, null, null)?.use { if (it.moveToFirst()) "$uri|${it.getLong(0)}|${it.getLong(1)}" else null } }.getOrNull()
        return known ?: "$uri|${size(uri)}|${runCatching { DocumentFile.fromSingleUri(app, uri)?.lastModified() }.getOrNull() ?: 0}"
    }
    val playbackEpoch: Long get() = accessEpoch.get()
    private fun playbackIdentity(identity: String, key: ByteArray): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
        return encode(mac.doFinal(identity.toByteArray()))
    }
    suspend fun playbackPosition(identity: String, epoch: Long): Long = withContext(Dispatchers.IO) {
        playbackHistory.withLock {
            if (epoch != accessEpoch.get() || state.value.stage != VaultStage.READY) return@withLock 0L
            val key = unlockedKey()
            try {
                val ledger = dao.get("playback-history:$account:$vault")?.let { payload(it, key) } ?: return@withLock 0L
                val saved = ledger.optJSONObject(playbackIdentity(identity, key)) ?: return@withLock 0L
                VideoPresentation.resumePosition(saved.optLong("position"), saved.optLong("duration"), saved.optBoolean("ended"))
            } finally {
                key.fill(0)
            }
        }
    }
    suspend fun savePlaybackPosition(identity: String, position: Long, duration: Long, ended: Boolean, epoch: Long) = withContext(Dispatchers.IO) {
        playbackHistory.withLock {
            if (epoch != accessEpoch.get() || state.value.stage != VaultStage.READY || duration <= 0) return@withLock
            val key = unlockedKey()
            try {
                val rowId = "playback-history:$account:$vault"
                val ledger = dao.get(rowId)?.let { payload(it, key) } ?: JSONObject()
                val id = playbackIdentity(identity, key)
                ledger.put(id, JSONObject().put("position", position.coerceIn(0, duration)).put("duration", duration).put("ended", ended).put("updated", System.currentTimeMillis()))
                val oldest = ledger.keys().asSequence().toList().sortedBy { ledger.getJSONObject(it).optLong("updated") }
                oldest.take((oldest.size - 200).coerceAtLeast(0)).forEach { ledger.remove(it) }
                val saved = row(rowId, "playback-history", ledger, key)
                if (epoch == accessEpoch.get() && state.value.stage == VaultStage.READY) dao.put(saved)
            } finally {
                key.fill(0)
            }
        }
    }
    suspend fun privateNote(id: String): String = withContext(Dispatchers.IO) {
        val key = unlockedKey()
        try {
            dao.get("private-note:$account:$vault:$id")?.let { payload(it, key).optString("text") }.orEmpty()
        } finally {
            key.fill(0)
        }
    }
    suspend fun savePrivateNote(id: String, text: String) = withContext(Dispatchers.IO) {
        require(text.length <= 4096)
        val key = unlockedKey()
        try {
            val rowId = "private-note:$account:$vault:$id"
            if (text.isBlank()) dao.remove(rowId) else dao.put(row(rowId, "private-note", JSONObject().put("text", text), key))
        } finally {
            key.fill(0)
        }
    }
    suspend fun deviceTrashOrigin(uris: List<Uri>, origin: String) = withContext(Dispatchers.IO) {
        reconciliation.withLock {
            val key = unlockedKey()
            try {
                uris.forEach { uri ->
                    val id = "local-trash:" + encode(MessageDigest.getInstance("SHA-256").digest("$account:$vault:$uri".toByteArray()))
                    val data = dao.get(id)?.let { payload(it, key) } ?: JSONObject().put("uri", uri.toString()).put("trashedAt", System.currentTimeMillis())
                    data.put("origin", origin)
                    dao.put(row(id, "local-trash", data, key))
                }
                refreshLocal()
            } finally {
                key.fill(0)
            }
        }
    }
    suspend fun localFavorite(uri: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        val id = "local-favorite:" + MessageDigest.getInstance("SHA-256").digest("$account:$vault:$uri".toByteArray()).joinToString("") { "%02x".format(it) }
        val key = unlockedKey()
        try {
            if (favorite) dao.put(row(id, "local-favorite", JSONObject().put("uri", uri), key)) else dao.remove(id)
            refreshLocal()
        } finally {
            key.fill(0)
        }
    }
    private fun verifiedBackupSources(ownerAccount: Long, ownerVault: String, key: ByteArray): MutableSet<String> {
        val retainedIds = dao.ids(ownerAccount, ownerVault, "file").mapNotNull { id ->
            val item = file(payload(checkNotNull(dao.get(id)), key))
            val props = dao.get("state:${item.id}")?.let { payload(it, key) }
            if (props?.optBoolean("purged") == true) null else item.id
        }.toSet()
        val receipts = dao.list(ownerAccount, ownerVault, "source-link").associate { row ->
            val link = payload(row, key)
            link.getString("source") to link.getString("file")
        }
        return LocalBackupReceipts.retained(retainedIds, receipts).keys.toMutableSet()
    }
    private fun saveSourceLink(source: String, fileId: String, key: ByteArray) {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply { init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256")) }
        val id = "source-link:$account:$vault:" + encode(mac.doFinal(source.toByteArray()))
        dao.put(row(id, "source-link", JSONObject().put("source", source).put("file", fileId), key))
    }
    suspend fun reconcileDeviceMedia(items: List<LocalMedia>) = withContext(Dispatchers.IO) {
        reconciliation.withLock {
            if (state.value.stage != VaultStage.READY) return@withLock
            val existing = state.value.files.filter { it.trashedAt == 0L }.groupBy { it.size }
            val known = state.value.backedSources
            val key = unlockedKey()
            try {
                var linked = 0
                for (item in items) {
                    val id = "local-trash:" + encode(MessageDigest.getInstance("SHA-256").digest("$account:$vault:${item.uri}".toByteArray()))
                    val previous = dao.get(id)
                    if (item.trashed && previous == null) {
                        dao.put(row(id, "local-trash", JSONObject().put("uri", item.uri.toString()).put("trashedAt", System.currentTimeMillis()), key))
                        linked++
                    } else if (!item.trashed && previous != null) {
                        dao.remove(id)
                        linked++
                    }
                }
                for (item in items.filter { it.fingerprint !in known && it.size in existing }) {
                    coroutineContext.ensureActive()
                    val cachedHash = reconciledHashes.get(item.fingerprint)
                    if (cachedHash != null) {
                        existing[item.size]?.find { it.hash == cachedHash }?.let { match ->
                            saveSourceLink(item.fingerprint, match.id, key)
                            linked++
                        }
                        continue
                    }
                    val hash = deviceCopies.verifiedHash(item, fresh = true) ?: continue
                    reconciledHashes.put(item.fingerprint, hash)
                    existing[item.size]?.find { it.hash == hash }?.let { match ->
                        saveSourceLink(item.fingerprint, match.id, key)
                        linked++
                    }
                }
                if (linked > 0) refreshLocal()
            } finally {
                key.fill(0)
            }
        }
    }
    internal fun currentAccessEpoch(): Long {
        check(state.value.stage == VaultStage.READY)
        return accessEpoch.get()
    }
    fun shareMedia(source: Uri?, cloudId: String?, name: String, mime: String, size: Long): Uri = PrivateMediaGrants.create(app.packageName, source, cloudId, name, mime, size, currentAccessEpoch())
    fun privateMediaUri(id: String): Uri {
        streamingFile(id)
        return Uri.parse("content://${app.packageName}.private-media/media/$id")
    }
    internal suspend fun authenticatedReader(id: String, readJob: Job? = null, speculative: Boolean = false): AuthenticatedRecords = withContext(Dispatchers.IO) {
        val (item, vaultId) = streamingFile(id)
        val epoch = accessEpoch.get()
        val parts = item.parts.ifEmpty { listOf(item.data) }
        val masterCopy = unlockedKey()
        val key = try {
            val record = checkNotNull(dao.get("file:$id"))
            check(record.account == account && record.vault == vaultId)
            decode(payload(record, masterCopy).getString("key"))
        } finally {
            masterCopy.fill(0)
        }
        try {
            AuthenticatedRecords(
                AuthenticatedRecords.CipherInput { offset, output, destination, requested ->
                    protection.requireAllowed()
                    check(state.value.stage == VaultStage.READY && vault == vaultId && accessEpoch.get() == epoch) { "Vault locked or changed" }
                    val local = (if (item.id in offlineMutable.value.ids) offlineStore.read(offlineScope(item.id), parts.sumOf { it.bytes }, offset, requested) else null) ?: cachedUploadCipher(item.id, parts.sumOf { it.bytes }, offset, requested)
                    if (local != null) {
                        try {
                            protection.requireAllowed()
                            check(state.value.stage == VaultStage.READY && vault == vaultId && accessEpoch.get() == epoch)
                            System.arraycopy(local, 0, output, destination, requested)
                        } finally {
                            local.fill(0)
                        }
                        return@CipherInput
                    }
                    var position = offset
                    var count = requested
                    var target = destination
                    for (part in parts) {
                        if (position >= part.bytes) {
                            position -= part.bytes
                            continue
                        }
                        val amount = minOf(count.toLong(), part.bytes - position).toInt()
                        val bytes = readStreamCipher("${item.id}/${part.chatId}/${part.messageId}", part.bytes, position, amount, item.mime.startsWith("image/")) { at, length ->
                            kotlinx.coroutines.runBlocking((readJob ?: kotlin.coroutines.EmptyCoroutineContext) + Dispatchers.IO) { if (speculative) telegram.readSpeculativeDocumentPart(part, at, length) else telegram.readDocumentPart(part, at, length) }
                        }
                        protection.requireAllowed()
                        check(state.value.stage == VaultStage.READY && vault == vaultId && accessEpoch.get() == epoch) { "Vault locked or changed" }
                        try {
                            System.arraycopy(bytes, 0, output, target, amount)
                        } finally {
                            bytes.fill(0)
                        }
                        count -= amount
                        target += amount
                        position = 0
                        if (count == 0) break
                    }
                    check(count == 0) { "Missing ciphertext" }
                },
                key,
                UUID.fromString(vaultId),
                UUID.fromString(item.id),
                item.size,
                parts.sumOf { it.bytes }
            ).also { reader ->
                synchronized(this@DriveRepository) {
                    if (master == null || vault != vaultId || accessEpoch.get() != epoch) {
                        reader.close()
                        error("Vault locked")
                    }
                    activeReaders.add(reader)
                }
            }
        } finally {
            key.fill(0)
        }
    }

    @Synchronized private fun ensureKeyActive(key: ByteArray) {
        protection.requireAllowed()
        check(state.value.stage == VaultStage.READY && master?.let { java.security.MessageDigest.isEqual(it, key) } == true) { "Drive locked or changed" }
    }
    private fun offlineScope(id: String) = "$account:$vault:${UUID.fromString(id)}"
    private fun offlineRow(id: String) = "offline:${offlineScope(id)}"
    private fun refreshOffline(key: ByteArray, files: List<DriveFile>, epoch: Long) {
        val byId = files.associateBy { it.id }
        val ids = dao.list(account, vault, "offline").mapNotNull { record ->
            verifiedRecord {
                val data = payload(record, key)
                val id = UUID.fromString(data.getString("file")).toString()
                val item = byId[id]
                val scope = offlineScope(id)
                if (item != null && item.trashedAt == 0L && item.hash == data.getString("hash") && offlineStore.contains(scope, data.getLong("bytes"))) {
                    id
                } else {
                    offlineStore.remove(scope)
                    dao.remove(record.id)
                    null
                }
            }
        }.toSet()
        val quota = dao.get("offline-settings:$account:$vault")?.let { verifiedRecord { payload(it, key).getLong("quota").coerceIn(128L * 1024 * 1024, 8L * 1024 * 1024 * 1024) } } ?: 512L * 1024 * 1024
        synchronized(this) {
            if (state.value.stage == VaultStage.READY && accessEpoch.get() == epoch) offlineMutable.update { it.copy(ids = ids, bytes = offlineStore.bytes(), quota = quota) }
        }
    }
    suspend fun setOfflineQuota(bytes: Long) = withContext(Dispatchers.IO) {
        offlineControl.withLock {
            require(bytes in 128L * 1024 * 1024..8L * 1024 * 1024 * 1024)
            check(bytes >= offlineStore.bytes()) { "Remove offline copies before lowering the quota." }
            val epoch = currentAccessEpoch()
            val key = synchronized(this@DriveRepository) { unlockedKey().also { activeWorkKeys.incrementAndGet() } }
            try {
                ensureKeyActive(key)
                dao.put(row("offline-settings:$account:$vault", "offline-settings", JSONObject().put("quota", bytes), key))
                refreshOffline(key, state.value.files, epoch)
            } finally {
                releaseWorkKey(key)
            }
        }
    }
    suspend fun removeOffline(ids: Set<String>) = withContext(Dispatchers.IO) {
        offlineControl.withLock {
            val epoch = currentAccessEpoch()
            val key = synchronized(this@DriveRepository) { unlockedKey().also { activeWorkKeys.incrementAndGet() } }
            try {
                ids.forEach { id ->
                    ensureKeyActive(key)
                    offlineStore.remove(offlineScope(id))
                    dao.remove(offlineRow(id))
                }
                refreshOffline(key, state.value.files, epoch)
            } finally {
                releaseWorkKey(key)
            }
        }
    }
    suspend fun pinOffline(ids: Set<String>) = withContext(Dispatchers.IO) {
        offlineControl.withLock {
            check(!policy.settings().dataSaver) { "Turn off Data Saver to save encrypted offline copies." }
            val epoch = currentAccessEpoch()
            val selectedAccount = account
            val selectedVault = vault
            val masterKey = synchronized(this@DriveRepository) { unlockedKey().also { activeWorkKeys.incrementAndGet() } }
            try {
                for (id in ids) {
                    coroutineContext.ensureActive()
                    ensureKeyActive(masterKey)
                    val item = checkNotNull(state.value.files.find { it.id == id && it.trashedAt == 0L })
                    if (id in offlineMutable.value.ids) continue
                    val parts = item.parts.ifEmpty { listOf(item.data) }
                    val length = parts.sumOf { it.bytes }
                    val key = decode(payload(checkNotNull(dao.get("file:$id")), masterKey).getString("key"))
                    fun access() {
                        protection.requireAllowed()
                        check(account == selectedAccount && vault == selectedVault && accessEpoch.get() == epoch && state.value.stage == VaultStage.READY)
                        check(!policy.settings().dataSaver)
                    }
                    access()
                    offlineMutable.update { it.copy(activeId = id, progress = 0, total = length) }
                    try {
                        offlineStore.save(offlineScope(id), length, offlineMutable.value.quota, { offset, count ->
                            access()
                            kotlinx.coroutines.runBlocking(coroutineContext) { coroutineContext.ensureActive() }
                            val result = ByteArray(count)
                            var position = offset
                            var remaining = count
                            var target = 0
                            try {
                                for (part in parts) {
                                    if (position >= part.bytes) {
                                        position -= part.bytes
                                        continue
                                    }
                                    val amount = minOf(remaining.toLong(), part.bytes - position).toInt()
                                    val chunk = readStreamCipher("${item.id}/${part.chatId}/${part.messageId}", part.bytes, position, amount, item.mime.startsWith("image/")) { at, size -> kotlinx.coroutines.runBlocking(coroutineContext) { telegram.readDocumentPart(part, at, size) } }
                                    try {
                                        access()
                                        System.arraycopy(chunk, 0, result, target, amount)
                                    } finally {
                                        chunk.fill(0)
                                    }
                                    remaining -= amount
                                    target += amount
                                    position = 0
                                    if (remaining == 0) break
                                }
                                check(remaining == 0)
                                offlineMutable.update { it.copy(progress = offset + count) }
                                result
                            } catch (failure: Exception) {
                                result.fill(0)
                                throw failure
                            }
                        }, { cipher ->
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            java.io.RandomAccessFile(cipher, "r").use { source ->
                                AuthenticatedRecords({ position, output, start, count ->
                                    access()
                                    source.seek(position)
                                    source.readFully(output, start, count)
                                }, key, UUID.fromString(selectedVault), UUID.fromString(id), item.size, length).use { reader ->
                                    val buffer = ByteArray(65536)
                                    try {
                                        var position = 0L
                                        while (position < reader.length()) {
                                            access()
                                            coroutineContext.ensureActive()
                                            val count = reader.read(position, buffer, 0, minOf(buffer.size.toLong(), reader.length() - position).toInt())
                                            check(count > 0)
                                            digest.update(buffer, 0, count)
                                            position += count
                                        }
                                    } finally {
                                        buffer.fill(0)
                                    }
                                }
                            }
                            check(java.security.MessageDigest.isEqual(digest.digest(), decode(item.hash))) { "Offline authentication failed." }
                            access()
                        })
                        access()
                        dao.put(row(offlineRow(id), "offline", JSONObject().put("file", id).put("hash", item.hash).put("bytes", length), masterKey))
                        refreshOffline(masterKey, state.value.files, epoch)
                    } catch (failure: Exception) {
                        // No uncommitted copy is advertised as available offline.
                        offlineStore.remove("$selectedAccount:$selectedVault:$id")
                        throw failure
                    } finally {
                        key.fill(0)
                    }
                }
            } finally {
                releaseWorkKey(masterKey)
                if (accessEpoch.get() == epoch) offlineMutable.update { it.copy(activeId = null, progress = 0, total = 0) }
            }
        }
    }
    private fun cachedUploadCipher(id: String, length: Long, offset: Long, count: Int): ByteArray? {
        if (policy.settings().dataSaver) return null
        val file = File(encryptedCache, "$id.bin")
        val now = System.currentTimeMillis()
        if (!file.isFile || file.length() != length || now < file.lastModified() || now - file.lastModified() >= CipherRangeCache.TTL) return null
        val bytes = ByteArray(count)
        return try {
            java.io.RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                input.readFully(bytes)
            }
            bytes
        } catch (_: java.io.IOException) {
            bytes.fill(0)
            file.delete()
            null
        }
    }
    internal fun invalidateStreamCache(id: String? = null) {
        if (id != null && runCatching { UUID.fromString(id) }.isSuccess) {
            val scope = offlineScope(id)
            val rowId = offlineRow(id)
            offlineMutable.update { it.copy(ids = it.ids - id) }
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    offlineStore.remove(scope)
                    dao.remove(rowId)
                }
            }
        }
        streamCache.clear()
        encryptedCache.listFiles()?.forEach { it.delete() }
    }
    internal fun closeReader(reader: AuthenticatedRecords) {
        activeReaders.remove(reader)
        reader.close()
    }
    internal fun streamingFile(id: String): Pair<DriveFile, String> {
        check(state.value.stage == VaultStage.READY)
        return checkNotNull(state.value.files.find { it.id == id }) to vault
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun mediaSourceFactory(): androidx.media3.datasource.DataSource.Factory = androidx.media3.datasource.DataSource.Factory { EncryptedMediaSource(this, telegram) }
    private fun cacheGroup(id: String) = "$account/$vault/$id"
    suspend fun recordMediaView(id: String) = withContext(Dispatchers.IO) {
        viewing.withLock {
            val epoch = accessEpoch.get()
            if (state.value.stage != VaultStage.READY) return@withLock
            val item = state.value.files.find { it.id == id && it.trashedAt == 0L } ?: return@withLock
            val key = unlockedKey()
            try {
                val rowId = "view-heat:$account:$vault:$id"
                val previous = dao.get(rowId)?.let { verifiedRecord { payload(it, key) } }
                val now = System.currentTimeMillis()
                val oldViews = previous?.optInt("views") ?: 0
                val last = previous?.optLong("last") ?: 0L
                val views = AdaptiveMediaPolicy.nextViews(oldViews, last, now)
                val counted = views != oldViews || last <= 0 || last > now || now - last >= 7 * AdaptiveMediaPolicy.DAY
                val until = if (counted) now + AdaptiveMediaPolicy.retention(item.mime.startsWith("video/"), item.facts.durationMs, views) else previous?.optLong("until") ?: now + AdaptiveMediaPolicy.DAY
                check(epoch == accessEpoch.get() && state.value.stage == VaultStage.READY)
                if (counted) dao.put(row(rowId, "view-heat", JSONObject().put("views", views).put("last", now).put("until", until), key))
                retentionDeadlines[cacheGroup(id)] = until
                streamCache.retain(cacheGroup(id), until)
            } finally {
                key.fill(0)
            }
        }
    }

    internal fun markVideoPosition(id: String, position: Long, duration: Long, epoch: Long) {
        if (epoch == accessEpoch.get() && state.value.stage == VaultStage.READY && duration > 0 && position >= 0) {
            videoTimes[id] = position.coerceAtMost(duration)
            streamCache.markPlaybackWindow(cacheGroup(id), position.coerceAtMost(duration))
        }
    }

    /** Main-thread caller passes native milliseconds only; disk pruning stays on IO. */
    suspend fun videoWindow(id: String, position: Long, duration: Long) = withContext(Dispatchers.IO) {
        if (state.value.stage != VaultStage.READY || duration <= 0 || position < 0) return@withContext
        val item = state.value.files.find { it.id == id && it.mime.startsWith("video/") } ?: return@withContext
        videoTimes[id] = position.coerceAtMost(duration)
        if (!AdaptiveMediaPolicy.shortVideo(duration)) streamCache.playbackWindow(cacheGroup(item.id), position.coerceAtMost(duration))
    }

    /** Bounded encrypted original prefixes. No device-original disk copy or mobile prefetch. */
    suspend fun prefetch(id: String, percent: Int = 100, budget: Long = AdaptiveMediaPolicy.WARM_BUDGET): Long = withContext(Dispatchers.IO) {
        photoPriority.await(id)
        prefetches.withPermit {
            val item = state.value.files.find { it.id == id && it.trashedAt == 0L } ?: return@withPermit 0L
            val video = item.mime.startsWith("video/")
            if (!localMedia.indexReady.value || reconciliation.isLocked || policy.settings().dataSaver || policy.cellular() || (!video && !item.mime.startsWith("image/"))) return@withPermit 0L
            // Long movies warm through native timed playback, never a guessed bitrate prefix.
            if (video && !AdaptiveMediaPolicy.shortVideo(item.facts.durationMs)) return@withPermit 0L
            if (item.size > 16L * 1024 * 1024 && !video || preferredDeviceSource(id) != null) return@withPermit 0L
            val limit = AdaptiveMediaPolicy.prefix(item.size, if (video) 100 else percent, budget)
            if (limit <= 0) return@withPermit 0L
            authenticatedReader(id, coroutineContext[Job], speculative = true).let { reader ->
                val buffer = ByteArray(65536)
                var position = 0L
                try {
                    while (position < limit) {
                        photoPriority.await(id)
                        coroutineContext.ensureActive()
                        if (policy.settings().dataSaver || policy.cellular()) break
                        val count = reader.read(position, buffer, 0, minOf(buffer.size.toLong(), limit - position).toInt())
                        if (count <= 0) break
                        position += count
                    }
                    position
                } finally {
                    buffer.fill(0)
                    closeReader(reader)
                }
            }
        }
    }
    suspend fun warmTimeline(ids: List<String>) = withContext(Dispatchers.IO) {
        startupWarm.withLock {
            val epoch = accessEpoch.get()
            if (state.value.stage != VaultStage.READY || !state.value.indexLoaded) return@withLock
            val activePhotos = state.value.files.asSequence().filter { it.trashedAt == 0L && it.mime.startsWith("image/") }.associateBy { it.id }
            val chosen = ids.distinct().mapNotNull { activePhotos[it] }.take(AdaptiveMediaPolicy.WARM_COUNT)
            if (chosen.isEmpty()) return@withLock
            val key = unlockedKey()
            try {
                dao.put(row("startup-index:$account:$vault", "startup-index", JSONObject().put("ids", JSONArray(chosen.take(70).map { it.id })), key))
            } finally {
                key.fill(0)
            }
            // Encrypted catalog thumbs are already durable. Decode only the first screen;
            // later cells decode on demand rather than filling RAM with seventy bitmaps.
            chosen.take(12).forEach {
                coroutineContext.ensureActive()
                thumbnail(it.id, 384)
            }
            var remaining = minOf(AdaptiveMediaPolicy.WARM_BUDGET, mediaStorage.usage.value.limit / 8)
            var photoIndex = 0
            for (item in chosen) {
                coroutineContext.ensureActive()
                if (epoch != accessEpoch.get() || state.value.stage != VaultStage.READY || remaining < 1024 * 1024) break
                if (!item.mime.startsWith("image/")) continue
                val percent = AdaptiveMediaPolicy.photoPercent(photoIndex++)
                try {
                    val used = prefetch(item.id, percent, remaining)
                    // Range cache commits aligned 1MiB ciphertext blocks plus authentication.
                    if (used > 0) remaining -= ((used + 1048575) / 1048576 + 1) * 1048576
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) { /* Optional warming never hides the verified index. */ }
            }
        }
    }
    private fun scheduleRetention() {
        if (!retentionCancelled) {
            work.cancelUniqueWork("tgphotos-trash")
            work.cancelUniqueWork("tgphotos-trash-retention")
            retentionCancelled = true
        }
    }
    suspend fun quickUnlock() = withContext(Dispatchers.IO) {
        protection.requireAllowed()
        requireVaultUsable(account, vault)
        val epoch = accessEpoch.get()
        check(telegram.accountId() == account) { "Telegram account changed. Discover and recover the matching Drive." }
        protection.requireAllowed()
        val key = deviceKey.load(account, vault)
        try {
            synchronized(this@DriveRepository) {
                coroutineContext.ensureActive()
                check(accessEpoch.get() == epoch) { "Unlock request expired" }
                master?.fill(0)
                master = key.copyOf()
                mutable.update { it.copy(stage = VaultStage.READY, quickUnlockAvailable = true) }
            }
            migrateCameraBackups()
            refreshLocal()
        } finally {
            key.fill(0)
        }
        scheduleRetention()
        scheduleCloudRestore()
        sync()
    }
    private fun applyEvent(event: JSONObject, message: Long, key: ByteArray) {
        check(event.getInt("version") == 1)
        val id = UUID.fromString(event.getString("id")).toString()
        val kind = event.getString("kind")
        check(kind in setOf("state", "album", "folder"))
        event.optJSONObject("privateProfile")?.let {
            check(kind == "state" && id == profileEventId())
            PrivateProfile.read(it)
        }
        val rowId = "$kind:$id"
        val previous = dao.get(rowId)?.let { verifiedRecord { payload(it, key) } }
        if ((previous?.optLong("eventMessage") ?: 0) >= message) return
        if (kind == "state" && previous?.optBoolean("purged") == true) event.put("purged", true)
        event.put("eventMessage", message)
        dao.put(row(rowId, kind, event, key))
    }
    private suspend fun publishEvent(event: JSONObject, key: ByteArray, expectedEpoch: Long? = null) {
        val owner = account
        val drive = vault
        fun checkScope() {
            check(expectedEpoch == null || expectedEpoch == accessEpoch.get() && account == owner && vault == drive)
        }
        checkScope()
        val file = File(cache, "${UUID.randomUUID()}.bin")
        try {
            file.writeBytes(sealJson(key, event, context("event", owner, drive)))
            checkScope()
            val reference = telegram.sendDocument(file, "TGDRIVE_V1_EVENT:$drive:${event.getString("id")}") { _, _ -> }
            checkScope()
            applyEvent(event, reference.messageId, key)
        } finally {
            file.delete()
        }
    }

    /** Fresh cryptographic authorization, then an encrypted, resumable snapshot of active cloud IDs. */
    suspend fun trashAllCloud(phrase: CharArray): Boolean = withContext(Dispatchers.IO) {
        val authorizedAccount = account
        val authorizedVault = vault
        if (!verifyRecoveryPhrase(phrase)) return@withContext false
        edits.withLock {
            check(account == authorizedAccount && vault == authorizedVault && state.value.stage == VaultStage.READY)
            val key = unlockedKey()
            try {
                val existing = activeJobs(account, vault).firstOrNull { it.id.startsWith("trash-all:") }
                if (existing != null) {
                    notice("Cloud Trash is already queued. Follow progress in Library → Transfers.")
                    return@withLock
                }
                val ids = dao.ids(account, vault, "file").map { it.removePrefix("file:") }.filter { id ->
                    val props = dao.get("state:$id")?.let { payload(it, key) }
                    props?.optBoolean("purged") != true && (props?.optLong("trashedAt") ?: 0L) == 0L
                }
                if (ids.isEmpty()) {
                    notice("No active cloud backups remain.")
                    return@withLock
                }
                coroutineContext.ensureActive()
                preferences.setTrashCooldownEnabled(true)
                val data = JSONObject().put("action", "trash-all").put("name", "Cloud backups → Trash").put("ids", JSONArray(ids))
                val task = row("trash-all:${UUID.randomUUID()}", "job", data, key, "WAITING")
                task.total = ids.size.toLong()
                dao.put(task)
                schedule(task)
                refreshTransfers()
                notice("Cloud Trash queued: ${ids.size} files. Device originals stay. Follow progress in Library → Transfers.")
            } finally {
                key.fill(0)
            }
        }
        true
    }
    private suspend fun moveCloudBatchToTrash(task: CacheRow, data: JSONObject, key: ByteArray) {
        val ids = data.getJSONArray("ids")
        task.total = ids.length().toLong()
        for (index in task.progress.toInt() until ids.length()) {
            coroutineContext.ensureActive()
            if (dao.get(task.id)?.phase == "CANCELLED") return
            if ((app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) throw DataPolicyBlocked("WAITING_UNLOCK")
            edits.withLock {
                val id = ids.getString(index)
                val event = dao.get("state:$id")?.let { payload(it, key) }
                    ?: JSONObject().put("version", 1).put("kind", "state").put("id", id)
                if (dao.get("file:$id") != null && !event.optBoolean("purged") && event.optLong("trashedAt") == 0L) {
                    stage(task, "MOVING_TO_TRASH")
                    event.put("trashedAt", System.currentTimeMillis())
                    publishEvent(event, key)
                }
                // Checkpoint only confirmed events. Retry skips the completed prefix and already-trashed files.
                task.progress = index + 1L
                saveTransfer(task)
                if ((index + 1) % 16 == 0 || index + 1 == ids.length()) refreshLocal() else refreshTransfers()
            }
            // Modest pacing; Telegram FLOOD_WAIT is handled by executeJob/WorkManager, never bypassed.
            delay(350)
        }
    }
    suspend fun changeFiles(ids: Set<String>, expectedEpoch: Long = accessEpoch.get(), favorite: Boolean? = null, folder: String? = null, trash: Boolean? = null, albums: Set<String>? = null, trashOrigin: String = "", displayName: String? = null) = withContext(Dispatchers.IO) {
        edits.withLock {
            check(expectedEpoch == accessEpoch.get())
            val key = unlockedKey()
            try {
                for (id in ids) {
                    coroutineContext.ensureActive()
                    check(expectedEpoch == accessEpoch.get())
                    checkNotNull(dao.get("file:$id"))
                    val event = dao.get("state:$id")?.let { payload(it, key) } ?: JSONObject().put("version", 1).put("kind", "state").put("id", id)
                    favorite?.let { event.put("favorite", it) }
                    displayName?.let {
                        require(it.isNotBlank() && it.length <= 240 && it.none { c -> c == '/' || c == '\\' || c.isISOControl() })
                        event.put("displayName", it)
                    }
                    folder?.let {
                        require(it.isNotBlank() && it.length <= 240)
                        event.put("album", it)
                    }
                    trash?.let {
                        event.put("trashedAt", if (it) System.currentTimeMillis() else 0L)
                        event.put("trashOrigin", if (it) trashOrigin else "")
                    }
                    albums?.let { event.put("albums", JSONArray(it.toList())) }
                    publishEvent(event, key, expectedEpoch)
                }
            } finally {
                try {
                    withContext(NonCancellable) { if (expectedEpoch == accessEpoch.get()) refreshLocal() }
                } finally {
                    key.fill(0)
                }
            }
        }
    }
    suspend fun createAlbum(name: String, ids: Set<String>, folder: Boolean = false) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.length <= 240)
        if (folder) {
            require(LibraryPath.clean(name) == name && name.none { it.isISOControl() || it == '\\' })
            require(state.value.albums.none { it.folder && it.name.equals(name, ignoreCase = true) }) { "A folder with this name already exists." }
        }
        require(folder || ids.all { fileId -> state.value.files.any { it.id == fileId && it.mime.startsWith("image/") && it.trashedAt == 0L } })
        val id = UUID.randomUUID().toString()
        val key = unlockedKey()
        try {
            publishEvent(JSONObject().put("version", 1).put("kind", if (folder) "folder" else "album").put("id", id).put("name", name), key)
        } finally {
            key.fill(0)
        }
        for (fileId in ids) changeFiles(setOf(fileId), albums = (state.value.files.find { it.id == fileId }?.albumIds.orEmpty() + id))
        refreshLocal()
    }
    suspend fun copyCloud(ids: Set<String>, folder: String) = withContext(Dispatchers.IO) {
        for (id in ids) {
            val item = checkNotNull(state.value.files.find { it.id == id })
            val source = privateMediaUri(id)
            enqueueUpload(listOf(source), folder, sourceTokens = mapOf(source.toString() to "copy:${UUID.randomUUID()}"), displayNames = mapOf(source.toString() to item.name), allowDuplicate = true)
        }
    }

    /** Internal coordinator supplies a fully authenticated independent Vault copy, never a queued upload. */
    internal suspend fun moveCloudToPrivateVault(id: String, verifiedSize: Long, verifiedHash: ByteArray, privateAccess: () -> Boolean) = withContext(Dispatchers.IO) {
        edits.withLock {
            val epoch = currentAccessEpoch()
            val selectedAccount = account
            val selectedVault = vault
            val key = synchronized(this@DriveRepository) { unlockedKey().also { activeWorkKeys.incrementAndGet() } }
            var serverDeleted = false
            var journal: CacheRow? = null
            var retiredJobs = emptyList<String>()
            try {
                fun access() {
                    ensureKeyActive(key)
                    check(accessEpoch.get() == epoch && privateAccess())
                }
                access()
                val item = checkNotNull(state.value.files.find { it.id == id && it.trashedAt == 0L })
                check(item.size == verifiedSize && MessageDigest.isEqual(decode(item.hash), verifiedHash)) { "Vault copy differs from the cloud source." }
                check(id !in runningTransfers)
                retireIntelligenceForPrivateMove(Uri.parse("content://${app.packageName}.private-media/media/$id"), verifiedSize, verifiedHash)
                access()
                val metadata = payload(checkNotNull(dao.get("file:$id")), key)
                retiredJobs = dao.ids(account, vault, "job").asSequence().mapNotNull { dao.get(it) }.filter { job ->
                    val data = payload(job, key)
                    job.id == id || data.optJSONObject("metadata")?.optString("id") == id || data.optJSONObject("file")?.optString("id") == id
                }.map { it.id }.toList()
                val event = JSONObject().put("version", 1).put("kind", "state").put("id", id).put("purged", true)
                journal = row("vault-source-retired:$id", "vault-source-retired", event, key, "PREPARED")
                dao.put(checkNotNull(journal))
                val references = item.parts.ifEmpty { listOf(item.data) } + RemoteDocument(item.data.chatId, metadata.getLong("catalogMessage"), 0)
                access()
                telegram.deleteOwnedDocuments(references, ":$selectedVault:$id")
                serverDeleted = true
                // No key access is needed to checkpoint an acknowledged server deletion.
                journal.phase = "REVOKED"
                dao.put(journal)
                access()
                publishEvent(event, key)
                dao.remove(journal.id)
            } finally {
                try {
                    if (serverDeleted) {
                        // Retire the old normal-Drive key even when the session locks after server ACK.
                        // Only the verified independent Vault copy remains recoverable through this app.
                        retiredJobs.forEach { jobId ->
                            work.cancelUniqueWork("transfer:$jobId")
                            dao.remove(jobId)
                        }
                        dao.remove("file:$id")
                        dao.remove("offline:$selectedAccount:$selectedVault:$id")
                        runCatching { offlineStore.remove("$selectedAccount:$selectedVault:$id") }
                        File(encryptedCache, "$id.bin").delete()
                        metadataCache.remove("$selectedAccount:$selectedVault:$id")
                        transferDisplayCache.evictAll()
                        thumbnails.evictAll()
                        photoPreviews.clear()
                        streamCache.clear()
                        if (accessEpoch.get() == epoch) refreshLocal()
                    }
                } finally {
                    releaseWorkKey(key)
                }
            }
        }
    }
    private suspend fun purgeRemote(metadata: JSONObject, key: ByteArray) {
        val item = file(metadata)
        val references = item.parts.ifEmpty { listOf(item.data) } + RemoteDocument(item.data.chatId, metadata.getLong("catalogMessage"), 0)
        telegram.deleteOwnedDocuments(references, ":$vault:${item.id}")
        val event = JSONObject().put("version", 1).put("kind", "state").put("id", item.id).put("purged", true)
        publishEvent(event, key)
        File(encryptedCache, "${item.id}.bin").delete()
    }
    suspend fun purgeTrash(ids: Set<String>, epoch: Long = accessEpoch.get()) = withContext(Dispatchers.IO) {
        purgeQueue.withLock {
            check(epoch == accessEpoch.get())
            val key = unlockedKey()
            try {
                val items = state.value.files.filter { it.id in ids && it.trashedAt > 0 }
                val enabled = preferences.trashCooldownEnabled.first()
                check(items.all { TrashCooldown.allowed(it.trashedAt, System.currentTimeMillis(), enabled) }) { "Trash protection: wait 24 hours after deletion, or change the setting." }
                for (item in items) {
                    coroutineContext.ensureActive()
                    check(epoch == accessEpoch.get())
                    val jobId = "purge:${item.id}"
                    val existing = dao.get(jobId)
                    if (existing != null) {
                        check(existing.account == account && existing.vault == vault)
                        // Repeated taps never replace a running encrypted checkpoint.
                        if (existing.phase == "FAILED") retry(jobId)
                        if (existing.phase == "CANCELLED") {
                            // Join the old worker's resource lifetime before explicitly requeueing.
                            transferLocks[jobId.hashCode().ushr(1) % transferLocks.size].withLock {
                                check(epoch == accessEpoch.get())
                                if (dao.requeueCancelled(jobId, account, vault) > 0) {
                                    val resumed = checkNotNull(dao.get(jobId))
                                    schedule(resumed, restart = true)
                                }
                            }
                        }
                        continue
                    }
                    val metadata = payload(checkNotNull(dao.get("file:${item.id}")), key)
                    val task = row(jobId, "job", JSONObject().put("action", "purge").put("file", metadata).put("name", metadata.getString("name")), key, "WAITING")
                    if (dao.insertIfAbsent(task) != -1L) schedule(task)
                    yield()
                }
                refreshTransfers()
                if (items.isNotEmpty()) notice("Permanent deletion queued. Check Transfers for completion.")
            } finally {
                key.fill(0)
            }
        }
    }

    // Previous installations may still have retention workers. Never automatically purge;
    // permanent deletion now requires the user's password-confirmed request.
    suspend fun expireTrash() {
        if (!retentionCancelled) {
            work.cancelUniqueWork("tgphotos-trash")
            work.cancelUniqueWork("tgphotos-trash-retention")
            retentionCancelled = true
        }
    }

    /** Browsing grant only: never creates a backup subscription or upload job. */
    suspend fun addDeviceDocumentFolder(uri: Uri) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && android.provider.DocumentsContract.isTreeUri(uri))
        val key = unlockedKey()
        try {
            val existing = dao.list(account, vault, "device-library-root").any { payload(it, key).getString("uri") == uri.toString() }
            if (!existing) {
                val data = JSONObject().put("uri", uri.toString())
                dao.put(row("device-library-root:${UUID.randomUUID()}", "device-library-root", data, key))
            }
            refreshLocal()
        } finally {
            key.fill(0)
        }
    }
    suspend fun setDeviceFolderBackup(path: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val folder = DeviceBackupFolder.normalize(path)
        if (DeviceBackupFolder.matches(folder, "", true)) {
            val settings = policy.settings()
            updateUploadPolicy(settings.photoLimitMb, settings.mobileVideos, enabled)
            return@withContext
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(folder.toByteArray()).joinToString("") { "%02x".format(it) }
        val id = "device-folder:$account:$vault:$digest"
        if (!enabled) {
            if (dao.get(id) != null) stopBackup(id)
            return@withContext
        }
        backupControl.withLock {
            val key = unlockedKey()
            try {
                dao.put(row(id, "backup", JSONObject().put("deviceFolder", folder).put("name", folder.substringAfterLast('/')).put("wifi", false), key))
            } finally {
                key.fill(0)
            }
            work.cancelUniqueWork("backup:$id")
            watchCamera(id)
            val now = OneTimeWorkRequestBuilder<FolderBackupWorker>().setInputData(workDataOf("folder" to id)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).addTag("tgdrive").build()
            work.enqueueUniqueWork("backup-now:$id", ExistingWorkPolicy.KEEP, now)
            refreshLocal()
        }
    }

    suspend fun updateUploadPolicy(limit: Long, videos: Boolean, camera: Boolean) = withContext(Dispatchers.IO) {
        backupControl.withLock {
            val key = unlockedKey()
            try {
                dao.list(account, vault, "backup").filter { row ->
                    val data = payload(row, key)
                    data.has("deviceFolder") && DeviceBackupFolder.matches(data.getString("deviceFolder"), "", true)
                }.forEach { stopBackupLocked(it.id, key) }
                policy.update(limit, videos, camera)
                val id = "camera:$account:$vault"
                if (camera) {
                    startCameraLocked(key)
                    watchCamera(id)
                } else if (dao.get(id) != null) {
                    stopBackupLocked(id, key)
                }
                resumeAutomaticUploads()
                refreshLocal()
            } finally {
                key.fill(0)
            }
        }
    }
    suspend fun setDataSaver(enabled: Boolean) = withContext(Dispatchers.IO) {
        backupControl.withLock {
            val key = unlockedKey()
            try {
                policy.setDataSaver(enabled)
                if (enabled) {
                    streamCache.clear()
                    encryptedCache.listFiles()?.forEach { it.delete() }
                }
                if (enabled) {
                    val current = policy.settings()
                    policy.update(current.photoLimitMb, current.mobileVideos, true)
                    startCameraLocked(key)
                    watchCamera("camera:$account:$vault")
                } else {
                    resumeAutomaticUploads()
                }
                refreshLocal()
            } finally {
                key.fill(0)
            }
        }
    }
    internal fun readStreamCipher(scope: String, size: Long, offset: Long, length: Int, photo: Boolean = false, fetch: CipherRangeCache.Fetch): ByteArray {
        val id = scope.substringBefore('/')
        val group = cacheGroup(id)
        val now = System.currentTimeMillis()
        val until = retentionDeadlines[group]?.takeIf { it in (now + 1)..(now + 7 * AdaptiveMediaPolicy.DAY) } ?: (now + AdaptiveMediaPolicy.DAY).also { retentionDeadlines[group] = it }
        val keep = !policy.settings().dataSaver
        if (keep) streamCache.retain(group, until)
        return streamCache.read("$account/$vault/$scope", size, offset, length, keep, photo, group, videoTimes[id] ?: -1L, fetch)
    }
    suspend fun clearMediaCache() = withContext(Dispatchers.IO) {
        photoPreviews.clear()
        documentPreviews.clear()
        streamCache.clear()
        encryptedCache.listFiles()?.forEach { it.delete() }
        thumbnails.evictAll()
        telegram.pruneLocalStreamCache()
        mediaStorage.refresh()
    }
    suspend fun rotateDeviceKey() = withContext(Dispatchers.IO) {
        val key = unlockedKey()
        try {
            deviceKey.rotate(account, vault, key)
            notice("This phone's encrypted key wrapper was rotated. File encryption and recovery password remain unchanged.")
        } finally {
            key.fill(0)
        }
    }
    suspend fun overrideMobileLimit() = withContext(Dispatchers.IO) {
        policy.overrideToday()
        resumeAutomaticUploads()
        refreshLocal()
        notice("Added 200 MB for today. Today’s allowance is ${policy.settings().dailyLimitBytes / AutomaticBackupPolicy.MIB} MB.")
    }
    suspend fun saveBackupOptions(limit: Long, minutes: Int, photosFirst: Boolean) = withContext(Dispatchers.IO) {
        policy.saveOptions(limit, minutes, photosFirst)
        resumeAutomaticUploads()
        refreshLocal()
    }
    suspend fun setUnlimitedBackup(enabled: Boolean) = withContext(Dispatchers.IO) {
        policy.setUnlimited(enabled)
        resumeAutomaticUploads()
        refreshLocal()
    }
    suspend fun resumeAutomaticUploads() = withContext(Dispatchers.IO) {
        if (state.value.stage != VaultStage.READY || state.value.indexNeedsRepair) return@withContext
        BackupScheduling.request(app)
        val anchor = activeJobs(account, vault).firstOrNull { !UploadQueuePolicy.terminalFailure(it.phase) } ?: return@withContext
        val key = unlockedKey()
        try {
            uploadQueue.withLock {
                pendingUploads(anchor, key).firstOrNull { uploadEligible(it.first, it.second) }?.let { scheduleUploadQueue(it.first, it.second.optBoolean("manual")) }
            }
        } finally {
            key.fill(0)
        }
    }
    suspend fun watchCamera(id: String, rearm: Boolean = false) = withContext(Dispatchers.IO) {
        val record = dao.get(id) ?: return@withContext
        val key = backgroundKey(record)
        val source = try {
            payload(record, key).optString("uri").takeIf { it.startsWith("content://") }?.let(Uri::parse)
        } finally {
            releaseWorkKey(key)
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
            .apply { if (source != null) addContentUriTrigger(source, true) }
            .addContentUriTrigger(android.provider.MediaStore.Files.getContentUri("external"), true)
            .addContentUriTrigger(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS).setTriggerContentMaxDelay(30, TimeUnit.SECONDS).build()
        val request = OneTimeWorkRequestBuilder<CameraChangeWorker>().setInputData(workDataOf("folder" to id)).setConstraints(constraints).addTag("tgdrive").build()
        work.enqueueUniqueWork("camera-watch:$id", if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }
    suspend fun restoreBackupSchedules() = withContext(Dispatchers.IO) {
        telegram.reconnect()
        withTimeout(45000) { telegram.authorization.first { it.stage == AuthProtocol.State.READY || it.stage == AuthProtocol.State.PHONE || it.stage == AuthProtocol.State.CLOSED } }
        if (telegram.authorization.value.stage != AuthProtocol.State.READY) return@withContext
        val owner = telegram.accountId()
        if (!rootFile(owner).baseFile.exists()) return@withContext
        val descriptor = rootFile(owner).openRead().use { input ->
            check(input.available() <= 65536)
            JSONObject(input.readBytes().toString(Charsets.UTF_8))
        }
        val scope = validateRoot(descriptor, owner)
        for (record in dao.list(owner, scope, "backup")) {
            val key = backgroundKey(record)
            try {
                val data = payload(record, key)
                val constraint = Constraints.Builder().setRequiredNetworkType(if (data.optBoolean("wifi", true)) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
                work.cancelUniqueWork("backup:${record.id}")
                watchCamera(record.id)
                val now = OneTimeWorkRequestBuilder<FolderBackupWorker>().setInputData(workDataOf("folder" to record.id)).setConstraints(constraint).addTag("tgdrive").build()
                work.enqueueUniqueWork("backup-now:${record.id}", ExistingWorkPolicy.KEEP, now)
            } finally {
                releaseWorkKey(key)
            }
        }
        val jobs = activeJobs(owner, scope).filter { !UploadQueuePolicy.terminalFailure(it.phase) }
        jobs.firstOrNull()?.let { anchor ->
            val key = backgroundKey(anchor)
            try {
                uploadQueue.withLock {
                    for (task in jobs) {
                        val data = payload(task, key)
                        if (data.optString("action") == "upload" && dao.get("upload-queue:${task.id}") == null) {
                            val wifi = data.optString("backupId").takeIf { it.isNotBlank() }?.let { dao.get(it) }?.let { payload(it, key).optBoolean("wifi", true) } ?: false
                            putUploadTicket(task, key, manual = !data.has("backupId") || data.optBoolean("manualRequested"), wifi = wifi)
                        }
                        if (data.optString("action") == "upload" && task.id !in runningTransfers) work.cancelUniqueWork("transfer:${task.id}")
                    }
                    pendingUploads(anchor, key).firstOrNull()?.let { scheduleUploadQueue(it.first, it.second.optBoolean("manual")) }
                }
            } finally {
                releaseWorkKey(key)
            }
        }
        cloudExports.restoreScheduled(owner, scope)
    }
    suspend fun waitingForOwner(id: String): Boolean = withContext(Dispatchers.IO) { dao.get(id)?.phase in setOf("DATA_SAVER_AUTO_PAUSED", "VIDEO_NOTICE_REQUIRED", "CAMERA_VIDEO_LIMIT") }
    suspend fun waitingForWifi(id: String): Boolean = withContext(Dispatchers.IO) { dao.get(id)?.phase in setOf("DAILY_PHOTO_LIMIT", "MOBILE_VIDEO_DISABLED") }
    suspend fun continueOnWifi(id: String) = withContext(Dispatchers.IO) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>().setInputData(workDataOf("job" to id, "wifiContinuation" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).addTag("tgdrive").addTag("tgdrive-transfer").build()
        work.enqueueUniqueWork("transfer:$id", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    suspend fun pollTransfers() = withContext(Dispatchers.IO) {
        if (android.os.SystemClock.elapsedRealtime() - nativePrunedAt >= 300000 || nativePrunedAt == 0L) {
            nativePrunedAt = android.os.SystemClock.elapsedRealtime()
            streamCache.prune()
            pruneCache()
            runCatching { telegram.pruneLocalStreamCache() }.onFailure { if (it is CancellationException) throw it }
            mediaStorage.refresh()
        }
        if (state.value.stage == VaultStage.READY) scheduleRetention()
        refreshTransfers()
    }
    fun notice(text: String) {
        mutable.update { it.copy(notice = text) }
    }
}
