package app.televault.telegram

import app.televault.domain.ForgottenVaultPolicy

/** Validate fresh server metadata before caching a native file ID. Cipher authentication is separate. */
internal data class IntelligenceDocumentMetadata(val chat: Long, val message: Long, val document: Boolean, val caption: String, val bytes: Long, val file: Int, val pending: Boolean, val forwarded: Boolean)

internal object IntelligenceDocumentAccess {
    fun validate(reference: RemoteDocument, message: IntelligenceDocumentMetadata): Int {
        val parts = reference.marker.split(':')
        require(parts.size in 3..4 && ForgottenVaultPolicy.validId(parts[1]))
        val allowed = when (parts[0]) {
            "TGDRIVE_V1_AI_PRIVATE" -> parts.size == 3 && ForgottenVaultPolicy.validId(parts[2])
            "TGDRIVE_V1_PEOPLE" -> parts.size == 4 && ForgottenVaultPolicy.validId(parts[2]) && ForgottenVaultPolicy.validId(parts[3])
            "TGDRIVE_V1_ANALYSIS" -> parts.size == 3 && parts[2].matches(Regex("[0-9a-f]{64}"))
            else -> false
        }
        require(allowed && reference.bytes in 28..2L * 1024 * 1024)
        check(message.message == reference.messageId && message.chat == reference.chatId)
        check(!message.pending && !message.forwarded && message.document)
        check(message.caption == reference.marker && message.bytes == reference.bytes)
        return message.file.also { check(it > 0) }
    }
}
