package app.televault.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Base64
import app.televault.crypto.AuthenticatedRecords
import app.televault.crypto.NewPasswordPolicy
import app.televault.crypto.RecoveryEnvelope
import app.televault.crypto.VaultCrypto
import app.televault.domain.AuthProtocol
import app.televault.domain.CaptureTime
import app.televault.domain.VaultDirectoryPolicy
import app.televault.domain.VaultNames
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class PrivateVaultItem(val id: String, val name: String, val mime: String, val size: Long, val date: Long, val hash: ByteArray, val fileKey: ByteArray, val cloud: PrivateVaultCloudObject? = null) {
    override fun toString() = "PrivateVaultItem(redacted)"
    fun wipe() {
        fileKey.fill(0)
        cloud?.outerKey?.fill(0)
        hash.fill(0)
    }
}

/** Password-isolated Vault; old local ciphertext remains readable. New media uses nested cloud encryption. */
internal class PrivateVaultRepository(private val context: Context) {
    private var directory = File(context.noBackupFilesDir, "private-vault-v1").apply {
        mkdirs()
        listFiles().orEmpty().filter { it.extension == "upload" }.forEach { it.delete() }
    }
    private val app = context.applicationContext as app.televault.TeleVaultApplication
    private var baseDirectory = directory
    private val selection = context.getSharedPreferences("private-vault-selection-v1", Context.MODE_PRIVATE)
    private val choicesMutable = MutableStateFlow<List<VaultChoice>>(emptyList())
    val choices = choicesMutable.asStateFlow()
    val selectedName = MutableStateFlow("")
    private var selectedIdentity = ""
    val selectedId: String get() = selectedIdentity

    @Synchronized internal fun revokeForgotten(owner: Long, photos: String, id: String) {
        if (selectedIdentity == id && (expectedBinding == null || expectedBinding == owner to photos)) lock()
    }
    internal suspend fun retireForgottenPhotosLocal(owner: Long, photos: String) = withContext(Dispatchers.IO) {
        val legacy = File(context.noBackupFilesDir, "private-vault-v1")
        val bound = File(context.noBackupFilesDir, "private-vault-cloud-v2/$owner-${UUID.fromString(photos)}")
        val candidates = listOf(legacy, bound) + listOf(legacy, bound).flatMap { File(it, "slots").listFiles().orEmpty().filter { child -> child.isDirectory } }
        val ids = candidates.mapNotNull { target ->
            val file = File(target, "descriptor.json")
            if (!file.exists() && !File(file.path + ".bak").exists()) {
                null
            } else {
                AtomicFile(file).openRead().use { input ->
                    check(file.length() in 1..8192)
                    JSONObject(input.readBytes().toString(Charsets.UTF_8)).takeIf { it.optLong("cloudAccount") == owner && it.optString("cloudVault") == photos }?.getString("id")
                }
            }
        }.distinct()
        for (id in ids) retireForgottenLocal(owner, photos, id)
    }
    internal suspend fun retireForgottenLocal(owner: Long, photos: String, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(app.telegram.accountId() == owner)
            UUID.fromString(photos)
            UUID.fromString(id)
            val legacy = File(context.noBackupFilesDir, "private-vault-v1")
            val bound = File(context.noBackupFilesDir, "private-vault-cloud-v2/$owner-$photos")
            val candidates = listOf(legacy, bound) + listOf(legacy, bound).flatMap { File(it, "slots").listFiles().orEmpty().filter { child -> child.isDirectory } }
            for (target in candidates.distinct()) {
                val stored = AtomicFile(File(target, "descriptor.json"))
                if (!stored.baseFile.exists() && !File(stored.baseFile.path + ".bak").exists()) continue
                val data = stored.openRead().use {
                    check(stored.baseFile.length() in 1..8192)
                    JSONObject(it.readBytes().toString(Charsets.UTF_8))
                }
                if (data.optString("id") != id || data.has("cloudAccount") && (data.getLong("cloudAccount") != owner || data.getString("cloudVault") != photos)) continue
                if (directory == target) lock()
                // The legacy base can contain sibling slots: never recursively erase it.
                target.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[0-9a-f-]{36}\\.(meta|cloud|cipher|upload)(\\.(bak|new))?")) }.forEach { check(it.delete()) }
                VaultBiometricKey(target, "$owner/$photos/$id").erase()
                DeviceVaultKey(context).erase(-17L, id)
                stored.delete()
                if (directory == target) {
                    expectedBinding = null
                    selectedIdentity = ""
                }
            }
            localChoices()
        }
    }
    private fun generatedName(data: JSONObject): String = VaultNames.display("", data.optLong("createdAt"), java.time.ZoneId.systemDefault(), data.getString("id"))
    private fun decodedName(key: ByteArray, data: JSONObject, id: UUID): String = runCatching {
        val plain = VaultCrypto.open(key, decode(data.getString("sealedName")), "TGPhotos/private-vault/name/v1/$id".toByteArray())
        try {
            VaultNames.clean(plain.toString(Charsets.UTF_8)).ifBlank { generatedName(data) }
        } finally {
            plain.fill(0)
        }
    }.getOrElse { generatedName(data) }
    private fun scopeName(binding: Pair<Long, String>) = "${binding.first}/${UUID.fromString(binding.second)}"
    private fun localChoices() {
        val candidates = listOf(baseDirectory) + File(baseDirectory, "slots").listFiles().orEmpty().filter { it.isDirectory }
        choicesMutable.value = candidates.mapNotNull { target ->
            runCatching {
                val stored = AtomicFile(File(target, "descriptor.json"))
                check(stored.baseFile.length() in 1..8192)
                val data = JSONObject(stored.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                val id = UUID.fromString(data.getString("id")).toString()
                VaultChoice(id, if (unlocked.value && vault.toString() == id) selectedName.value else generatedName(data), data.optLong("createdAt"))
            }.getOrNull()
        }.distinctBy { it.id }.sortedByDescending { it.createdAt }
    }
    private fun selectDirectory(target: File, binding: Pair<Long, String>) {
        lock()
        directory = target.apply { mkdirs() }
        descriptor = AtomicFile(File(directory, "descriptor.json"))
        expectedBinding = null
        selectedIdentity = ""
        if (exists) {
            val data = descriptorData()
            selectedIdentity = data.getString("id")
            selectedName.value = generatedName(data)
            selection.edit().putString(scopeName(binding), data.getString("id")).apply()
        }
        localChoices()
    }
    suspend fun selectVault(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            allowed()
            val binding = cloudStore.binding()
            val candidate = (listOf(baseDirectory) + File(baseDirectory, "slots").listFiles().orEmpty().toList()).first { target ->
                val stored = File(target, "descriptor.json")
                stored.length() in 1..8192 && JSONObject(stored.readText()).optString("id") == UUID.fromString(id).toString()
            }
            selectDirectory(candidate, binding)
            allowed()
        }
    }
    private val cloudStore by lazy { PrivateVaultCloudStore(context, app.drive, app.telegram) }
    val progress = MutableStateFlow<Pair<Long, Long>?>(null)
    val operation = MutableStateFlow("upload")
    private var descriptor = AtomicFile(File(directory, "descriptor.json"))
    private val mutex = Mutex()
    private var master: ByteArray? = null
    private var vault: UUID? = null

    @Volatile private var generation = 0L

    @Volatile private var expectedBinding: Pair<Long, String>? = null
    private var retryAfter = 0L
    private var failures = 0
    private val list = MutableStateFlow<List<PrivateVaultItem>>(emptyList())
    val items = list.asStateFlow()
    val unlocked = MutableStateFlow(false)
    val exists: Boolean get() = descriptor.baseFile.exists() || File(descriptor.baseFile.path + ".bak").exists()

    /** Switch storage only while locked; preserve the original unbound v1 Vault for its first owner. */
    suspend fun selectAccount() = withContext(Dispatchers.IO) {
        mutex.withLock {
            app.protection.requireAllowed()
            val binding = cloudStore.binding()
            check(app.telegram.accountId() == binding.first)
            val legacy = File(context.noBackupFilesDir, "private-vault-v1")
            val legacyDescriptor = AtomicFile(File(legacy, "descriptor.json"))
            fun boundSlots(base: File): List<Pair<File, JSONObject>> = File(base, "slots").listFiles().orEmpty().mapNotNull { target ->
                runCatching {
                    val file = File(target, "descriptor.json")
                    check(file.length() in 1..8192)
                    val data = JSONObject(file.readText())
                    check(data.getLong("cloudAccount") == binding.first && data.getString("cloudVault") == binding.second)
                    check(target.name == UUID.fromString(data.getString("id")).toString())
                    target to data
                }.getOrNull()
            }.sortedByDescending { it.second.optLong("createdAt") }
            val useLegacy = if (legacyDescriptor.baseFile.exists() || File(legacyDescriptor.baseFile.path + ".bak").exists()) {
                val data = legacyDescriptor.openRead().use {
                    check(legacyDescriptor.baseFile.length() in 1..8192)
                    JSONObject(it.readBytes().toString(Charsets.UTF_8))
                }
                if (!data.has("cloudAccount")) {
                    val assignment = "legacy-owner:${UUID.fromString(data.getString("id"))}"
                    val existingOwner = selection.getString(assignment, null)
                    if (existingOwner == null) selection.edit().putString(assignment, scopeName(binding)).commit()
                    existingOwner == null || existingOwner == scopeName(binding)
                } else {
                    data.getLong("cloudAccount") == binding.first && data.getString("cloudVault") == binding.second
                }
            } else {
                boundSlots(legacy).isNotEmpty()
            }
            val target = if (useLegacy) legacy else File(context.noBackupFilesDir, "private-vault-cloud-v2/${binding.first}-${UUID.fromString(binding.second)}")
            baseDirectory = target.apply {
                mkdirs()
                listFiles().orEmpty().filter { it.extension == "upload" }.forEach { it.delete() }
            }
            val slots = boundSlots(baseDirectory)
            val baseFile = File(baseDirectory, "descriptor.json")
            val baseId = if (baseFile.isFile) JSONObject(baseFile.readText()).getString("id") else null
            val selected = VaultDirectoryPolicy.choose(selection.getString(scopeName(binding), ""), baseId, slots.map { it.second.getString("id") })
            val preferred = slots.firstOrNull { it.second.getString("id") == selected }?.first
            selectDirectory(preferred ?: target, binding)
            allowed()
        }
    }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
    private fun descriptorData(): JSONObject = descriptor.openRead().use {
        check(descriptor.baseFile.length() in 1..8192)
        JSONObject(it.readBytes().toString(Charsets.UTF_8))
    }.also {
        check(it.getInt("version") == 1)
        expectedBinding = if (it.has("cloudAccount")) it.getLong("cloudAccount") to it.getString("cloudVault") else null
    }
    private fun allowed() {
        app.protection.requireAllowed()
        check(app.telegram.authorization.value.stage == AuthProtocol.State.READY) { "Sign in to Telegram first" }
        expectedBinding?.let { check(cloudStore.binding() == it) { "Open the matching Telegram account and Photos vault" } }
        expectedBinding?.let { binding ->
            check(app.forgottenVaults.tickets.value.none { it.account == binding.first && it.photos == binding.second && it.privateVault == selectedIdentity && it.phase in setOf("STARTED", "DONE") }) { "This Private Vault is being permanently deleted" }
        }
        check(!(context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceLocked)
    }

    fun lock() {
        synchronized(this) {
            generation++
            selectedName.value = ""
            choicesMutable.value = choicesMutable.value.map { choice -> choice.copy(name = VaultNames.display("", choice.createdAt, java.time.ZoneId.systemDefault(), choice.id)) }
            master?.fill(0)
            master = null
            vault = null
            list.value.forEach { it.wipe() }
            list.value = emptyList()
            unlocked.value = false
        }
        // Do not hold the session monitor while waiting for a reader doing network I/O.
        // Every in-flight callback checks the revoked epoch before returning plaintext.
        val closing = readers.entries.toList()
        closing.forEach { readers.remove(it.key, it.value) }
        CoroutineScope(Dispatchers.IO).launch {
            closing.forEach { (reader, handle) ->
                reader.close()
                runCatching { handle.close() }
            }
        }
    }

    @Synchronized private fun session(): Triple<ByteArray, UUID, Long> {
        allowed()
        return Triple(checkNotNull(master).clone(), checkNotNull(vault), generation)
    }

    @Synchronized fun currentEpoch(): Long {
        allowed()
        check(unlocked.value)
        return generation
    }

    @Synchronized fun valid(epoch: Long): Boolean = unlocked.value && generation == epoch && runCatching { allowed() }.isSuccess
    private fun context(id: UUID) = "TGPhotos/private-vault/v1/$id".toByteArray()
    private fun metadataContext(id: String) = "TGPhotos/private-vault/metadata/v1/$id".toByteArray()
    private fun file(id: String, ext: String) = File(directory, "${UUID.fromString(id)}.$ext")
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
    suspend fun discover() = withContext(Dispatchers.IO) {
        mutex.withLock {
            allowed()
            val binding = cloudStore.binding()
            val roots = cloudStore.discoverRoots()
            for (data in roots) {
                val id = UUID.fromString(data.getString("id")).toString()
                val current = if (exists) descriptorData().getString("id") else ""
                val target = if (current == id) descriptor else AtomicFile(File(baseDirectory, "slots/$id/descriptor.json").apply { parentFile!!.mkdirs() })
                write(target, data.toString().toByteArray())
            }
            if (!exists && roots.isNotEmpty()) selectDirectory(File(baseDirectory, "slots/${roots.first().getString("id")}"), binding)
            localChoices()
        }
    }
    private fun verifiedPassword(key: ByteArray, id: UUID, password: CharArray) {
        check(android.os.SystemClock.elapsedRealtime() >= retryAfter) { "Wait before trying again" }
        val data = descriptorData()
        if (!RecoveryEnvelope.verify(key, password, decode(data.getString("salt")), decode(data.getString("wrapped")), context(id))) {
            failures++
            if (failures >= 3) retryAfter = android.os.SystemClock.elapsedRealtime() + 30_000
            error("Vault password did not match")
        }
        failures = 0
        retryAfter = 0
    }

    /** The new recovery key is retained only in an authenticated Vault-key envelope. */
    suspend fun revealPrivateKey(password: CharArray): String = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                val (key, id, epoch) = session()
                try {
                    verifiedPassword(key, id, password)
                    val data = descriptorData()
                    if (!data.has("recoveryDisplay")) {
                        // Older recovery phrases were intentionally not retained. Add a NEW viewable key
                        // without invalidating the owner's original recovery envelope or changing file/master keys.
                        val recovery = VaultCrypto.randomBytes(32).joinToString("") { "%02x".format(it.toInt() and 255) }
                        val wrapped = RecoveryEnvelope.rewrap(key, recovery.toCharArray(), context(id))
                        data.put("privateKeySalt", b64(wrapped.salt())).put("privateKeyWrapped", b64(wrapped.wrapped()))
                        data.put("recoveryDisplay", b64(VaultCrypto.seal(key, recovery.toByteArray(), "TGPhotos/private-vault/recovery-display/v1/$id".toByteArray())))
                        check(valid(epoch))
                        if (data.has("cloudAccount")) cloudStore.publishRoot(data)
                        check(valid(epoch))
                        write(descriptor, data.toString().toByteArray())
                    }
                    val bytes = VaultCrypto.open(key, decode(data.getString("recoveryDisplay")), "TGPhotos/private-vault/recovery-display/v1/$id".toByteArray())
                    try {
                        check(valid(epoch))
                        bytes.toString(Charsets.US_ASCII)
                    } finally {
                        bytes.fill(0)
                    }
                } finally {
                    key.fill(0)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }
    suspend fun refreshCloud() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (key, id, epoch) = session()
            try {
                val descriptor = descriptorData()
                if (!descriptor.has("cloudAccount")) return@withLock
                val remote = cloudStore.discoverItems(key, id, cloudStore.binding()) { check(valid(epoch)) }
                for ((objectId, sealed) in remote) {
                    check(valid(epoch))
                    write(AtomicFile(file(objectId, "cloud")), sealed)
                }
                // Only cloud metadata cache entries absent from the authenticated remote catalog are discarded.
                val present = remote.map { it.first }.toSet()
                directory.listFiles().orEmpty().filter { it.extension == "cloud" && it.nameWithoutExtension !in present }.forEach { it.delete() }
                replaceItems(key, epoch)
            } finally {
                key.fill(0)
            }
        }
    }
    private fun replaceItems(key: ByteArray, epoch: Long) {
        val content = readItems(key)
        synchronized(this) {
            if (valid(epoch)) {
                list.value.forEach { it.wipe() }
                list.value = content
            } else {
                content.forEach { it.wipe() }
                error("Vault locked")
            }
        }
    }

    /** Returns only after both layers and the complete server copy have been authenticated. */
    suspend fun import(uri: Uri, relativeName: String? = null): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val job = currentCoroutineContext()
            val (key, id, epoch) = session()
            val fileKey = VaultCrypto.randomKey()
            val objectId = UUID.randomUUID().toString()
            val inner = File(directory, "$objectId.upload")
            var stage = VaultImportStage.SOURCE
            try {
                check(list.value.size < 10_000)
                var name = "Private file"
                var size = -1L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst()) {
                        name = it.getString(0).take(512)
                        if (!it.isNull(1)) size = it.getLong(1)
                    }
                }
                if (relativeName != null) {
                    check(relativeName.length <= 512 && relativeName.split('/').all { it.isNotBlank() && it !in setOf(".", "..") })
                    name = relativeName
                }
                check(size in 0..63L * 1024 * 1024 * 1024) { "Original kept: provider does not expose a stable size" }
                check(VaultCrypto.encryptedLength(VaultCrypto.encryptedLength(size)) * 3 + 16L * 1024 * 1024 < directory.usableSpace) { "Original kept: insufficient encrypted staging space" }
                val binding = cloudStore.binding()
                val descriptor = descriptorData()
                stage = VaultImportStage.CLOUD_ROOT
                if (!descriptor.has("cloudAccount")) {
                    val existing = cloudStore.discoverRoot()
                    check(existing == null || existing.getString("id") == id.toString()) { "A different cloud Vault exists. The older local Vault and cloud files were preserved." }
                    descriptor.put("cloudAccount", binding.first).put("cloudVault", binding.second)
                    cloudStore.publishRoot(descriptor)
                    check(valid(epoch))
                    write(this@PrivateVaultRepository.descriptor, descriptor.toString().toByteArray())
                }
                cloudStore.ensureRoot(descriptor)
                stage = VaultImportStage.ENCRYPTION
                operation.value = "encrypt-upload"
                progress.value = 0L to size
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val hash = context.contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(inner).use { output ->
                        VaultCrypto.encrypt(input, output, fileKey, UUID.fromString(binding.second), UUID.fromString(objectId), size) {
                            job.ensureActive()
                            check(valid(epoch))
                            progress.value = it to size
                        }.also { output.fd.sync() }
                    }
                }
                operation.value = "upload"
                val facts = MediaFacts.read(context, uri, mime)
                val originalDate = CaptureTime.backup(facts.capturedAt, 0, if (facts.capturedAt > 0) 0 else MediaTools.captureDate(context, uri), System.currentTimeMillis())
                val metadata = cloudStore.upload(inner, name, mime, size, hash, fileKey, key, id, objectId, binding, facts, originalDate, {
                    job.ensureActive()
                    check(valid(epoch))
                }, { next ->
                    stage = next
                    operation.value = when (next) {
                        VaultImportStage.ENCRYPTION -> "encrypt-upload"
                        VaultImportStage.SERVER_VERIFICATION -> "verify-upload"
                        VaultImportStage.COMMIT -> "commit-upload"
                        else -> "upload"
                    }
                }, { done, total -> progress.value = done to total })
                check(valid(epoch))
                write(AtomicFile(file(objectId, "cloud")), metadata)
                replaceItems(key, epoch)
                // The independent server copy is verified. Retire normal AI representations too.
                app.drive.notePrivateIntelligenceHash(hash)
                check(valid(epoch))
                objectId
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                throw PrivateVaultImportFailure(stage, failure)
            } finally {
                progress.value = null
                key.fill(0)
                fileKey.fill(0)
                inner.delete()
            }
        }
    }
    suspend fun migrateLegacy(password: CharArray) = withContext(Dispatchers.IO) {
        try {
            val proof = mutex.withLock {
                val (key, id, epoch) = session()
                try {
                    verifiedPassword(key, id, password)
                    id to epoch
                } finally {
                    key.fill(0)
                }
            }
            val ids = list.value.filter { it.cloud == null }.map { it.id }
            for (id in ids) {
                check(valid(proof.second))
                import(uri(id)) // Full server verification completes before the old encrypted source is removed.
                mutex.withLock {
                    val (key, vaultId, epoch) = session()
                    try {
                        check(epoch == proof.second && vaultId == proof.first)
                        deleteVerified(item(id), vaultId, epoch)
                        replaceItems(key, epoch)
                    } finally {
                        key.fill(0)
                    }
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }
    suspend fun exportEncrypted(ids: Set<String>, destination: Uri, zipPassword: CharArray, password: CharArray, remove: Boolean) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                val (key, vaultId, epoch) = session()
                try {
                    verifiedPassword(key, vaultId, password)
                    check(ids.isNotEmpty() && ids.size <= 1000)
                    val entries = ids.map { item(it) }
                    check(zipPassword.size >= 12) { "Use at least 12 characters for the ZIP password" }
                    val names = entries.mapIndexed { index, entry -> VaultZipSupport.name(index, entry.mime) }
                    val total = entries.sumOf { it.size }
                    var transferred = 0L
                    operation.value = "zip"
                    progress.value = 0L to total
                    context.contentResolver.openOutputStream(destination, "wt")!!.use { output ->
                        net.lingala.zip4j.io.outputstream.ZipOutputStream(output, zipPassword).use { zip ->
                            entries.forEachIndexed { index, entry ->
                                check(valid(epoch))
                                zip.putNextEntry(VaultZipSupport.entry(names[index], entry.size))
                                val (reader, _) = reader(entry.id)
                                try {
                                    val buffer = ByteArray(65536)
                                    try {
                                        var offset = 0L
                                        while (offset < entry.size) {
                                            currentCoroutineContext().ensureActive()
                                            check(valid(epoch))
                                            val n = reader.read(offset, buffer, 0, minOf(buffer.size.toLong(), entry.size - offset).toInt())
                                            check(n > 0)
                                            zip.write(buffer, 0, n)
                                            offset += n
                                            transferred += n
                                            progress.value = transferred to total
                                        }
                                    } finally {
                                        buffer.fill(0)
                                    }
                                } finally {
                                    close(reader)
                                }
                                zip.closeEntry()
                            }
                            val index = org.json.JSONArray(entries.mapIndexed { i, entry -> JSONObject().put("entry", names[i]).put("name", entry.name).put("mime", entry.mime) }).toString().toByteArray()
                            try {
                                zip.putNextEntry(VaultZipSupport.entry("index.json", index.size.toLong()))
                                zip.write(index)
                                zip.closeEntry()
                            } finally {
                                index.fill(0)
                            }
                        }
                    }
                    operation.value = "verify-zip"
                    // Re-open the user's actual destination, authenticate each ZIP entry and compare full original hashes.
                    context.contentResolver.openInputStream(destination)!!.use { input ->
                        net.lingala.zip4j.io.inputstream.ZipInputStream(input, zipPassword).use { zip ->
                            entries.forEachIndexed { index, entry ->
                                check(zip.nextEntry.fileName == names[index])
                                val digest = MessageDigest.getInstance("SHA-256")
                                val buffer = ByteArray(65536)
                                var count = 0L
                                try {
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        check(valid(epoch))
                                        val n = zip.read(buffer)
                                        if (n < 0) break
                                        count += n
                                        check(count <= entry.size)
                                        digest.update(buffer, 0, n)
                                    }
                                } finally {
                                    buffer.fill(0)
                                }
                                check(count == entry.size && MessageDigest.isEqual(digest.digest(), entry.hash))
                            }
                            check(zip.nextEntry.fileName == "index.json")
                            val buffer = ByteArray(4096)
                            try {
                                while (zip.read(buffer) >= 0) {
                                    check(valid(epoch))
                                    currentCoroutineContext().ensureActive()
                                }
                            } finally {
                                buffer.fill(0)
                            }
                            check(zip.nextEntry == null)
                        }
                    }
                    if (remove) for (entry in entries) deleteVerified(entry, vaultId, epoch)
                    replaceItems(key, epoch)
                } finally {
                    key.fill(0)
                }
            }
        } finally {
            progress.value = null
            password.fill('\u0000')
            zipPassword.fill('\u0000')
        }
    }
    private suspend fun deleteVerified(entry: PrivateVaultItem, vaultId: UUID, epoch: Long) {
        check(valid(epoch))
        if (entry.cloud != null) cloudStore.delete(entry, vaultId, cloudStore.binding()) { check(valid(epoch)) }
        check(valid(epoch))
        // No Trash/history entry. Flash wear-leveling prevents reliable physical overwrite guarantees.
        check(!file(entry.id, "cipher").exists() || file(entry.id, "cipher").delete())
        for (extension in listOf("meta", "cloud")) AtomicFile(file(entry.id, extension)).delete()
    }
    suspend fun deleteSelected(ids: Set<String>, password: CharArray) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                val (key, vaultId, epoch) = session()
                try {
                    verifiedPassword(key, vaultId, password)
                    check(ids.isNotEmpty())
                    for (entry in ids.map { item(it) }) deleteVerified(entry, vaultId, epoch)
                    replaceItems(key, epoch)
                } finally {
                    key.fill(0)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }
    suspend fun deleteEntireVault(privateKey: CharArray) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                val (key, vaultId, epoch) = session()
                try {
                    check(android.os.SystemClock.elapsedRealtime() >= retryAfter)
                    val data = descriptorData()
                    val newMatches = data.has("privateKeySalt") && RecoveryEnvelope.verify(key, privateKey.clone(), decode(data.getString("privateKeySalt")), decode(data.getString("privateKeyWrapped")), context(vaultId))
                    if (!newMatches && !RecoveryEnvelope.verify(key, privateKey, decode(data.getString("recoverySalt")), decode(data.getString("recoveryWrapped")), context(vaultId))) {
                        failures++
                        if (failures >= 3) retryAfter = android.os.SystemClock.elapsedRealtime() + 30_000
                        error("Private key did not match")
                    }
                    if (data.has("cloudAccount")) cloudStore.deleteAll(cloudStore.binding(), vaultId) { check(valid(epoch)) }
                    for (entry in list.value.toList()) {
                        check(valid(epoch))
                        check(!file(entry.id, "cipher").exists() || file(entry.id, "cipher").delete())
                        for (extension in listOf("meta", "cloud")) AtomicFile(file(entry.id, extension)).delete()
                    }
                    DeviceVaultKey(context).erase(-17L, vaultId.toString())
                    biometricKey().erase()
                    descriptor.delete()
                    expectedBinding = null
                    lock()
                } finally {
                    key.fill(0)
                }
            }
        } finally {
            privateKey.fill('\u0000')
        }
    }
    var replacementDeletionPending: Boolean = false
        private set
    suspend fun create(password: CharArray, requestedName: String = "", replacement: Boolean = false): String = withContext(Dispatchers.IO) {
        replacementDeletionPending = false
        try {
            mutex.withLock {
                check(!exists || replacement)
                allowed()
                val epoch = synchronized(this@PrivateVaultRepository) { generation }
                val replacedId = if (replacement && exists) descriptorData().getString("id") else null
                check(NewPasswordPolicy.accepts(password))
                val key = VaultCrypto.randomKey()
                val id = UUID.randomUUID()
                val recovery = VaultCrypto.randomBytes(32).joinToString("") { "%02x".format(it.toInt() and 255) }
                try {
                    val phrase = RecoveryEnvelope.rewrap(key, password, context(id))
                    val offline = RecoveryEnvelope.rewrap(key, recovery.toCharArray(), context(id))
                    val binding = cloudStore.binding()
                    val createdAt = System.currentTimeMillis()
                    val name = VaultNames.forCreation(requestedName, createdAt, java.time.ZoneId.systemDefault(), id.toString()).toByteArray()
                    val data = JSONObject().put("version", 1).put("id", id.toString()).put("salt", b64(phrase.salt())).put("wrapped", b64(phrase.wrapped()))
                        .put("cloudAccount", binding.first).put("cloudVault", binding.second)
                        .put("recoveryDisplay", b64(VaultCrypto.seal(key, recovery.toByteArray(), "TGPhotos/private-vault/recovery-display/v1/$id".toByteArray())))
                        .put("recoverySalt", b64(offline.salt())).put("recoveryWrapped", b64(offline.wrapped()))
                    try {
                        data.put("createdAt", createdAt).put("sealedName", b64(VaultCrypto.seal(key, name, "TGPhotos/private-vault/name/v1/$id".toByteArray())))
                    } finally {
                        name.fill(0)
                    }
                    val target = if (replacement) File(baseDirectory, "slots/$id") else directory
                    val targetDescriptor = AtomicFile(File(target, "descriptor.json").apply { parentFile!!.mkdirs() })
                    cloudStore.publishRoot(data)
                    write(targetDescriptor, data.toString().toByteArray())
                    // Never expose a key whose creation finished after the screen left the foreground.
                    check(generation == epoch)
                    allowed()
                    selectDirectory(target, binding)
                    if (replacedId != null) {
                        replacementDeletionPending = runCatching { app.forgottenVaults.queueReplacement(binding.first, binding.second, replacedId) }.isFailure
                    }
                    // Creation stays locked until the user has copied and confirmed their recovery key.
                    recovery
                } finally {
                    key.fill(0)
                    password.fill('\u0000')
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }
    private fun biometricKey(): VaultBiometricKey {
        val data = descriptorData()
        val binding = cloudStore.binding()
        return VaultBiometricKey(directory, "${binding.first}/${binding.second}/${UUID.fromString(data.getString("id"))}")
    }
    suspend fun biometricAvailable(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            allowed()
            exists && biometricKey().available()
        }
    }
    suspend fun prepareBiometricEnrollment(password: CharArray): VaultBiometricKey.Request = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                val (key, id, epoch) = session()
                try {
                    verifiedPassword(key, id, password)
                    check(valid(epoch))
                    biometricKey().enrollment(key, epoch)
                } finally {
                    key.fill(0)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }
    suspend fun prepareBiometricUnlock(): VaultBiometricKey.Request = withContext(Dispatchers.IO) {
        mutex.withLock {
            allowed()
            check(!unlocked.value)
            biometricKey().unlocking(generation)
        }
    }
    suspend fun disableBiometric(password: CharArray) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                val (key, id, epoch) = session()
                try {
                    verifiedPassword(key, id, password)
                    check(valid(epoch))
                    biometricKey().erase()
                } finally {
                    key.fill(0)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }
    suspend fun completeBiometric(request: VaultBiometricKey.Request, cipher: javax.crypto.Cipher) = withContext(Dispatchers.IO) {
        mutex.withLock {
            allowed()
            check(request.epoch == generation && request.scope == biometricKey().identity) { "Biometric request expired" }
            if (request.enrolling) {
                check(unlocked.value)
                request.finish(cipher)
            } else {
                check(!unlocked.value)
                val key = checkNotNull(request.finish(cipher))
                var content = emptyList<PrivateVaultItem>()
                var installed = false
                try {
                    val data = descriptorData()
                    val id = UUID.fromString(data.getString("id"))
                    content = readItems(key)
                    synchronized(this@PrivateVaultRepository) {
                        allowed()
                        check(generation == request.epoch)
                        lock()
                        master = key.clone()
                        vault = id
                        list.value = content
                        unlocked.value = true
                        selectedName.value = decodedName(key, data, id)
                        installed = true
                    }
                    localChoices()
                } finally {
                    if (!installed) content.forEach { it.wipe() }
                    key.fill(0)
                }
            }
        }
    }
    suspend fun unlock(password: CharArray, recovery: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                allowed()
                check(android.os.SystemClock.elapsedRealtime() >= retryAfter) { "Wait before trying again." }
                val epoch = synchronized(this@PrivateVaultRepository) { generation }
                val data = descriptorData()
                allowed()
                val id = UUID.fromString(data.getString("id"))
                var derived: ByteArray? = null
                var key: ByteArray? = null
                var content = emptyList<PrivateVaultItem>()
                var installed = false
                try {
                    if (recovery && data.has("privateKeySalt")) {
                        try {
                            derived = VaultCrypto.recoveryKey(password, decode(data.getString("privateKeySalt")))
                            key = VaultCrypto.open(derived, decode(data.getString("privateKeyWrapped")), context(id))
                        } catch (_: java.security.GeneralSecurityException) {
                            derived?.fill(0)
                            derived = null
                        }
                    }
                    if (key == null) {
                        derived = VaultCrypto.recoveryKey(password, decode(data.getString(if (recovery) "recoverySalt" else "salt")))
                        key = VaultCrypto.open(derived, decode(data.getString(if (recovery) "recoveryWrapped" else "wrapped")), context(id))
                    }
                    check(key.size == 32)
                    // Only authenticated envelope failures count as incorrect credentials.
                    failures = 0
                    retryAfter = 0
                    localChoices()
                    content = readItems(key)
                    // Remove the former phone-only bypass only after successful password/key authentication.
                    runCatching { DeviceVaultKey(context).erase(-17L, id.toString()) }
                    synchronized(this@PrivateVaultRepository) {
                        allowed()
                        check(generation == epoch) { "Unlock cancelled because the app left the foreground." }
                        lock()
                        master = key.clone()
                        vault = id
                        list.value = content
                        unlocked.value = true
                        selectedName.value = decodedName(key, data, id)
                        installed = true
                    }
                    failures = 0
                    retryAfter = 0
                    localChoices()
                } catch (e: Exception) {
                    if (key == null && e is java.security.GeneralSecurityException) {
                        failures++
                        if (failures >= 3) retryAfter = android.os.SystemClock.elapsedRealtime() + 30_000
                    }
                    throw e
                } finally {
                    if (!installed) content.forEach { it.wipe() }
                    derived?.fill(0)
                    key?.fill(0)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }
    private fun readItems(key: ByteArray): List<PrivateVaultItem> {
        val result = mutableListOf<PrivateVaultItem>()
        try {
            directory.listFiles().orEmpty().filter { it.extension == "meta" }.forEach { entry ->
                check(entry.length() in 28..65536)
                val id = UUID.fromString(entry.nameWithoutExtension).toString()
                val plain = VaultCrypto.open(key, AtomicFile(entry).openRead().use { it.readBytes() }, metadataContext(id))
                try {
                    val data = JSONObject(plain.toString(Charsets.UTF_8))
                    val item = PrivateVaultItem(id, data.getString("name"), data.getString("mime"), data.getLong("size"), data.getLong("date"), decode(data.getString("hash")), decode(data.getString("fileKey")))
                    result.add(item)
                    check(item.fileKey.size == 32 && item.hash.size == 32 && file(id, "cipher").length() == VaultCrypto.encryptedLength(item.size))
                } finally {
                    plain.fill(0)
                }
            }
            val vaultId = UUID.fromString(descriptorData().getString("id"))
            directory.listFiles().orEmpty().filter { it.extension == "cloud" }.forEach { entry ->
                check(entry.length() in 28..1_048_576)
                val id = UUID.fromString(entry.nameWithoutExtension).toString()
                val data = cloudStore.openMetadata(AtomicFile(entry).openRead().use { it.readBytes() }, key, vaultId, id, cloudStore.binding())
                result.add(cloudStore.item(id, data))
            }
            return result.sortedByDescending { it.date }
        } catch (error: Exception) {
            result.forEach { it.wipe() }
            throw error
        }
    }

    @Synchronized fun uri(id: String): Uri {
        allowed()
        check(unlocked.value)
        return Uri.parse("content://${context.packageName}.private-media/private-vault/${UUID.fromString(id)}?epoch=$generation&expires=${android.os.SystemClock.elapsedRealtime() + 86_400_000}")
    }
    fun validateUri(uri: Uri) {
        check(valid(checkNotNull(uri.getQueryParameter("epoch")).toLong()) && checkNotNull(uri.getQueryParameter("expires")).toLong() > android.os.SystemClock.elapsedRealtime()) { "Vault access expired" }
    }

    @Synchronized fun item(id: String): PrivateVaultItem {
        allowed()
        check(unlocked.value)
        return list.value.first { it.id == id }
    }
    fun reader(id: String): Pair<AuthenticatedRecords, Long> {
        val (key, vaultId, epoch) = session()
        val entry = item(id)
        if (entry.cloud != null) {
            try {
                val (reader, close) = cloudStore.reader(entry, vaultId, cloudStore.binding()) { check(valid(epoch)) }
                synchronized(readers) {
                    if (!valid(epoch)) {
                        close.close()
                        error("Vault locked")
                    }
                    readers[reader] = close
                }
                return reader to epoch
            } finally {
                key.fill(0)
            }
        }
        val input = RandomAccessFile(file(id, "cipher"), "r")
        try {
            val reader = AuthenticatedRecords({ position, output, offset, length ->
                synchronized(input) {
                    input.seek(position)
                    input.readFully(output, offset, length)
                }
            }, entry.fileKey, vaultId, UUID.fromString(id), entry.size, input.length())
            synchronized(readers) {
                if (!valid(epoch)) {
                    reader.close()
                    input.close()
                    error("Vault locked")
                }
                readers[reader] = input
            }
            return reader to epoch
        } catch (e: Exception) {
            input.close()
            throw e
        } finally {
            key.fill(0)
        }
    }
    private val readers = java.util.concurrent.ConcurrentHashMap<AuthenticatedRecords, AutoCloseable>()
    fun close(reader: AuthenticatedRecords) {
        reader.close()
        synchronized(readers) { readers.remove(reader)?.close() }
    }

    /** Portable ciphertext-only backup, separate from development source snapshots. */
    suspend fun backup(destination: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val job = currentCoroutineContext()
            val (key, vaultId, epoch) = session()
            var content = emptyList<PrivateVaultItem>()
            try {
                content = readItems(key)
                check(content.none { it.cloud != null }) { "Use password-protected ZIP download for cloud Vault files" }
                content.forEach { entry ->
                    file(entry.id, "cipher").inputStream().use { input ->
                        VaultCrypto.decrypt(
                            input,
                            object : OutputStream() {
                                override fun write(value: Int) {} override fun write(bytes: ByteArray, offset: Int, length: Int) {}
                            },
                            entry.fileKey,
                            vaultId,
                            UUID.fromString(entry.id),
                            entry.size,
                            entry.hash
                        ) {
                            check(valid(epoch))
                            job.ensureActive()
                        }
                    }
                }
                context.contentResolver.openOutputStream(destination, "wt")!!.use { output ->
                    java.util.zip.ZipOutputStream(output).use { zip ->
                        val files = listOf(descriptor.baseFile) + content.flatMap { listOf(file(it.id, "meta"), file(it.id, "cipher")) }
                        files.forEach { source ->
                            check(valid(epoch))
                            zip.putNextEntry(java.util.zip.ZipEntry(source.name))
                            source.inputStream().use { input ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    job.ensureActive()
                                    check(valid(epoch))
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    zip.write(buffer, 0, count)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, destination) }
                throw e
            } finally {
                content.forEach { it.wipe() }
                key.fill(0)
            }
        }
    }
    suspend fun restoreBackup(source: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            allowed()
            check(!exists && directory.listFiles().orEmpty().isEmpty()) { "Restore requires an empty Private Vault. Existing files are preserved." }
            val staging = File(context.noBackupFilesDir, "private-vault-restore-${UUID.randomUUID()}").apply { check(mkdir()) }
            val moved = mutableListOf<File>()
            var complete = false
            try {
                val seen = mutableSetOf<String>()
                var total = 0L
                context.contentResolver.openInputStream(source)!!.use { input ->
                    java.util.zip.ZipInputStream(input).use { zip ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = zip.nextEntry ?: break
                            val name = entry.name
                            check(!entry.isDirectory && seen.add(name) && seen.size <= 20_001)
                            check(name == "descriptor.json" || name.matches(Regex("[0-9a-f-]{36}\\.(meta|cipher)")))
                            val target = File(staging, name)
                            check(target.canonicalFile.parentFile == staging.canonicalFile)
                            var length = 0L
                            FileOutputStream(target).use { output ->
                                val bytes = ByteArray(65536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    allowed()
                                    val count = zip.read(bytes)
                                    if (count < 0) break
                                    length += count
                                    total += count
                                    check(total <= 64L * 1024 * 1024 * 1024 && directory.usableSpace > 16 * 1024 * 1024)
                                    check(
                                        if (name == "descriptor.json") {
                                            length <= 8192
                                        } else if (name.endsWith(".meta")) {
                                            length <= 65536
                                        } else {
                                            length <= 64L * 1024 * 1024 * 1024
                                        }
                                    )
                                    output.write(bytes, 0, count)
                                }
                                output.fd.sync()
                            }
                            zip.closeEntry()
                        }
                    }
                }
                val header = JSONObject(File(staging, "descriptor.json").readText())
                check(header.getInt("version") == 1)
                UUID.fromString(header.getString("id"))
                check(decode(header.getString("salt")).size == 16 && decode(header.getString("wrapped")).size == 60)
                check(decode(header.getString("recoverySalt")).size == 16 && decode(header.getString("recoveryWrapped")).size == 60)
                val children = staging.listFiles().orEmpty()
                children.filter { it.extension == "meta" }.forEach { check(File(staging, "${it.nameWithoutExtension}.cipher").isFile) }
                children.filter { it.extension == "cipher" }.forEach { check(File(staging, "${it.nameWithoutExtension}.meta").isFile) }
                // Descriptor last: a failed copy is never advertised as a restorable Vault.
                children.sortedBy { it.name == "descriptor.json" }.forEach { file ->
                    val target = File(directory, file.name)
                    check(!target.exists())
                    check(file.renameTo(target))
                    moved.add(target)
                }
                complete = true
            } finally {
                if (!complete) moved.forEach { it.delete() }
                staging.deleteRecursively()
            }
        }
    }
}
