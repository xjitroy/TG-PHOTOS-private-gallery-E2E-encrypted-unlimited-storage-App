package app.televault

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.televault.domain.LockGrace
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

private val Context.preferences by preferencesDataStore(name = "appearance")

enum class AppearanceMode { SYSTEM, LIGHT, DARK }

enum class InterfaceStyle { GLASS, MATERIAL_YOU }

class PreferencesRepository(context: Context) {
    private val store = context.applicationContext.preferences
    private val telegramStorageWarning = booleanPreferencesKey("telegram_storage_warning_seen_v2")
    val telegramStorageWarningSeen = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map { it[telegramStorageWarning] ?: false }
    suspend fun markTelegramStorageWarningSeen() {
        store.edit { it[telegramStorageWarning] = true }
    }
    private val galleryIntroduction = booleanPreferencesKey("gallery_permission_introduction_seen")
    val galleryIntroductionSeen = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { it[galleryIntroduction] ?: false }
    suspend fun markGalleryIntroductionSeen() {
        store.edit { it[galleryIntroduction] = true }
    }
    private val mediaSetup = booleanPreferencesKey("first_login_media_setup_seen")
    val mediaSetupSeen = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { it[mediaSetup] ?: false }
    suspend fun markMediaSetupSeen() {
        store.edit { it[mediaSetup] = true }
    }
    private val cooldown = booleanPreferencesKey("trash_cooldown_enabled")
    val trashCooldownEnabled = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { it[cooldown] ?: true }
    suspend fun setTrashCooldownEnabled(enabled: Boolean) {
        store.edit { it[cooldown] = enabled }
    }
    private val animation = intPreferencesKey("animation_mode")
    private val launcher = stringPreferencesKey("launcher_name")
    private val launcherChanges = kotlinx.coroutines.sync.Mutex()
    private val appContext = context.applicationContext
    val animationMode = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map { app.televault.domain.MotionPolicy.mode(it[animation] ?: 0) }
    suspend fun setAnimationMode(mode: Int) {
        store.edit { it[animation] = app.televault.domain.MotionPolicy.mode(mode) }
    }
    val launcherName = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map { values -> LauncherName.entries.firstOrNull { it.name == values[launcher] } ?: LauncherName.DEFAULT }
    suspend fun setLauncherName(name: LauncherName) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        launcherChanges.withLock {
            LauncherIdentity.apply(appContext, name)
            store.edit { it[launcher] = name.name }
        }
    }
    private val mosaicKey = booleanPreferencesKey("gallery_mosaic_layout")
    val mosaicLayout = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map { it[mosaicKey] ?: true }
    suspend fun setMosaicLayout(enabled: Boolean) {
        store.edit { it[mosaicKey] = enabled }
    }
    private val cleanMediaKey = booleanPreferencesKey("clean_media_view")
    private val hiddenMediaKey = stringSetPreferencesKey("hidden_media_categories")
    val cleanMedia = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map { it[cleanMediaKey] ?: false }
    val hiddenMediaCategories = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map { values -> values[hiddenMediaKey].orEmpty().filter { app.televault.domain.MediaCategories.category(it) != app.televault.domain.MediaCategories.Category.ALL }.toSet() }
    suspend fun setCleanMedia(enabled: Boolean) {
        store.edit { it[cleanMediaKey] = enabled }
    }
    suspend fun setMediaCategoryHidden(id: String, hidden: Boolean) {
        require(app.televault.domain.MediaCategories.category(id) != app.televault.domain.MediaCategories.Category.ALL)
        store.edit { values -> values[hiddenMediaKey] = if (hidden) values[hiddenMediaKey].orEmpty() + id else values[hiddenMediaKey].orEmpty() - id }
    }
    suspend fun resetMediaFilters() {
        store.edit {
            it[cleanMediaKey] = false
            it[hiddenMediaKey] = emptySet()
        }
    }
    private val hiddenGallery = booleanPreferencesKey("gallery_show_hidden")
    private val guidance = booleanPreferencesKey("usage_guidance_enabled")
    val showHiddenGallery = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map { it[hiddenGallery] ?: false }
    val guidanceEnabled = store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }.map { it[guidance] ?: true }
    suspend fun setShowHiddenGallery(enabled: Boolean) {
        store.edit { it[hiddenGallery] = enabled }
    }
    suspend fun setGuidanceEnabled(enabled: Boolean) {
        store.edit { it[guidance] = enabled }
    }
    suspend fun claimGuide(scope: String, topic: String): Boolean {
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val count = intPreferencesKey("guide_${id}_$topic")
        var claimed = false
        store.edit { values ->
            if ((values[guidance] ?: true) && (values[count] ?: 0) < 3) {
                values[count] = (values[count] ?: 0) + 1
                claimed = true
            }
        }
        return claimed
    }
    private fun fileSortKey(cloud: Boolean) = stringPreferencesKey(if (cloud) "files_cloud_sort" else "files_device_sort")
    private val fileSortModes = setOf("Newest", "Oldest", "Name", "Name descending", "Size", "Smallest", "Type")
    fun fileSort(cloud: Boolean) = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values -> values[fileSortKey(cloud)]?.takeIf { it in fileSortModes } ?: "Newest" }
    suspend fun setFileSort(cloud: Boolean, mode: String) {
        require(mode in fileSortModes)
        store.edit { it[fileSortKey(cloud)] = mode }
    }
    private val dynamic = booleanPreferencesKey("dynamic_color")
    private val appearance = stringPreferencesKey("appearance_mode")
    private val interfaceStyleKey = stringPreferencesKey("interface_style")
    val interfaceStyle = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values -> InterfaceStyle.entries.firstOrNull { it.name == values[interfaceStyleKey] } ?: InterfaceStyle.GLASS }
    suspend fun setInterfaceStyle(style: InterfaceStyle) {
        store.edit { it[interfaceStyleKey] = style.name }
    }
    private val grace = intPreferencesKey("lock_grace_minutes")
    val lockGraceMinutes = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { LockGrace.minutes(it[grace] ?: 2) }
    suspend fun setLockGraceMinutes(minutes: Int) {
        store.edit { it[grace] = LockGrace.minutes(minutes) }
    }
    val appearanceMode = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values -> AppearanceMode.entries.firstOrNull { it.name == values[appearance] } ?: AppearanceMode.SYSTEM }
    suspend fun setAppearanceMode(mode: AppearanceMode) {
        store.edit { it[appearance] = mode.name }
    }
    val dynamicColor = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { it[dynamic] ?: true }
    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[dynamic] = enabled }
    }
}
