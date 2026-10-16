package app.televault

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Only launcher aliases change. Package, app header, vault namespace and credentials do not. */
enum class LauncherName(val title: String, val alias: String) {
    DEFAULT("TG Photos", "DefaultLauncher"),
    GALLERY("Gallery TG", "GalleryLauncher"),
    PHOTOS("Photos TG", "PhotosLauncher"),
    ALBUM("Album TG", "AlbumLauncher"),
    FILES("Files & Drive", "FilesLauncher")
}
internal object LauncherIdentity {
    fun apply(context: Context, selected: LauncherName) {
        val pm = context.packageManager
        val entries = LauncherName.entries.map { it to ComponentName(context, "${context.packageName}.${it.alias}") }
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(entries.map { (name, component) -> PackageManager.ComponentEnabledSetting(component, if (name == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) })
        } else {
            // Enable the selected entry first; never leave the app without a launcher during switching.
            entries.sortedBy { if (it.first == selected) 0 else 1 }.forEach { (name, component) ->
                pm.setComponentEnabledSetting(component, if (name == selected) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
