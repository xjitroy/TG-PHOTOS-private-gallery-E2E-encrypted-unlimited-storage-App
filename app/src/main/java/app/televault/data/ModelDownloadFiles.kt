package app.televault.data

import java.io.File
import java.security.MessageDigest

/** Recover a completed verified download locally instead of requesting an impossible EOF Range. */
internal fun promoteVerifiedModelPart(part: File, target: File, bytes: Long, sha256: String): Boolean {
    require(part.absolutePath == target.absolutePath + ".partial" && bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    if (!part.isFile || part.length() != bytes) return false
    val hash = MessageDigest.getInstance("SHA-256")
    part.inputStream().use { input ->
        val buffer = ByteArray(131072)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            hash.update(buffer, 0, n)
        }
    }
    if (hash.digest().joinToString("") { "%02x".format(it) } != sha256) {
        check(part.delete()) { "Corrupt model partial could not be removed" }
        return false
    }
    check(part.renameTo(target)) { "Verified model could not be installed" }
    return true
}
