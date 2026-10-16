package app.televault.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.televault.domain.PhotoDataBudget
import app.televault.domain.UploadTransport
import java.time.LocalDate

class DataPolicyBlocked(val reason: String) : Exception(reason)
data class UploadSettings(val photoLimitMb: Long = 150, val mobileVideos: Boolean = false, val cameraBackup: Boolean = false, val usedBytes: Long = 0, val overrideToday: Boolean = false, val dataSaver: Boolean = false, val saverSince: Long = 0)

class UploadPolicy(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("photo-upload-policy", Context.MODE_PRIVATE)
    private val lock = Any()
    private fun day() = LocalDate.now().toString()
    private fun reset() {
        if (prefs.getString("day", "") != day()) prefs.edit().putString("day", day()).putLong("used", 0).putBoolean("override", false).commit()
    }
    fun settings(): UploadSettings = synchronized(lock) {
        reset()
        UploadSettings(prefs.getLong("limit", 150), prefs.getBoolean("videos", false), prefs.getBoolean("camera", false), prefs.getLong("used", 0), prefs.getBoolean("override", false), prefs.getBoolean("saver", false), prefs.getLong("saverSince", 0))
    }
    fun update(limit: Long, videos: Boolean, camera: Boolean) = synchronized(lock) {
        prefs.edit().putLong("limit", limit.coerceIn(1, 100000)).putBoolean("videos", videos).putBoolean("camera", camera).commit()
        Unit
    }
    fun setDataSaver(enabled: Boolean) = synchronized(lock) {
        val edit = prefs.edit().putBoolean("saver", enabled)
        if (enabled && !prefs.getBoolean("saver", false)) edit.putLong("saverSince", System.currentTimeMillis())
        edit.commit()
        Unit
    }
    fun overrideToday() = synchronized(lock) {
        reset()
        prefs.edit().putBoolean("override", true).commit()
        Unit
    }
    fun cellular(): Boolean {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return UploadTransport.mobileBudget(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), manager.isActiveNetworkMetered)
    }

    /** Reserve each attempted ciphertext send, not just successful files. Conservative on interruption. */
    fun reserve(mime: String, bytes: Long) = synchronized(lock) {
        val state = settings()
        val decision = PhotoDataBudget.reserve(mime, cellular(), state.mobileVideos, state.photoLimitMb * 1024 * 1024, state.usedBytes, bytes, state.overrideToday)
        if (decision.block() != PhotoDataBudget.Block.NONE) throw DataPolicyBlocked(decision.block().name)
        if (decision.reservedBytes() != state.usedBytes) prefs.edit().putLong("used", decision.reservedBytes()).commit()
        Unit
    }
}
