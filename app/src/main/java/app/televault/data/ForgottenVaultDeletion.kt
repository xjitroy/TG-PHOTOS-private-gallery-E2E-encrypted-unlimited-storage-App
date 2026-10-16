package app.televault.data

import android.content.Context
import androidx.work.*
import app.televault.TeleVaultApplication
import app.televault.domain.AuthProtocol
import app.televault.domain.ForgottenVaultPolicy
import app.televault.telegram.RemoteDocument
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Opaque IDs only. Passwords, names and content keys never enter lifecycle messages. */
internal data class ForgottenVaultTicket(val id: String, val account: Long, val photos: String, val privateVault: String?, val requestedAt: Long, val phase: String) {
    val dueAt: Long get() = requestedAt + ForgottenVaultPolicy.GRACE_MILLIS
    val target: String get() = privateVault ?: photos
}

internal fun pendingDeletionRequests(tickets: List<ForgottenVaultTicket>, target: ForgottenVaultTicket): List<ForgottenVaultTicket> = tickets.filter {
    it.account == target.account && it.photos == target.photos && it.privateVault == target.privateVault && it.phase in setOf("PENDING", "STARTED")
}

/** Deletion requires an unedited server-confirmed request and a fresh server clock proof.
 * Recovery/cancellation lives in Saved Messages, so clearing app data cannot bypass the wait.
 */
internal class ForgottenVaultDeletion(private val app: TeleVaultApplication) {
    private val telegram get() = app.telegram
    private val work = WorkManager.getInstance(app)
    private val processing = Mutex()
    private val recovery = Mutex()
    private var recoveredAt = 0L
    private var recoveredOwner = 0L
    private val mutable = MutableStateFlow<List<ForgottenVaultTicket>>(emptyList())
    val tickets = mutable.asStateFlow()
    private val outbox = File(app.noBackupFilesDir, "vault-deletion-outbox").apply { mkdirs() }

    /** Written only after the replacement root was confirmed; retries never lose the intent. */
    fun queueReplacement(account: Long, photos: String, privateVault: String?): String {
        require(account != 0L && ForgottenVaultPolicy.validId(photos) && (privateVault == null || ForgottenVaultPolicy.validId(privateVault)))
        mutable.value.firstOrNull { it.account == account && it.photos == photos && it.privateVault == privateVault && it.phase in setOf("SCHEDULING", "PENDING", "STARTED") }?.let { return it.id }
        val id = UUID.randomUUID().toString()
        val file = android.util.AtomicFile(File(outbox, "$id.json"))
        val stream = file.startWrite()
        try {
            stream.write(body(account, photos, privateVault, id).toString().toByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        mutable.value = mutable.value + ForgottenVaultTicket(id, account, photos, privateVault, 0, "SCHEDULING")
        work.enqueueUniqueWork("tgphotos-forgotten-vault-outbox", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ForgottenVaultWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        return id
    }
    private fun marker(id: String, state: String) = "TGDRIVE_V1_RESET:${UUID.fromString(id)}:$state"
    private fun body(owner: Long, photos: String, privateVault: String?, id: String) = JSONObject().put("version", 1).put("account", owner).put("photos", photos).put("private", privateVault ?: JSONObject.NULL).put("request", id)
    private suspend fun owner(): Long {
        app.protection.requireAllowed()
        check(telegram.authorization.value.stage == AuthProtocol.State.READY) { "Sign in to Telegram first" }
        return telegram.accountId()
    }
    private suspend fun send(data: JSONObject, caption: String): RemoteDocument {
        check(ForgottenVaultPolicy.lifecycleMarker(caption))
        val directory = File(app.noBackupFilesDir, "vault-lifecycle-staging").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.bin")
        return try {
            file.writeText(data.toString())
            telegram.sendDocument(file, caption) { _, _ -> }.also { reference ->
                val confirmed = telegram.readLifecycleDocument(reference, caption)
                try {
                    check(JSONObject(confirmed.toString(Charsets.UTF_8)).toString() == data.toString())
                } finally {
                    confirmed.fill(0)
                }
            }
        } finally {
            file.delete()
        }
    }
    private suspend fun checkedBody(reference: RemoteDocument, caption: String, expectedOwner: Long): JSONObject {
        val bytes = telegram.readLifecycleDocument(reference, caption)
        return try {
            JSONObject(bytes.toString(Charsets.UTF_8)).also {
                check(it.getInt("version") == 1 && it.getLong("account") == expectedOwner)
                check(ForgottenVaultPolicy.validId(it.getString("photos")) && ForgottenVaultPolicy.validId(it.getString("request")))
                if (!it.isNull("private")) check(ForgottenVaultPolicy.validId(it.getString("private")))
                check(caption.startsWith(marker(it.getString("request"), "")))
            }
        } finally {
            bytes.fill(0)
        }
    }
    private suspend fun status(ticket: ForgottenVaultTicket): String {
        check(owner() == ticket.account)
        suspend fun first(state: String): RemoteDocument? {
            val exact = marker(ticket.id, state)
            return telegram.findDocuments(exact).filter { it.marker == exact }.sortedBy { it.messageId }.firstOrNull { reference ->
                val data = checkedBody(reference, exact, ticket.account)
                check(reference.confirmedAtMillis >= ticket.requestedAt)
                check(data.getString("photos") == ticket.photos && data.optString("private").takeUnless { it == "null" || it.isBlank() } == ticket.privateVault)
                true
            }
        }
        val cancelled = first("CANCEL")
        val started = first("START")
        val done = first("DONE")
        return ForgottenVaultPolicy.phase(ticket.requestedAt, cancelled?.messageId ?: 0, started?.messageId ?: 0, started?.confirmedAtMillis ?: 0, done?.messageId ?: 0, done?.confirmedAtMillis ?: 0).name
    }
    suspend fun request(photos: String, privateVault: String? = null): ForgottenVaultTicket = withContext(Dispatchers.IO) {
        require(ForgottenVaultPolicy.validId(photos) && (privateVault == null || ForgottenVaultPolicy.validId(privateVault)))
        val account = owner()
        val id = queueReplacement(account, photos, privateVault)
        recover()
        checkNotNull(mutable.value.find { it.id == id })
    }
    suspend fun cancel(id: String) = withContext(Dispatchers.IO) {
        recover(force = true)
        val account = owner()
        val ticket = checkNotNull(mutable.value.find { it.id == id && it.account == account })
        check(status(ticket) == "PENDING") { "Deletion already started; it can no longer be cancelled" }
        // Concurrent devices can have registered more than one request for one target.
        // Cancel every pending request, so a restored replacement intent cannot resurrect it.
        val pending = pendingDeletionRequests(mutable.value, ticket)
        check(pending.all { status(it) == "PENDING" }) { "Deletion already started on another request" }
        for (request in pending) {
            send(body(request.account, request.photos, request.privateVault, request.id), marker(request.id, "CANCEL"))
            check(status(request) == "CANCELLED") { "Deletion started before cancellation reached Telegram" }
            work.cancelUniqueWork("tgphotos-forgotten-vault:${request.id}")
        }
        recover(force = true)
    }
    suspend fun recover(force: Boolean = false) = withContext(Dispatchers.IO) {
        recovery.withLock {
            val account = owner()
            val elapsed = android.os.SystemClock.elapsedRealtime()
            if (!force && recoveredOwner == account && elapsed >= recoveredAt && elapsed - recoveredAt < 5000 && outbox.listFiles().orEmpty().none { it.extension == "json" }) return@withLock
            for (file in outbox.listFiles().orEmpty().filter { it.extension == "json" }) {
                check(file.length() in 1..8192)
                val data = JSONObject(android.util.AtomicFile(file).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                if (data.getLong("account") != account) continue
                val id = UUID.fromString(data.getString("request")).toString()
                check(file.name == "$id.json" && ForgottenVaultPolicy.validId(data.getString("photos")))
                if (!data.isNull("private")) check(ForgottenVaultPolicy.validId(data.getString("private")))
                val caption = marker(id, "REQUEST")
                val existing = telegram.findDocuments(caption).filter { it.marker == caption }.minByOrNull { it.messageId }
                if (existing == null) send(data, caption) else checkedBody(existing, caption, account)
                android.util.AtomicFile(file).delete()
            }
            val references = telegram.findDocuments("TGDRIVE_V1_RESET:")
            val restored = mutableListOf<ForgottenVaultTicket>()
            for (reference in references.filter { it.marker.endsWith(":REQUEST") }.sortedBy { it.messageId }) {
                try {
                    val data = checkedBody(reference, reference.marker, account)
                    val id = data.getString("request")
                    check(reference.marker == marker(id, "REQUEST"))
                    var ticket = ForgottenVaultTicket(id, account, data.getString("photos"), if (data.isNull("private")) null else data.getString("private"), reference.confirmedAtMillis, "PENDING")
                    check(ticket.requestedAt > 0)
                    ticket = ticket.copy(phase = status(ticket))
                    if (ticket.phase == "DONE") {
                        try {
                            app.drive.retireCompletedDeletion(ticket)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            schedule(ticket)
                        }
                    }
                    restored += ticket
                    if (ticket.phase in setOf("PENDING", "STARTED")) schedule(ticket)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) { /* Invalid/edited requests never authorize deletion. */ }
            }
            mutable.value = restored.distinctBy { it.id }.sortedByDescending { it.requestedAt }
            recoveredAt = android.os.SystemClock.elapsedRealtime()
            recoveredOwner = account
        }
    }
    private fun schedule(ticket: ForgottenVaultTicket) {
        val delay = (ticket.dueAt - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ForgottenVaultWorker>().setInputData(workDataOf("request" to ticket.id))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        work.enqueueUniqueWork("tgphotos-forgotten-vault:${ticket.id}", ExistingWorkPolicy.KEEP, request)
    }
    suspend fun execute(id: String) = withContext(Dispatchers.IO) {
        processing.withLock {
            recover(force = true)
            val ticket = mutable.value.find { it.id == id } ?: return@withLock
            if (ticket.phase == "DONE") {
                app.drive.retireCompletedDeletion(ticket)
                return@withLock
            }
            if (ticket.phase !in setOf("PENDING", "STARTED")) return@withLock
            val caption = marker(id, "CLOCK:${UUID.randomUUID()}")
            val proof = send(body(ticket.account, ticket.photos, ticket.privateVault, id), caption)
            try {
                check(ForgottenVaultPolicy.due(ticket.requestedAt, proof.confirmedAtMillis)) { "The full 24-hour server grace period has not elapsed" }
            } finally {
                telegram.deleteLifecycleClock(proof, caption)
            }
            if (status(ticket) == "PENDING") send(body(ticket.account, ticket.photos, ticket.privateVault, id), marker(id, "START"))
            suspend fun access() {
                check(owner() == ticket.account && status(ticket) == "STARTED") { "Deletion cancelled or account changed" }
            }
            access()
            mutable.value = mutable.value.map { if (it.id == id) it.copy(phase = "STARTED") else it }
            if (ticket.privateVault == null) app.drive.deleteForgottenPhotos(ticket.account, ticket.photos, ::access) else app.drive.deleteForgottenPrivate(ticket.account, ticket.photos, ticket.privateVault, ::access)
            access()
            send(body(ticket.account, ticket.photos, ticket.privateVault, id), marker(id, "DONE"))
            recover(force = true)
        }
    }
    fun scheduleRecovery() {
        val request = PeriodicWorkRequestBuilder<ForgottenVaultWorker>(1, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        work.enqueueUniquePeriodicWork("tgphotos-forgotten-vault-recovery", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

class ForgottenVaultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as TeleVaultApplication
        app.telegram.reconnect()
        withTimeout(45000) { app.telegram.authorization.first { it.stage == AuthProtocol.State.READY } }
        val id = inputData.getString("request")
        if (id == null) {
            app.forgottenVaults.recover()
        } else {
            setForeground(transferForeground(app, id.hashCode(), "Removing a forgotten vault after its 24-hour waiting period"))
            app.forgottenVaults.execute(id)
        }
        Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        Result.retry()
    }
}
