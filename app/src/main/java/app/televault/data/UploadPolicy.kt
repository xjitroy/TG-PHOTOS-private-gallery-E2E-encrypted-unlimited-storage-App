package app.televault.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.televault.domain.AutomaticBackupPolicy
import app.televault.domain.PhotoDataBudget
import app.televault.domain.UploadTransport
import java.time.LocalDate

class DataPolicyBlocked(val reason: String) : Exception(reason)
data class UploadSettings(val photoLimitMb: Long = 250, val mobileVideos: Boolean = false, val cameraBackup: Boolean = false, val usedBytes: Long = 0, val dataSaver: Boolean = false, val saverSince: Long = 0, val extraTodayMb: Long = 0, val unlimited: Boolean = false, val cameraVideoMinutes: Int = 2, val photosFirst: Boolean = true) {
    val dailyLimitBytes: Long get() = AutomaticBackupPolicy.limitBytes(photoLimitMb, extraTodayMb)
}

class UploadPolicy(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("photo-upload-policy", Context.MODE_PRIVATE)
    private val lock = Any()
    private fun day() = LocalDate.now().toString()
    private fun reset() {
        val today = day()
        if (prefs.getString("day", "") != today) check(prefs.edit().putString("day", today).putLong("used", 0).putLong("extraMb", 0).putBoolean("override", false).commit()) { "Could not save today's allowance" }
        // Replace the old one-day unlimited override with one explicit 200MB addition.
        if (prefs.getBoolean("override", false)) check(prefs.edit().putLong("extraMb", AutomaticBackupPolicy.addExtra(prefs.getLong("extraMb", 0))).putBoolean("override", false).commit())
    }
    fun settings(): UploadSettings = synchronized(lock) {
        reset()
        UploadSettings(prefs.getLong("limit", 250).coerceIn(1, 100000), prefs.getBoolean("videos", false), prefs.getBoolean("camera", false), prefs.getLong("used", 0).coerceAtLeast(0), prefs.getBoolean("saver", false), prefs.getLong("saverSince", 0), prefs.getLong("extraMb", 0).coerceAtLeast(0), prefs.getBoolean("unlimited", false), prefs.getInt("cameraVideoMinutes", 2).coerceIn(0, 60), prefs.getBoolean("photosFirst", true))
    }
    fun update(limit: Long, videos: Boolean, camera: Boolean) = synchronized(lock) {
        check(prefs.edit().putLong("limit", limit.coerceIn(1, 100000)).putBoolean("videos", videos).putBoolean("camera", camera).commit()) { "Backup settings could not be saved" }
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
        check(prefs.edit().putLong("extraMb", AutomaticBackupPolicy.addExtra(prefs.getLong("extraMb", 0))).commit()) { "Extra allowance could not be saved" }
        Unit
    }
    fun saveOptions(limit: Long, minutes: Int, photosFirst: Boolean) = synchronized(lock) {
        require(limit in 1..100000 && minutes in 0..60)
        check(prefs.edit().putLong("limit", limit).putInt("cameraVideoMinutes", minutes).putBoolean("photosFirst", photosFirst).commit()) { "Backup settings could not be saved" }
    }
    fun setUnlimited(enabled: Boolean) = synchronized(lock) {
        check(prefs.edit().putBoolean("unlimited", enabled).commit()) { "Unlimited setting could not be saved" }
    }
    fun cellular(): Boolean {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return UploadTransport.mobileBudget(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), manager.isActiveNetworkMetered)
    }

    /** Reserve each attempted ciphertext send, not just successful files. Conservative on interruption. */
    fun reserve(mime: String, bytes: Long, camera: Boolean = false, manual: Boolean = false) = synchronized(lock) {
        val state = settings()
        val decision = AutomaticBackupPolicy.reserve(mime, cellular(), state.mobileVideos, state.dailyLimitBytes, state.usedBytes, bytes, state.unlimited, camera, manual)
        if (decision.block() != PhotoDataBudget.Block.NONE) throw DataPolicyBlocked(decision.block().name)
        if (decision.reservedBytes() != state.usedBytes) check(prefs.edit().putLong("used", decision.reservedBytes()).commit()) { "Usage could not be saved" }
        Unit
    }
}
