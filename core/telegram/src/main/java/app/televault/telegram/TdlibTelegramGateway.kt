package app.televault.telegram

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import app.televault.domain.AuthProtocol
import app.televault.domain.ForgottenVaultPolicy
import app.televault.domain.IntelligencePrivacy
import app.televault.domain.OwnedCloudCaption
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.JsonClient
import org.json.JSONObject

/** Application owns one gateway: one receive loop, one TDLib client, no UI/JNI coupling. */
class TdlibTelegramGateway(context: Context, private val credentials: TelegramCredentials, private val protection: TransportProtection = OpenTransportProtection, private val retainCiphertext: () -> Boolean = { false }) : TelegramGateway {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionHistory = TelegramSessionHistory(app)
    private val state = MutableStateFlow(AuthSnapshot(previouslyAuthorized = sessionHistory.previouslyAuthorized))
    override val authorization = state.asStateFlow()
    private val requests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val sequence = AtomicLong()
    private val commands = Mutex()

    @Volatile private var clientId = 0
    private val lifecycle = Mutex()
    private var receiver: kotlinx.coroutines.Job? = null

    @Volatile private var protectionRestart = false
    private var dbKey: ByteArray? = null
    private var parametersStarted = false

    @Volatile private var explicitlyClosed = false
    private val files = ConcurrentHashMap<Int, JSONObject>()
    private val fileChanges = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    private val fileLocks = Array(64) { Mutex() }
    private val nativeRetainedBytes = ConcurrentHashMap<Int, Long>()
    private var restartAttempts = 0
    private val nativeStreamRetirements = app.getSharedPreferences("stream-native-retirements", Context.MODE_PRIVATE)
    private val documentIds = ConcurrentHashMap<Pair<Long, Long>, Int>()
    private val sent = ConcurrentHashMap<Long, JSONObject>()
    private var selfChatId = 0L

    init {
        if (!credentials.valid()) {
            state.value = AuthSnapshot(AuthProtocol.State.SETUP_REQUIRED, previouslyAuthorized = sessionHistory.previouslyAuthorized, setupIssue = SetupIssue.CREDENTIALS)
        } else {
            scope.launch {
                protection.paused.collect { paused ->
                    lifecycle.withLock {
                        if (paused || protection.blocked()) {
                            stopForProtection()
                        } else if (clientId == 0) {
                            start()
                        }
                    }
                }
            }
        }
    }

    private suspend fun stopForProtection() {
        protectionRestart = true
        if (clientId == 0) return
        JsonClient.send(clientId, json("setNetworkType").put("type", json("networkTypeNone")).toString())
        requests.values.forEach { it.completeExceptionally(IllegalStateException("Protection active")) }
        requests.clear()
        JsonClient.send(clientId, json("close").toString())
        kotlinx.coroutines.withTimeoutOrNull(10000) { receiver?.join() }
    }
    override fun sessionDiagnostics(): String = sessionHistory.summary()
    override suspend fun reconnect() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!credentials.valid() || protection.blocked()) return@withContext
        lifecycle.withLock {
            explicitlyClosed = false
            if (clientId == 0) {
                start()
            } else if (state.value.stage == AuthProtocol.State.CLOSED) {
                if (receiver?.isActive == true) {
                    protectionRestart = true
                    JsonClient.send(clientId, json("close").toString())
                } else if (closeBrokenClient(clientId)) {
                    clientId = 0
                    start()
                }
            } else if (state.value.stage == AuthProtocol.State.PARAMETERS && state.value.error != null) {
                protectionRestart = true
                JsonClient.send(clientId, json("close").toString())
            }
        }
    }

    /** Only the receive-loop owner, or its successor after it stopped, may drain native updates. */
    private fun closeBrokenClient(id: Int): Boolean = runCatching {
        JsonClient.send(id, json("close").toString())
        val deadline = SystemClock.elapsedRealtime() + 5000
        var closed = false
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            val event = JsonClient.receive(0.25)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            closed = event.optInt("@client_id", id) == id && event.optString("@type") == "updateAuthorizationState" &&
                event.optJSONObject("authorization_state")?.optString("@type") == "authorizationStateClosed"
        }
        closed
    }.getOrDefault(false)
    private suspend fun start() {
        if (protection.blocked() || explicitlyClosed) return
        if ((app.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceLocked) {
            state.value = AuthSnapshot(AuthProtocol.State.SETUP_REQUIRED, previouslyAuthorized = sessionHistory.previouslyAuthorized, setupIssue = SetupIssue.DEVICE_KEY)
            return
        }
        protectionRestart = false
        parametersStarted = false
        state.value = AuthSnapshot(previouslyAuthorized = sessionHistory.previouslyAuthorized)
        try {
            System.loadLibrary("tdjsonjava")
        } catch (_: UnsatisfiedLinkError) {
            state.value = AuthSnapshot(AuthProtocol.State.SETUP_REQUIRED, previouslyAuthorized = sessionHistory.previouslyAuthorized, setupIssue = SetupIssue.NATIVE_LIBRARY)
            return
        }
        try {
            protection.requireAllowed()
            dbKey = SessionKeyStore(app).loadOrCreate()
        } catch (_: Exception) {
            state.value = AuthSnapshot(AuthProtocol.State.SETUP_REQUIRED, previouslyAuthorized = sessionHistory.previouslyAuthorized, setupIssue = SetupIssue.DEVICE_KEY)
            return
        }
        try {
            JsonClient.execute(json("setLogStream").put("log_stream", json("logStreamEmpty")).toString())
            JsonClient.execute(json("setLogVerbosityLevel").put("new_verbosity_level", 0).toString())
            clientId = JsonClient.createClientId()
            receiver = scope.launch {
                var nativeClosed = false
                try {
                    while (isActive) {
                        val raw = JsonClient.receive(0.5) ?: continue
                        val event = JSONObject(raw)
                        dispatch(event)
                        if (event.optInt("@client_id", clientId) == clientId && event.optString("@type") == "updateAuthorizationState" && event.optJSONObject("authorization_state")?.optString("@type") == "authorizationStateClosed") {
                            nativeClosed = true
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    restartAttempts++
                    nativeClosed = closeBrokenClient(clientId)
                    protectionRestart = nativeClosed && !explicitlyClosed && restartAttempts <= 3
                    state.update { it.copy(stage = AuthProtocol.State.CLOSED, error = internalError(), busy = false) }
                } finally {
                    requests.values.forEach { it.completeExceptionally(IllegalStateException("TDLib closed")) }
                    requests.clear()
                    dbKey?.fill(0)
                    dbKey = null
                    if (nativeClosed) clientId = 0
                    files.clear()
                    documentIds.clear()
                    sent.clear()
                    nativeRetainedBytes.clear()
                    selfChatId = 0
                    if (protectionRestart && nativeClosed) {
                        scope.launch {
                            delay(2000)
                            lifecycle.withLock { if (clientId == 0 && !protection.blocked() && !explicitlyClosed) start() }
                        }
                    }
                }
            }
            // This also starts delivery of authorization updates.
            request(json("getAuthorizationState"))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            state.update { it.copy(stage = AuthProtocol.State.CLOSED, error = internalError(), busy = false) }
            if (clientId != 0 && receiver?.isActive == true) {
                restartAttempts++
                protectionRestart = !explicitlyClosed && restartAttempts <= 3
                runCatching { JsonClient.send(clientId, json("close").toString()) }
            } else if (clientId == 0) {
                dbKey?.fill(0)
                dbKey = null
            }
        }
    }

    private fun dispatch(event: JSONObject) {
        if (event.optInt("@client_id", clientId) != clientId) return
        val extra = event.optString("@extra")
        if (extra.isNotEmpty()) requests.remove(extra)?.complete(event)
        val type = event.optString("@type")
        if (type == "updateFile") {
            val file = event.getJSONObject("file")
            val id = file.getInt("id")
            if (files.size >= 1024 && !files.containsKey(id)) files.keys.firstOrNull()?.let { files.remove(it) }
            files[id] = file
            fileChanges.tryEmit(id)
        }
        if (type == "updateMessageSendSucceeded" || type == "updateMessageSendFailed") {
            sent[event.getLong("old_message_id")] = event
        }
        if (type == "updateAuthorizationState") {
            onAuthorization(event.getJSONObject("authorization_state"))
        } else if (type.startsWith("authorizationState")) {
            onAuthorization(event)
        }
    }

    private fun onAuthorization(auth: JSONObject) {
        val stage = AuthProtocol.map(auth.optString("@type"))
        sessionHistory.observe(stage)
        if (stage == AuthProtocol.State.READY) restartAttempts = 0
        if (stage in setOf(AuthProtocol.State.CLOSED, AuthProtocol.State.LOGGING_OUT)) {
            selfChatId = 0
            documentIds.clear()
        }
        val info = auth.optJSONObject("code_info")
        val next = AuthProtocol.delivery(info?.optJSONObject("next_type")?.optString("@type").orEmpty())
        val seconds = info?.optLong("timeout", 0)?.coerceIn(0, 86400) ?: 0
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000
        state.update {
            AuthSnapshot(
                stage = stage,
                previouslyAuthorized = sessionHistory.previouslyAuthorized,
                busy = it.busy,
                terms = auth.optJSONObject("terms_of_service")?.optJSONObject("text")?.optString("text").orEmpty(),
                confirmationLink = auth.optString("link"),
                delivery = AuthProtocol.delivery(info?.optJSONObject("type")?.optString("@type").orEmpty()),
                nextDelivery = next,
                resendAtElapsedMillis = deadline,
                codeLength = info?.optJSONObject("type")?.optInt("length", 0)?.coerceIn(0, 30) ?: 0,
                callPattern = info?.optJSONObject("type")?.optString("pattern").orEmpty().take(128),
                callPrefix = info?.optJSONObject("type")?.optString("phone_number_prefix").orEmpty().take(40),
                phoneNumber = if (stage == AuthProtocol.State.READY || stage == AuthProtocol.State.CLOSED) "" else info?.optString("phone_number")?.take(32) ?: it.phoneNumber,
                retryAtElapsedMillis = if (stage == it.stage) it.retryAtElapsedMillis else 0
            )
        }
        if (stage == AuthProtocol.State.PARAMETERS && !parametersStarted) {
            parametersStarted = true
            scope.launch {
                commands.withLock {
                    perform {
                        val database = File(app.noBackupFilesDir, "tdlib/database").apply { mkdirs() }
                        val files = File(app.noBackupFilesDir, "tdlib/files").apply { mkdirs() }
                        val key = checkNotNull(dbKey)
                        try {
                            request(
                                json("setTdlibParameters")
                                    .put("use_test_dc", false)
                                    .put("database_directory", database.absolutePath)
                                    .put("files_directory", files.absolutePath)
                                    .put("database_encryption_key", Base64.encodeToString(key, Base64.NO_WRAP))
                                    .put("use_file_database", true)
                                    .put("use_chat_info_database", true)
                                    .put("use_message_database", true)
                                    .put("use_secret_chats", false)
                                    .put("api_id", credentials.apiId)
                                    .put("api_hash", credentials.apiHash)
                                    .put("system_language_code", "en")
                                    .put("device_model", Build.MODEL)
                                    .put("system_version", "Android ${Build.VERSION.RELEASE}")
                                    .put("application_version", app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "TG Photos")
                            )
                        } finally {
                            key.fill(0)
                            dbKey = null
                        }
                    }
                }
            }
        }
    }

    override suspend fun submit(action: AuthProtocol.Action, value: String, lastName: String) {
        commands.withLock {
            val current = state.value
            if (!AuthProtocol.permits(current.stage, action) || current.busy) return
            if (SystemClock.elapsedRealtime() < current.retryAtElapsedMillis) return
            if (action == AuthProtocol.Action.RESEND_CODE && !AuthProtocol.canResend(current.nextDelivery, SystemClock.elapsedRealtime(), current.resendAtElapsedMillis, current.retryAtElapsedMillis, current.busy)) return
            if (action !in setOf(AuthProtocol.Action.LOGOUT, AuthProtocol.Action.RESEND_CODE) && value.isBlank()) return
            perform {
                val input = json(AuthProtocol.method(action))
                when (action) {
                    AuthProtocol.Action.PHONE -> input.put("phone_number", value).put(
                        "settings",
                        json("phoneNumberAuthenticationSettings")
                            .put("allow_flash_call", true).put("allow_missed_call", true)
                            .put("is_current_phone_number", false).put("has_unknown_phone_number", false)
                            .put("allow_sms_retriever_api", false).put("firebase_authentication_settings", JSONObject.NULL).put("authentication_tokens", org.json.JSONArray())
                    )
                    AuthProtocol.Action.CODE -> input.put("code", value)
                    AuthProtocol.Action.PASSWORD -> input.put("password", value)
                    AuthProtocol.Action.EMAIL -> input.put("email_address", value)
                    AuthProtocol.Action.EMAIL_CODE -> input.put("code", json("emailAddressAuthenticationCode").put("code", value))
                    AuthProtocol.Action.REGISTER -> input.put("first_name", value).put("last_name", lastName).put("disable_notification", true)
                    AuthProtocol.Action.RESEND_CODE -> input.put("reason", json("resendCodeReasonUserRequest"))
                    AuthProtocol.Action.LOGOUT -> Unit
                }
                request(input)
            }
        }
    }

    private suspend fun perform(block: suspend () -> JSONObject) {
        state.update { it.copy(busy = true, error = null) }
        try {
            val response = block()
            if (response.optString("@type") == "error") {
                val error = AuthProtocol.sanitize(response.optInt("code"), response.optString("message"))
                // Saturating wait arithmetic; never shorten a server-provided wait.
                val now = SystemClock.elapsedRealtime()
                val seconds = error.retryAfterSeconds()
                val until = if (seconds > (Long.MAX_VALUE - now) / 1000) Long.MAX_VALUE else now + seconds * 1000
                state.update { it.copy(error = error, retryAtElapsedMillis = until) }
            }
        } catch (_: TimeoutCancellationException) {
            state.update { it.copy(error = AuthProtocol.SafeError(AuthProtocol.Failure.TIMEOUT, 0)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.update { it.copy(error = internalError()) }
        } finally {
            state.update { it.copy(busy = false) }
        }
    }

    private suspend fun request(input: JSONObject): JSONObject {
        if (input.optString("@type") != "close") protection.requireAllowed()
        check(clientId != 0)
        val id = sequence.incrementAndGet().toString()
        val result = CompletableDeferred<JSONObject>()
        requests[id] = result
        try {
            JsonClient.send(clientId, input.put("@extra", id).toString())
            return withTimeout(45_000) { result.await() }
        } finally {
            requests.remove(id)
            result.cancel()
        }
    }

    private suspend fun checked(input: JSONObject): JSONObject {
        check(state.value.stage == AuthProtocol.State.READY) { "Telegram authorization required" }
        val result = request(input)
        if (result.optString("@type") == "error") {
            throw TelegramFailure(AuthProtocol.sanitize(result.optInt("code"), result.optString("message")))
        }
        return result
    }

    override suspend fun ownerProfile(): TelegramOwnerProfile {
        val me = checked(json("getMe"))
        val names = me.optJSONObject("usernames")?.optJSONArray("active_usernames")
        val username = names?.optString(0).orEmpty()
        val name = listOf(me.optString("first_name"), me.optString("last_name")).filter { it.isNotBlank() }.joinToString(" ").take(80)
        // Telegram's actual inline avatar preview: no public-profile plaintext download/cache.
        val avatar = me.optJSONObject("profile_photo")?.optJSONObject("minithumbnail")?.optString("data").orEmpty()
        return TelegramOwnerProfile(me.getLong("id"), name, username.take(64), avatar.takeIf { it.length <= 32768 }.orEmpty())
    }
    override suspend fun accountId(): Long = checked(json("getMe")).getLong("id")

    override suspend fun phoneCountries(): List<CountryDialingInfo> {
        val response = request(json("getCountries")) // Official TDLib permits this before authorization.
        check(response.optString("@type") == "countries")
        val countries = response.getJSONArray("countries")
        return (0 until countries.length()).mapNotNull { index ->
            val country = countries.getJSONObject(index)
            if (country.optBoolean("is_hidden")) {
                null
            } else {
                val codes = country.getJSONArray("calling_codes")
                CountryDialingInfo(country.getString("country_code"), country.getString("name"), country.optString("flag_emoji"), (0 until codes.length()).map { codes.getString(it) }.filter { it.matches(Regex("[0-9]{1,4}")) })
            }
        }.filter { it.codes.isNotEmpty() }
    }
    override suspend fun suggestedPhoneCountry(): String {
        val response = request(json("getCountryCode")) // Telegram's IP estimate, no third-party location SDK.
        check(response.optString("@type") == "text")
        return response.getString("text").uppercase().takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
    }

    override suspend fun activeSessions(): List<TelegramSessionInfo> {
        val sessions = checked(json("getActiveSessions")).getJSONArray("sessions")
        return (0 until sessions.length()).map { index ->
            val session = sessions.getJSONObject(index)
            TelegramSessionInfo(session.getLong("id"), session.getBoolean("is_current"), session.optString("application_name"), session.optString("device_model"), session.optString("platform"))
        }
    }
    override suspend fun terminateOtherSessions() {
        val response = checked(json("terminateAllOtherSessions"))
        check(response.getString("@type") == "ok")
    }

    override suspend fun uploadLimit(): Long {
        val me = checked(json("getMe"))
        val published = if (me.optBoolean("is_premium")) 4L * 1024 * 1024 * 1024 else 2L * 1024 * 1024 * 1024
        val option = checked(json("getOption").put("name", "uploaded_file_size_max"))
        val actual = option.optLong("value", 0)
        return if (actual > 0) minOf(actual, published) else published
    }

    private suspend fun selfChat(): Long {
        if (selfChatId == 0L) selfChatId = checked(json("createPrivateChat").put("user_id", accountId()).put("force", false)).getLong("id")
        return selfChatId
    }

    private suspend fun sendDocumentRetained(file: File, marker: String, progress: (Long, Long) -> Unit): RemoteDocument {
        require(marker.startsWith("TGDRIVE_V1_") && file.extension == "bin")
        val chat = selfChat()
        val result = checked(
            json("sendMessage").put("chat_id", chat).put(
                "input_message_content",
                json("inputMessageDocument")
                    .put(
                        "document",
                        json("inputDocument")
                            .put("document", json("inputFileLocal").put("path", file.absolutePath))
                            .put("disable_content_type_detection", true)
                    )
                    .put("caption", json("formattedText").put("text", marker).put("entities", org.json.JSONArray()))
            )
        )
        val temporaryId = result.getLong("id")
        val final = if (result.isNull("sending_state")) {
            result
        } else {
            withTimeout(30 * 60_000L) {
                val documentFile = result.getJSONObject("content").getJSONObject("document").getJSONObject("document")
                val fileId = documentFile.getInt("id")
                try {
                    while (true) {
                        coroutineContext.ensureActive()
                        val terminal = sent.remove(temporaryId)
                        if (terminal != null) {
                            if (terminal.optString("@type") == "updateMessageSendFailed") {
                                val error = terminal.getJSONObject("error")
                                throw TelegramFailure(AuthProtocol.sanitize(error.optInt("code"), error.optString("message")))
                            }
                            return@withTimeout terminal.getJSONObject("message")
                        }
                        files[fileId]?.optJSONObject("remote")?.let { progress(it.optLong("uploaded_size"), file.length()) }
                        delay(250)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    result
                } catch (failure: Throwable) {
                    scope.launch { runCatching { checked(json("deleteMessages").put("chat_id", chat).put("message_ids", org.json.JSONArray(listOf(temporaryId))).put("revoke", true)) } }
                    throw failure
                } finally {
                    files.remove(fileId)
                }
            }
        }
        // A final server message is independently resolved before callers may commit metadata.
        val verified = checked(json("getMessage").put("chat_id", chat).put("message_id", final.getLong("id")))
        check(verified.isNull("sending_state")) { "Upload not confirmed" }
        val size = verified.getJSONObject("content").getJSONObject("document").getJSONObject("document").getLong("size")
        check(size == file.length()) { "Remote byte count differs" }
        progress(size, size)
        return RemoteDocument(chat, verified.getLong("id"), size, verified.optLong("date").coerceIn(0, Int.MAX_VALUE.toLong()) * 1000, marker)
    }

    override suspend fun findDocuments(marker: String): List<RemoteDocument> = searchDocuments(marker, Int.MAX_VALUE)
    override suspend fun findNewestDocuments(marker: String, limit: Int): List<RemoteDocument> = searchDocuments(marker, limit.coerceIn(1, 70))
    private suspend fun searchDocuments(marker: String, limit: Int): List<RemoteDocument> {
        require(marker.startsWith("TGDRIVE_V1_"))
        val chat = selfChat()
        val found = linkedMapOf<Long, RemoteDocument>()
        var cursor = 0L
        // Paginate until exhausted; do not truncate larger photo libraries at 10,000 items.
        while (true) {
            coroutineContext.ensureActive()
            val page = checked(
                json("searchChatMessages").put("chat_id", chat).put("query", marker)
                    .put("from_message_id", cursor).put("offset", 0).put("limit", 100)
            )
            val messages = page.getJSONArray("messages")
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                val content = message.optJSONObject("content") ?: continue
                val caption = content.optJSONObject("caption")?.optString("text").orEmpty()
                if (content.optString("@type") != "messageDocument" || !caption.startsWith(marker) || !message.isNull("sending_state")) continue
                if ((marker.startsWith("TGDRIVE_V1_PRIVATE_ROOT:") || marker.startsWith("TGDRIVE_V1_FILE:PV2:")) && caption != marker) continue
                val bytes = content.getJSONObject("document").getJSONObject("document").getLong("size")
                val id = message.getLong("id")
                found[id] = RemoteDocument(chat, id, bytes, message.optLong("date").coerceIn(0, Int.MAX_VALUE.toLong()) * 1000, caption)
            }
            val next = page.optLong("next_from_message_id")
            if (next == 0L || found.size >= limit) return found.values.sortedByDescending { it.messageId }.take(limit)
            check(next != cursor && (cursor == 0L || next < cursor)) { "Archive cursor did not advance" }
            cursor = next
        }
    }

    override suspend fun readLifecycleDocument(reference: RemoteDocument, marker: String): ByteArray {
        require(ForgottenVaultPolicy.lifecycleMarker(marker) && reference.chatId == selfChat() && reference.bytes in 1..8192)
        val message = checked(json("getMessage").put("chat_id", reference.chatId).put("message_id", reference.messageId))
        check(message.isNull("sending_state") && message.optBoolean("is_outgoing") && message.optLong("edit_date") == 0L && message.isNull("forward_info"))
        check(message.getLong("date") * 1000 == reference.confirmedAtMillis && reference.confirmedAtMillis > 0)
        val content = message.getJSONObject("content")
        check(content.getString("@type") == "messageDocument" && content.getJSONObject("caption").getString("text") == marker)
        val document = content.getJSONObject("document").getJSONObject("document")
        check(document.getLong("size") == reference.bytes)
        documentIds[reference.chatId to reference.messageId] = document.getInt("id")
        return readEncryptedDocumentPart(reference, 0, reference.bytes.toInt(), false, privateMarker = marker)
    }
    override suspend fun deleteLifecycleClock(reference: RemoteDocument, marker: String) {
        require(marker.contains(":CLOCK:") && ForgottenVaultPolicy.lifecycleMarker(marker))
        readLifecycleDocument(reference, marker).fill(0)
        checked(json("deleteMessages").put("chat_id", selfChat()).put("message_ids", org.json.JSONArray(listOf(reference.messageId))).put("revoke", true))
    }
    override suspend fun deleteVaultNamespace(references: List<RemoteDocument>, photos: String, privateVault: String?) {
        require(ForgottenVaultPolicy.validId(photos) && (privateVault == null || ForgottenVaultPolicy.validId(privateVault)))
        val chat = selfChat()
        val present = mutableListOf<Long>()
        val localFiles = mutableSetOf<Int>()
        for (reference in references) {
            require(reference.chatId == chat)
            val message = request(json("getMessage").put("chat_id", chat).put("message_id", reference.messageId))
            if (message.optString("@type") == "error" && message.optInt("code") == 404) continue
            check(message.optString("@type") == "message" && message.isNull("sending_state"))
            val content = message.getJSONObject("content")
            check(content.getString("@type") == "messageDocument")
            check(ForgottenVaultPolicy.owns(content.getJSONObject("caption").getString("text"), photos, privateVault))
            check(content.getJSONObject("document").getJSONObject("document").getLong("size") == reference.bytes)
            localFiles += content.getJSONObject("document").getJSONObject("document").getInt("id")
            present += reference.messageId
        }
        for (batch in present.chunked(100)) checked(json("deleteMessages").put("chat_id", chat).put("message_ids", org.json.JSONArray(batch)).put("revoke", true))
        for (id in localFiles) {
            checked(json("deleteFile").put("file_id", id))
            nativeStreamRetirements.edit().remove("$selfChatId/$id").commit()
            nativeRetainedBytes.remove(id)
            documentIds.entries.removeAll { it.value == id }
        }
    }
    override suspend fun deletePeopleDocuments(references: List<RemoteDocument>, photos: String, person: String) {
        require(ForgottenVaultPolicy.validId(photos) && ForgottenVaultPolicy.validId(person))
        val chat = selfChat()
        val messages = mutableListOf<Long>()
        val local = mutableListOf<Int>()
        for (reference in references) {
            require(reference.chatId == chat && IntelligencePrivacy.ownedPersonCaption(reference.marker, photos, person))
            val message = request(json("getMessage").put("chat_id", chat).put("message_id", reference.messageId))
            if (message.optString("@type") == "error" && message.optInt("code") == 404)continue
            check(message.optString("@type") == "message" && message.isNull("sending_state"))
            // Edited/forwarded copies were made outside this app; never delete them as owned uploads.
            if (!message.optBoolean("is_outgoing") || message.optLong("edit_date") != 0L || !message.isNull("forward_info"))continue
            val content = message.getJSONObject("content")
            check(content.getString("@type") == "messageDocument")
            check(content.getJSONObject("caption").getString("text") == reference.marker)
            val data = content.getJSONObject("document").getJSONObject("document")
            check(data.getLong("size") == reference.bytes)
            messages += reference.messageId
            local += data.getInt("id")
        }
        for (batch in messages.chunked(100))checked(json("deleteMessages").put("chat_id", chat).put("message_ids", org.json.JSONArray(batch)).put("revoke", true))
        for (id in local) {
            checked(json("deleteFile").put("file_id", id))
            nativeStreamRetirements.edit().remove("$selfChatId/$id").commit()
            nativeRetainedBytes.remove(id)
        }
    }
    override suspend fun deleteOwnedDocuments(references: List<RemoteDocument>, ownerMarker: String) {
        require(ownerMarker.startsWith(":"))
        val chat = selfChat()
        val present = mutableListOf<Long>()
        for (reference in references) {
            require(reference.chatId == chat)
            val message = request(json("getMessage").put("chat_id", chat).put("message_id", reference.messageId))
            if (message.optString("@type") == "error") {
                if (message.optInt("code") == 404) continue
                throw TelegramFailure(AuthProtocol.sanitize(message.optInt("code"), message.optString("message")))
            }
            val content = message.getJSONObject("content")
            check(content.optString("@type") == "messageDocument" && message.isNull("sending_state"))
            val caption = content.getJSONObject("caption").getString("text")
            check(OwnedCloudCaption.matches(caption, ownerMarker))
            if (reference.bytes > 0) check(content.getJSONObject("document").getJSONObject("document").getLong("size") == reference.bytes)
            present += reference.messageId
        }
        for (batch in present.chunked(100)) checked(json("deleteMessages").put("chat_id", chat).put("message_ids", org.json.JSONArray(batch)).put("revoke", true))
    }
    override suspend fun deletePrivateVaultRoots(references: List<RemoteDocument>, marker: String) {
        require(marker.matches(Regex("TGDRIVE_V1_PRIVATE_ROOT:[0-9a-f-]{36}")))
        val chat = selfChat()
        for (reference in references) {
            require(reference.chatId == chat && reference.bytes in 1..65536)
            val message = request(json("getMessage").put("chat_id", chat).put("message_id", reference.messageId))
            if (message.optString("@type") == "error" && message.optInt("code") == 404) continue
            check(message.optString("@type") == "message" && message.isNull("sending_state"))
            val content = message.getJSONObject("content")
            check(content.getString("@type") == "messageDocument")
            check(content.getJSONObject("caption").getString("text") == marker)
            check(content.getJSONObject("document").getJSONObject("document").getLong("size") == reference.bytes)
            checked(json("deleteMessages").put("chat_id", chat).put("message_ids", org.json.JSONArray(listOf(reference.messageId))).put("revoke", true))
        }
    }
    override suspend fun retireRecoveryRoots(references: List<RemoteDocument>, vaultId: String) {
        val identity = java.util.UUID.fromString(vaultId).toString()
        val chat = selfChat()
        for (reference in references) {
            require(reference.chatId == chat && reference.bytes in 1..65536)
            val message = request(json("getMessage").put("chat_id", chat).put("message_id", reference.messageId))
            if (message.optString("@type") == "error" && message.optInt("code") == 404) continue
            check(message.optString("@type") == "message")
            val content = message.getJSONObject("content")
            check(content.getString("@type") == "messageDocument" && message.isNull("sending_state"))
            check(content.getJSONObject("caption").getString("text") == "TGDRIVE_V1_ROOT:$identity")
            check(content.getJSONObject("document").getJSONObject("document").getLong("size") == reference.bytes)
            checked(json("deleteMessages").put("chat_id", chat).put("message_ids", org.json.JSONArray(listOf(reference.messageId))).put("revoke", true))
        }
    }
    private val nativeStorageGate = Mutex()
    private var nativeStorageUsers = 0
    private suspend fun <T> usingNativeStorage(block: suspend () -> T): T {
        nativeStorageGate.withLock { nativeStorageUsers++ }
        try {
            return block()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { nativeStorageGate.withLock { nativeStorageUsers-- } }
        }
    }
    override suspend fun sendDocument(file: File, marker: String, progress: (Long, Long) -> Unit): RemoteDocument = usingNativeStorage { sendDocumentRetained(file, marker, progress) }
    override suspend fun downloadDocument(reference: RemoteDocument, progress: (Long, Long) -> Unit): File = usingNativeStorage { downloadDocumentRetained(reference, progress) }
    override suspend fun pruneLocalStreamCache() {
        if (state.value.stage != AuthProtocol.State.READY) return
        val chat = selfChat()
        // TDLib owns this directory. Its optimizer removes only re-downloadable local documents;
        // never filesystem-delete its database, session, queued sources or Telegram messages.
        nativeStorageGate.withLock {
            if (nativeStorageUsers == 0) {
                withTimeout(5000) {
                    checked(
                        json("optimizeStorage").put("size", 64L * 1024 * 1024).put("ttl", 300).put("count", 2048).put("immunity_delay", 60)
                            .put("file_types", org.json.JSONArray().put(json("fileTypeDocument")))
                            .put("chat_ids", org.json.JSONArray()).put("exclude_chat_ids", org.json.JSONArray())
                            .put("return_deleted_file_statistics", false).put("chat_limit", 0)
                    )
                }
            }
        }
        val expired = nativeStreamRetirements.all.entries.filter { it.key.startsWith("$chat/") }.mapNotNull { entry ->
            runCatching { entry.key to JSONObject(entry.value as String) }.getOrNull()
        }.filter { System.currentTimeMillis() >= it.second.optLong("expires", Long.MAX_VALUE) }.take(16)
        for ((name, value) in expired) {
            val id = name.substringAfter('/').toIntOrNull() ?: continue
            fileLocks[id.and(Int.MAX_VALUE) % fileLocks.size].withLock {
                runCatching {
                    val message = checked(json("getMessage").put("chat_id", chat).put("message_id", value.getLong("message")))
                    val content = message.getJSONObject("content")
                    val marker = value.optString("privateMarker")
                    val caption = content.getJSONObject("caption").getString("text")
                    check(if (marker.isNotBlank()) caption == marker else caption.startsWith("TGDRIVE_V1_DATA:"))
                    check(content.getJSONObject("document").getJSONObject("document").getInt("id") == id)
                    checked(json("deleteFile").put("file_id", id))
                    nativeRetainedBytes.remove(id)
                    nativeStreamRetirements.edit().remove(name).commit()
                }
            }
        }
    }
    override suspend fun readPrivateVaultPart(reference: RemoteDocument, offset: Long, count: Int, marker: String, access: () -> Unit): ByteArray {
        access()
        require(marker.startsWith("TGDRIVE_V1_DATA:PV2:") || marker.startsWith("TGDRIVE_V1_FILE:PV2:") || marker.startsWith("TGDRIVE_V1_PRIVATE_ROOT:"))
        require(reference.chatId == selfChat())
        val message = checked(json("getMessage").put("chat_id", reference.chatId).put("message_id", reference.messageId))
        val content = message.getJSONObject("content")
        check(message.isNull("sending_state") && content.getString("@type") == "messageDocument")
        check(content.getJSONObject("caption").getString("text") == marker)
        val document = content.getJSONObject("document").getJSONObject("document")
        check(document.getLong("size") == reference.bytes)
        documentIds[reference.chatId to reference.messageId] = document.getInt("id")
        return readEncryptedDocumentPart(reference, offset, count, false, access, marker)
    }
    override suspend fun readDocumentPart(reference: RemoteDocument, offset: Long, count: Int): ByteArray = readEncryptedDocumentPart(reference, offset, count, true)
    override suspend fun readSpeculativeDocumentPart(reference: RemoteDocument, offset: Long, count: Int): ByteArray = readEncryptedDocumentPart(reference, offset, count, true, priority = 4)
    private suspend fun readEncryptedDocumentPart(reference: RemoteDocument, offset: Long, count: Int, allowRetention: Boolean, access: () -> Unit = {}, privateMarker: String = "", priority: Int = 32): ByteArray = usingNativeStorage { readEncryptedDocumentPartRetained(reference, offset, count, allowRetention, access, privateMarker, priority) }
    private suspend fun readEncryptedDocumentPartRetained(reference: RemoteDocument, offset: Long, count: Int, allowRetention: Boolean, access: () -> Unit, privateMarker: String, priority: Int): ByteArray {
        require(reference.chatId == selfChat() && offset >= 0 && count in 1..(2 * 1024 * 1024) && offset <= reference.bytes - count)
        val identity = reference.chatId to reference.messageId
        val id = documentIds[identity] ?: run {
            val message = checked(json("getMessage").put("chat_id", reference.chatId).put("message_id", reference.messageId))
            val content = message.getJSONObject("content")
            check(content.getJSONObject("caption").getString("text").startsWith("TGDRIVE_V1_DATA:"))
            val document = content.getJSONObject("document").getJSONObject("document")
            check(document.getLong("size") == reference.bytes)
            document.getInt("id").also {
                if (documentIds.size >= 2048) documentIds.keys.firstOrNull()?.let { oldest -> documentIds.remove(oldest) }
                documentIds[identity] = it
            }
        }
        return fileLocks[id.and(Int.MAX_VALUE) % fileLocks.size].withLock {
            val retirement = "$selfChatId/$id"
            if (!nativeStreamRetirements.contains(retirement) || !allowRetention) nativeStreamRetirements.edit().putString(retirement, JSONObject().put("message", reference.messageId).put("expires", System.currentTimeMillis() + if (allowRetention) 86400000L else 0L).put("privateMarker", privateMarker).toString()).commit()
            try {
                NativeCipherRanges.read(
                    offset,
                    count,
                    prefix = { checked(json("getFileDownloadedPrefixSize").put("file_id", id).put("offset", offset)).getLong("size") },
                    localFile = { checked(json("getFile").put("file_id", id)).getJSONObject("local").optString("path").takeIf { it.isNotBlank() }?.let(::File) },
                    download = {
                        checked(json("downloadFile").put("file_id", id).put("priority", priority.coerceIn(1, 32)).put("offset", offset).put("limit", count).put("synchronous", false))
                        Unit
                    },
                    access = access,
                    awaitChange = {
                        // Native completion wakes this file immediately; bounded polling covers a missed event.
                        withTimeoutOrNull(100) { fileChanges.first { it == id } }
                        Unit
                    }
                )
            } catch (failure: Throwable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { withTimeout(5000) { checked(json("cancelDownloadFile").put("file_id", id).put("only_if_pending", false)) } } }
                throw failure
            } finally {
                // Amortize LOCAL ciphertext cleanup, without fetching any extra network bytes.
                // Data Saver still removes the native copy after every requested range.
                val retained = nativeRetainedBytes.merge(id, count.toLong()) { before, added -> before + added } ?: count.toLong()
                if (!allowRetention || !retainCiphertext() || retained >= 8L * 1024 * 1024 || nativeRetainedBytes.values.sum() >= 32L * 1024 * 1024) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { withTimeout(5000) { checked(json("cancelDownloadFile").put("file_id", id).put("only_if_pending", false)) } }
                        runCatching {
                            withTimeout(5000) {
                                checked(json("deleteFile").put("file_id", id))
                                nativeStreamRetirements.edit().remove(retirement).commit()
                                nativeRetainedBytes.remove(id)
                            }
                        }
                    }
                }
            }
        }
    }
    private suspend fun downloadDocumentRetained(reference: RemoteDocument, progress: (Long, Long) -> Unit): File {
        require(reference.chatId == selfChat())
        val message = checked(json("getMessage").put("chat_id", reference.chatId).put("message_id", reference.messageId))
        val content = message.getJSONObject("content")
        check(content.getJSONObject("caption").getString("text").startsWith("TGDRIVE_V1_")) { "Document is not app-owned" }
        val document = content.getJSONObject("document").getJSONObject("document")
        val id = document.getInt("id")
        return fileLocks[id.and(Int.MAX_VALUE) % fileLocks.size].withLock {
            val initial = checked(
                json("downloadFile").put("file_id", id).put("priority", 16)
                    .put("offset", 0).put("limit", 0).put("synchronous", false)
            )
            files.putIfAbsent(id, initial)
            try {
                withTimeout(30 * 60_000L) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val current = files[id] ?: initial
                        val local = current.getJSONObject("local")
                        progress(local.optLong("downloaded_size"), current.optLong("size", reference.bytes))
                        if (local.optBoolean("is_downloading_completed")) {
                            val output = File(local.getString("path"))
                            check(output.isFile && output.length() == reference.bytes) { "Downloaded bytes differ" }
                            return@withTimeout output
                        }
                        delay(250)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    File("")
                }
            } catch (e: CancellationException) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runCatching { withTimeout(5000) { checked(json("cancelDownloadFile").put("file_id", id).put("only_if_pending", false)) } } }
                throw e
            } finally {
                files.remove(id)
            }
        }
    }

    override suspend fun close() {
        explicitlyClosed = true
        protectionRestart = false
        if (clientId != 0 && state.value.stage != AuthProtocol.State.CLOSED) {
            perform { request(json("close")) }
        }
    }

    private fun json(type: String) = JSONObject().put("@type", type)
    private fun internalError() = AuthProtocol.SafeError(AuthProtocol.Failure.INTERNAL, 0)
}
