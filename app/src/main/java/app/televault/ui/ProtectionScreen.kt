package app.televault.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.televault.data.LocalMediaRepository
import app.televault.security.DeviceProtection

/** No vault/content key, cloud viewer, transfer callback or cloud cache is available here. */
@Composable internal fun ProtectionScreen(reason: DeviceProtection.Reason) {
    val context = LocalContext.current
    val media = remember { LocalMediaRepository(context.applicationContext) }
    DisposableEffect(media) { onDispose { media.close() } }
    var files by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var browsing by remember { mutableStateOf(false) }
    val root = reason == DeviceProtection.Reason.ROOT
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open device file")) }
    }
    LaunchedEffect(browsing, refresh) {
        if (browsing && !root) {
            media.refresh()
            media.changes.collect { items -> files = items.filterNot { it.trashed }.map { MediaEntry(it.uri.toString(), it.name, it.mime, it.size, it.date, local = it) } }
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Icon(Icons.Outlined.Security, null, Modifier.padding(start = 20.dp, top = 16.dp).size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                when (reason) {
                    DeviceProtection.Reason.VPN -> label("VPN detected · cloud paused", "VPN পাওয়া গেছে · ক্লাউড বন্ধ")
                    DeviceProtection.Reason.ROOT -> label("Root evidence detected", "রুটের প্রমাণ পাওয়া গেছে")
                    else -> label("Unsafe runtime detected", "অনিরাপদ হস্তক্ষেপ পাওয়া গেছে")
                },
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge
            )
            Text(if (root) label("This device cannot open TG Photos. Existing files were not deleted. Use an unrooted device with your recovery password.", "এই ফোনে TG Photos খোলা যাবে না। ফাইল মুছে যায়নি। রুট ছাড়া ফোনে রিকভারি পাসওয়ার্ড ব্যবহার করুন।") else label("Cloud connections are stopped and the vault is locked. No new cloud transfers or vault-key operations are allowed. Local device browsing remains available.", "ক্লাউড সংযোগ বন্ধ, ভল্ট লক। নতুন ক্লাউড ট্রান্সফার বা ভল্ট কী ব্যবহার হবে না। ফোনের লোকাল ফাইল দেখতে পারবেন।"), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton({ runCatching { context.startActivity(Intent(if (reason == DeviceProtection.Reason.VPN) Settings.ACTION_VPN_SETTINGS else Settings.ACTION_SECURITY_SETTINGS)) } }) { Text(label("Security settings", "সিকিউরিটি সেটিংস")) }
                if (!root) TextButton({ browsing = !browsing }) { Text(label("Browse device files", "ফোনের ফাইল দেখুন")) }
            }
            if (browsing && !root) {
                FileLibrary(files, false, {}, selected, { selected = it }, { item, _ ->
                    if (item.local?.uri?.scheme == "content") {
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(item.local.uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open device file")) }
                    } else {
                        openFile.launch(arrayOf(item.mime))
                    }
                }, null, search, { search = it }, cloudAvailable = false, access = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching { settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) }
                    } else {
                        permission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                    }
                }, refresh = { refresh++ }, modifier = Modifier.weight(1f))
            } else if (!root) {
                Text(label("This is a local safeguard, not a complete malware scan. Ordinary installed apps do not trigger a block. Cloud access resumes after the detected condition clears and you unlock again.", "এটি লোকাল সুরক্ষা, সম্পূর্ণ ভাইরাস স্ক্যান নয়। সাধারণ ইনস্টল করা অ্যাপের জন্য ব্লক হবে না। সমস্যা সরে গেলে আবার আনলক করে ক্লাউড ব্যবহার করুন।"), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
