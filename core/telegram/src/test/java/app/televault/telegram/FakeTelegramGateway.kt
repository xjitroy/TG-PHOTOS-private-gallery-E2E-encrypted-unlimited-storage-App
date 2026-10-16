package app.televault.telegram

import app.televault.domain.AuthProtocol
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Test source set only. Never included in the production app. */
internal class FakeTelegramGateway : TelegramGateway {
    private val state = MutableStateFlow(AuthSnapshot(AuthProtocol.State.PHONE))
    override val authorization = state.asStateFlow()
    var acceptedRequests = 0
        private set
    fun serverEvent(stage: AuthProtocol.State) {
        state.value = AuthSnapshot(stage)
    }
    override suspend fun submit(action: AuthProtocol.Action, value: String, lastName: String) {
        if (AuthProtocol.permits(state.value.stage, action)) acceptedRequests++
        // Do not retain any input secrets or fabricate a Ready update.
    }
    override suspend fun close() {
        serverEvent(AuthProtocol.State.CLOSED)
    }
    override suspend fun accountId() = 42L
    override suspend fun phoneCountries(): List<CountryDialingInfo> = throw UnsupportedOperationException("No country network in test")
    override suspend fun suggestedPhoneCountry(): String = throw UnsupportedOperationException("No IP network in test")
    override suspend fun activeSessions(): List<TelegramSessionInfo> = throw UnsupportedOperationException("No real sessions in test")
    override suspend fun terminateOtherSessions(): Unit = throw UnsupportedOperationException("No account session changes in test")
    override suspend fun uploadLimit() = 2L * 1024 * 1024 * 1024
    override suspend fun sendDocument(file: File, marker: String, progress: (Long, Long) -> Unit) = error("Use a storage fixture")
    override suspend fun findDocuments(marker: String) = emptyList<RemoteDocument>()
    override suspend fun deleteOwnedDocuments(references: List<RemoteDocument>, ownerMarker: String): Unit = throw UnsupportedOperationException("Test operation not configured")
    override suspend fun retireRecoveryRoots(references: List<RemoteDocument>, vaultId: String): Unit = throw UnsupportedOperationException("Test operation not configured")
    override suspend fun readDocumentPart(reference: RemoteDocument, offset: Long, count: Int): ByteArray = error("Use a range fixture")
    override suspend fun downloadDocument(reference: RemoteDocument, progress: (Long, Long) -> Unit): File = error("Use a storage fixture")
}
