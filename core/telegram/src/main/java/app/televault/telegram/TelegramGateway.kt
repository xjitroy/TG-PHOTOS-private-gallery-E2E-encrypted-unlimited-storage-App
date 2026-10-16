package app.televault.telegram

import app.televault.domain.AuthProtocol
import java.io.File
import kotlinx.coroutines.flow.StateFlow

data class CountryDialingInfo(val iso: String, val name: String, val flag: String, val codes: List<String>)

data class TelegramSessionInfo(val id: Long, val current: Boolean, val application: String, val device: String, val platform: String)

data class TelegramOwnerProfile(val accountId: Long, val name: String, val username: String, val avatarPreview: String)

data class RemoteDocument(val chatId: Long, val messageId: Long, val bytes: Long, val confirmedAtMillis: Long = 0, val marker: String = "")
class TelegramFailure(val safe: AuthProtocol.SafeError) : Exception("Telegram request failed")

enum class SetupIssue { CREDENTIALS, NATIVE_LIBRARY, DEVICE_KEY }

data class AuthSnapshot(
    val stage: AuthProtocol.State = AuthProtocol.State.STARTING,
    val busy: Boolean = false,
    val error: AuthProtocol.SafeError? = null,
    val setupIssue: SetupIssue? = null,
    val terms: String = "",
    val confirmationLink: String = "",
    val retryAtElapsedMillis: Long = 0,
    val delivery: AuthProtocol.CodeDelivery = AuthProtocol.CodeDelivery.NONE,
    val nextDelivery: AuthProtocol.CodeDelivery = AuthProtocol.CodeDelivery.NONE,
    val resendAtElapsedMillis: Long = 0,
    val codeLength: Int = 0,
    val callPattern: String = "",
    val callPrefix: String = "",
    val phoneNumber: String = ""
)

// Deliberately not a data class: never stringify secrets in diagnostics.
class TelegramCredentials(val apiId: Int, val apiHash: String) {
    fun valid() = apiId > 0 && apiHash.matches(Regex("[a-fA-F0-9]{32}"))
    override fun toString() = "TelegramCredentials(redacted)"
}

interface TelegramGateway {
    val authorization: StateFlow<AuthSnapshot>
    suspend fun submit(action: AuthProtocol.Action, value: String = "", lastName: String = "")
    suspend fun reconnect() {}
    suspend fun close()
    suspend fun ownerProfile(): TelegramOwnerProfile = throw UnsupportedOperationException("Owner profile unavailable")
    suspend fun accountId(): Long
    suspend fun phoneCountries(): List<CountryDialingInfo>
    suspend fun suggestedPhoneCountry(): String
    suspend fun activeSessions(): List<TelegramSessionInfo>
    suspend fun terminateOtherSessions()
    suspend fun uploadLimit(): Long
    suspend fun sendDocument(file: File, marker: String, progress: (Long, Long) -> Unit): RemoteDocument
    suspend fun findDocuments(marker: String): List<RemoteDocument>
    suspend fun findNewestDocuments(marker: String, limit: Int): List<RemoteDocument> = findDocuments(marker).sortedByDescending { it.messageId }.take(limit.coerceIn(1, 70))
    suspend fun deleteOwnedDocuments(references: List<RemoteDocument>, ownerMarker: String)
    suspend fun readLifecycleDocument(reference: RemoteDocument, marker: String): ByteArray = throw UnsupportedOperationException("Vault lifecycle unavailable")
    suspend fun deleteLifecycleClock(reference: RemoteDocument, marker: String): Unit = throw UnsupportedOperationException("Vault lifecycle unavailable")
    suspend fun deleteVaultNamespace(references: List<RemoteDocument>, photos: String, privateVault: String?): Unit = throw UnsupportedOperationException("Vault lifecycle unavailable")
    suspend fun deletePrivateVaultRoots(references: List<RemoteDocument>, marker: String): Unit = throw UnsupportedOperationException("Private Vault root deletion unavailable")
    suspend fun retireRecoveryRoots(references: List<RemoteDocument>, vaultId: String)
    suspend fun pruneLocalStreamCache() {}
    suspend fun readDocumentPart(reference: RemoteDocument, offset: Long, count: Int): ByteArray
    suspend fun readSpeculativeDocumentPart(reference: RemoteDocument, offset: Long, count: Int): ByteArray = readDocumentPart(reference, offset, count)

    /** Private Vault range: no retained ciphertext; validates the exact app-owned marker. */
    suspend fun readPrivateVaultPart(reference: RemoteDocument, offset: Long, count: Int, marker: String, access: () -> Unit = {}): ByteArray = throw UnsupportedOperationException("Private Vault streaming unavailable")
    suspend fun downloadDocument(reference: RemoteDocument, progress: (Long, Long) -> Unit): File
}
