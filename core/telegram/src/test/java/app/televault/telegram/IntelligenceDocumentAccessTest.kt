package app.televault.telegram

import org.junit.Assert.*
import org.junit.Test

class IntelligenceDocumentAccessTest {
    private val vault = "11111111-1111-4111-8111-111111111111"
    private val objectId = "22222222-2222-4222-8222-222222222222"
    private fun reference(marker: String) = RemoteDocument(15, 50, 128, 0, marker)
    private fun metadata(ref: RemoteDocument) = IntelligenceDocumentMetadata(ref.chatId, ref.messageId, true, ref.marker, ref.bytes, 42, false, false)

    @Test fun allThreeEncryptedMetadataNamespacesCanRestore() {
        for (marker in listOf("TGDRIVE_V1_AI_PRIVATE:$vault:$objectId", "TGDRIVE_V1_PEOPLE:$vault:$objectId:$objectId", "TGDRIVE_V1_ANALYSIS:$vault:${"a".repeat(64)}")) {
            val ref = reference(marker)
            assertEquals(42, IntelligenceDocumentAccess.validate(ref, metadata(ref)))
        }
    }

    @Test fun mediaAndMalformedMetadataCannotUseThisPath() {
        for (marker in listOf("TGDRIVE_V1_DATA:$vault:$objectId", "TGDRIVE_V1_ROOT:$vault", "TGDRIVE_V1_AI_PRIVATE:$vault:no", "TGDRIVE_V1_ANALYSIS:$vault:hash", "TGDRIVE_V1_PEOPLE:$vault:$objectId", "TGDRIVE_V1_AI_PRIVATE:$vault:$objectId:extra")) {
            val ref = reference(marker)
            assertThrows(IllegalArgumentException::class.java) { IntelligenceDocumentAccess.validate(ref, metadata(ref)) }
        }
    }

    @Test fun changedCaptionSizeScopeAndPendingOrForwardedMessagesAreRejected() {
        val ref = reference("TGDRIVE_V1_AI_PRIVATE:$vault:$objectId")
        val original = metadata(ref)
        for (changed in listOf(original.copy(caption = ref.marker + "x"), original.copy(bytes = 129), original.copy(chat = 16), original.copy(message = 51), original.copy(pending = true), original.copy(forwarded = true), original.copy(document = false), original.copy(file = 0))) {
            assertThrows(IllegalStateException::class.java) { IntelligenceDocumentAccess.validate(ref, changed) }
        }
    }

    @Test fun unboundedRecordsAreRejectedBeforeDownload() {
        val ref = reference("TGDRIVE_V1_AI_PRIVATE:$vault:$objectId")
        for (size in listOf(0L, 27L, 2L * 1024 * 1024 + 1)) {
            val changed = ref.copy(bytes = size)
            assertThrows(IllegalArgumentException::class.java) { IntelligenceDocumentAccess.validate(changed, metadata(changed)) }
        }
    }
}
