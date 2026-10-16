package app.televault.data

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class LocalIndexEnvelopeTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun snapshotRoundTripAndFreshNonce() {
        val key = key()
        val files = "DCIM/Camera/photo.jpg|Download/private.pdf".toByteArray()
        val first = LocalIndexEnvelope.encrypt(files, key)
        val second = LocalIndexEnvelope.encrypt(files, key)
        assertArrayEquals(files, LocalIndexEnvelope.decrypt(first, key))
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("private.pdf"))
    }

    @Test fun wrongKeyAndModifiedCiphertextAreRejected() {
        val key = key()
        val raw = LocalIndexEnvelope.encrypt("device metadata".toByteArray(), key)
        for (candidate in listOf(raw to key(), raw.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to key)) {
            try {
                LocalIndexEnvelope.decrypt(candidate.first, candidate.second)
                fail("Unverified metadata must never be displayed")
            } catch (_: AEADBadTagException) { }
        }
    }
}
