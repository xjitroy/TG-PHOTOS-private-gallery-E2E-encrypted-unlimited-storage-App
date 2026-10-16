package app.televault.telegram

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeCipherRangesTest {
    @Test fun removedUploadStagingDoesNotMasqueradeAsDownloadedServerBytes() = runBlocking {
        val uploaded = File.createTempFile("cipher-upload", ".bin")
        val downloaded = File.createTempFile("cipher-download", ".bin")
        val server = ByteArray(4096) { (it * 31).toByte() }
        try {
            uploaded.writeBytes(server)
            assertTrue(uploaded.delete())
            var nativePath = uploaded // TDLib retains the now-deleted inputFileLocal path.
            var downloads = 0
            val restored = NativeCipherRanges.read(57, 1024, { 4096 }, { nativePath }, {
                downloads++
                downloaded.writeBytes(server)
                nativePath = downloaded
            }, {})
            assertEquals(1, downloads)
            assertArrayEquals(server.copyOfRange(57, 1081), restored)
            assertFalse(uploaded.exists())
        } finally {
            uploaded.delete()
            downloaded.delete()
        }
    }

    @Test fun anActuallyReadableCipherRangeUsesTheExistingCopy() = runBlocking {
        val local = File.createTempFile("cipher-range", ".bin")
        try {
            val cipher = ByteArray(512) { it.toByte() }
            local.writeBytes(cipher)
            val bytes = NativeCipherRanges.read(20, 100, { 492 }, { local }, { fail("Do not re-download a valid range") }, {})
            assertArrayEquals(cipher.copyOfRange(20, 120), bytes)
        } finally {
            local.delete()
        }
    }

    @Test fun aShortPhysicalCopyWithAStaleFullPrefixNeedsANewRange() = runBlocking {
        val local = File.createTempFile("cipher-short", ".bin")
        try {
            local.writeBytes(byteArrayOf(1, 2))
            var downloads = 0
            val bytes = NativeCipherRanges.read(10, 30, { 100 }, { local }, {
                downloads++
                local.writeBytes(ByteArray(100) { 7 })
            }, {})
            assertEquals(1, downloads)
            assertArrayEquals(ByteArray(30) { 7 }, bytes)
        } finally {
            local.delete()
        }
    }

    @Test fun revokedAccessDuringFreshDownloadCannotReturnAByteArray() = runBlocking {
        val local = File.createTempFile("cipher-revoked", ".bin")
        try {
            var allowed = true
            try {
                NativeCipherRanges.read(0, 16, { 0 }, { local }, {
                    local.writeBytes(ByteArray(16) { 9 })
                    allowed = false
                }, { check(allowed) })
                fail("A revoked session must fail closed")
            } catch (_: IllegalStateException) { }
        } finally {
            local.delete()
        }
    }

    @Test fun accessIsCheckedAgainAfterThePhysicalRead() = runBlocking {
        val local = File.createTempFile("cipher-lock", ".bin")
        try {
            local.writeBytes(ByteArray(16) { 9 })
            var checks = 0
            try {
                NativeCipherRanges.read(0, 16, { 16 }, { local }, { fail("Valid cache") }, {
                    checks++
                    check(checks < 3)
                })
                fail("Bytes cannot leave a session locked during the read")
            } catch (_: IllegalStateException) { }
            assertEquals(3, checks)
        } finally {
            local.delete()
        }
    }
}
