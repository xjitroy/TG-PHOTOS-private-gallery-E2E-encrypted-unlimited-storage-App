package app.televault.data

import android.net.Uri
import android.os.SystemClock
import java.util.UUID

/** Explicit share consent creates a short-lived in-memory grant, never a plaintext disk copy. */
internal object PrivateMediaGrants {
    data class Grant(val source: Uri?, val cloudId: String?, val name: String, val mime: String, val size: Long, val epoch: Long, val expires: Long)
    private val grants = java.util.concurrent.ConcurrentHashMap<String, Grant>()
    fun create(packageName: String, source: Uri?, cloudId: String?, name: String, mime: String, size: Long, epoch: Long): Uri {
        grants.entries.removeIf { it.value.expires <= SystemClock.elapsedRealtime() }
        val token = UUID.randomUUID().toString()
        grants[token] = Grant(source, cloudId, name, mime, size, epoch, SystemClock.elapsedRealtime() + 3600000)
        return Uri.parse("content://$packageName.private-media/share/$token")
    }
    fun get(token: String): Grant {
        val grant = checkNotNull(grants[token]) { "Share expired" }
        if (grant.expires <= SystemClock.elapsedRealtime()) {
            grants.remove(token)
            error("Share expired")
        }
        return grant
    }
    fun revoke(token: String) {
        grants.remove(token)
    }
    fun clear() {
        grants.clear()
    }
}
