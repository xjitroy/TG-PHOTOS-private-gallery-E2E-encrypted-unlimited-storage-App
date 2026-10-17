package app.televault.security

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import java.security.MessageDigest
import java.util.UUID

/** Process-owned expiry survives leaving a Compose screen; no secret is written to app storage. */
internal object SensitiveClipboard {
    private const val OWNER_TOKEN = "app.televault.sensitive-clipboard-token"
    private const val LIFETIME = 60_000L
    private val handler = Handler(Looper.getMainLooper())
    private var clipboard: ClipboardManager? = null
    private var token: String? = null
    private var fingerprint: ByteArray? = null
    private var expires = 0L
    private val expiry = Runnable { clearExpired() }

    fun copy(context: Context, secret: String, title: String = "Private Vault secret") {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (clipboard == null) {
            val app = context.applicationContext as Application
            clipboard = app.getSystemService(ClipboardManager::class.java)
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    // Android 10+ may deny clipboard reads while the app is unfocused. Retry
                    // an expired, identity-checked clear when an app window regains access.
                    handler.post { clearExpired() }
                    handler.postDelayed({ clearExpired() }, 500)
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        }
        val nextToken = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(title, secret)
        clip.description.extras = PersistableBundle().apply {
            putString(OWNER_TOKEN, nextToken)
            // Android 12L and older ignore this documented forward-compatible flag.
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        checkNotNull(clipboard).setPrimaryClip(clip)
        retire()
        token = nextToken
        fingerprint = digest(secret)
        expires = SystemClock.elapsedRealtime() + LIFETIME
        handler.postDelayed(expiry, LIFETIME)
    }

    private fun clearExpired() {
        val expectedToken = token ?: return
        if (SystemClock.elapsedRealtime() < expires) return
        val manager = checkNotNull(clipboard)
        // A null result can mean Android denied a background read. Keep only the opaque
        // token and digest until resume; never blindly erase a different app's newer clip.
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return
        if (clip.description.extras?.getString(OWNER_TOKEN) != expectedToken || clip.itemCount != 1 || !clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
            retire()
            return
        }
        val value = clip.getItemAt(0).text?.toString()
        val actual = value?.let(::digest)
        val owns = actual != null && fingerprint?.let { MessageDigest.isEqual(it, actual) } == true
        actual?.fill(0)
        if (!owns) {
            retire()
            return
        }
        if (runCatching {
                if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("", ""))
            }.isSuccess
        ) {
            retire()
        }
    }

    private fun digest(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun retire() {
        handler.removeCallbacks(expiry)
        fingerprint?.fill(0)
        fingerprint = null
        token = null
        expires = 0
    }
}
