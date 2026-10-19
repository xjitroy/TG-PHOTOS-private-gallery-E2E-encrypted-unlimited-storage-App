package app.televault.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.televault.DriveViewModel
import app.televault.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun words(english: String, bengali: String): String = if (LocalConfiguration.current.locales[0].language == "bn") bengali else english

private enum class DriveTab(val icon: ImageVector) { PHOTOS(Icons.Outlined.PhotoLibrary), ALBUMS(Icons.Outlined.Collections), FILES(Icons.Outlined.Folder), ACTIVITY(Icons.Outlined.SwapVert), SETTINGS(Icons.Outlined.Settings) }

@Composable
private fun tabTitle(tab: DriveTab) = when (tab) {
    DriveTab.PHOTOS -> words("Photos", "ফটো")
    DriveTab.ALBUMS -> words("Albums", "অ্যালবাম")
    DriveTab.FILES -> words("Files", "ফাইল")
    DriveTab.ACTIVITY -> words("Transfers", "ট্রান্সফার")
    DriveTab.SETTINGS -> words("Settings", "সেটিংস")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(model: DriveViewModel, dynamicColor: Boolean, changeDynamicColor: suspend (Boolean) -> Unit, logout: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    if (state.stage != VaultStage.READY) {
        VaultForm(state, model)
        return
    }
    var tab by rememberSaveable { mutableStateOf(DriveTab.PHOTOS) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var album by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var selectedDownloads by remember { mutableStateOf(setOf<String>()) }
    var exportWarning by remember { mutableStateOf(false) }
    var backupWarning by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(true) }
    var logoutWarning by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<DriveFile?>(null) }
    var preview by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isNotEmpty()) {
            notificationPermission()
            model.upload(uris)
            tab = DriveTab.ACTIVITY
        }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            notificationPermission()
            model.download(selectedDownloads, uri)
            selectedDownloads = emptySet()
            selected = emptySet()
            tab = DriveTab.ACTIVITY
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            notificationPermission()
            model.backup(uri, wifiOnly)
            tab = DriveTab.SETTINGS
        }
    }
    fun requestDownload(ids: Set<String>) {
        selectedDownloads = ids
        exportWarning = true
    }
    val visible = state.files.filter { item ->
        (tab != DriveTab.PHOTOS || item.mime.startsWith("image/") || item.mime.startsWith("video/")) &&
            (album == null || item.album == album) && item.name.contains(search, true) &&
            (filter == "All" || filter == "Photos" && item.mime.startsWith("image/") || filter == "Videos" && item.mime.startsWith("video/"))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 600.dp
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text("TG Photos", fontWeight = FontWeight.Bold)
                                HeaderActivity(model)
                            }
                        },
                        actions = {
                            IconButton({ model.sync() }, enabled = !state.busy) { Icon(Icons.Outlined.Sync, words("Sync", "সিঙ্ক")) }
                            IconButton({ model.lock() }) { Icon(Icons.Outlined.Lock, words("Lock vault", "ভল্ট লক করুন")) }
                        }
                    )
                    if (selected.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ selected = emptySet() }) { Icon(Icons.Outlined.Close, words("Clear selection", "নির্বাচন বাতিল")) }
                            Text("${selected.size} " + words("selected", "নির্বাচিত"), Modifier.weight(1f))
                            TextButton({ selected = visible.map { it.id }.toSet() }) { Text(words("All", "সব")) }
                            IconButton({ requestDownload(selected) }) { Icon(Icons.Outlined.Download, words("Download selected", "নির্বাচিত ফাইল ডাউনলোড")) }
                        }
                    }
                }
            },
            bottomBar = {
                if (!expanded) {
                    NavigationBar {
                        DriveTab.entries.forEach { item ->
                            NavigationBarItem(selected = tab == item, onClick = {
                                tab = item
                                album = null
                                selected = emptySet()
                            }, icon = { Icon(item.icon, null) }, label = { Text(tabTitle(item)) })
                        }
                    }
                }
            },
            floatingActionButton = {
                if (tab in setOf(DriveTab.PHOTOS, DriveTab.FILES, DriveTab.ALBUMS) && selected.isEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { upload.launch(arrayOf("*/*")) },
                        icon = { Icon(Icons.Outlined.Add, null) },
                        text = { Text(words("Upload", "আপলোড")) }
                    )
                }
            }
        ) { padding ->
            Row(Modifier.padding(padding).consumeWindowInsets(padding)) {
                if (expanded) {
                    NavigationRail {
                        DriveTab.entries.forEach { item ->
                            NavigationRailItem(selected = tab == item, onClick = {
                                tab = item
                                album = null
                                selected = emptySet()
                            }, icon = { Icon(item.icon, null) }, label = { Text(tabTitle(item)) })
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (state.notice.isNotBlank()) Text(words(state.notice, "কাজটি সম্পন্ন হয়নি। সংযোগ, ফাইলের অনুমতি ও খালি জায়গা যাচাই করে আবার চেষ্টা করুন।"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    when (tab) {
                        DriveTab.PHOTOS, DriveTab.FILES -> {
                            OutlinedTextField(
                                search,
                                { search = it },
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                placeholder = { Text(words("Search your encrypted library", "আপনার এনক্রিপ্টেড লাইব্রেরিতে খুঁজুন")) },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp)
                            )
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("All", "Photos", "Videos").forEach { item ->
                                    FilterChip(selected = filter == item, onClick = { filter = item }, label = {
                                        Text(
                                            when (item) {
                                                "Photos" -> words("Photos", "ফটো")
                                                "Videos" -> words("Videos", "ভিডিও")
                                                else -> words("All", "সব")
                                            }
                                        )
                                    })
                                }
                            }
                            if (album != null) TextButton({ album = null }) { Text("‹ " + words("All albums", "সব অ্যালবাম")) }
                            if (visible.isEmpty()) {
                                EmptyLibrary { upload.launch(arrayOf("*/*")) }
                            } else if (tab == DriveTab.PHOTOS) {
                                val months = visible.groupBy { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(it.date)) }
                                LazyVerticalGrid(columns = GridCells.Adaptive(112.dp), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    months.forEach { (month, files) ->
                                        item(key = "month:$month", span = { GridItemSpan(maxLineSpan) }) { Text(month, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium) }
                                        items(files, key = { it.id }) { file ->
                                            GalleryTile(
                                                file,
                                                file.id in selected,
                                                model,
                                                click = {
                                                    if (selected.isNotEmpty()) {
                                                        selected = if (file.id in selected) selected - file.id else selected + file.id
                                                    } else {
                                                        opening = file
                                                        model.preview(file.id) {
                                                            preview = it
                                                            if (it == null) opening = null
                                                        }
                                                    }
                                                },
                                                hold = { selected = selected + file.id }
                                            )
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(contentPadding = PaddingValues(bottom = 100.dp)) {
                                    items(visible, key = { it.id }) { file ->
                                        ListItem(
                                            headlineContent = { Text(file.name, maxLines = 2) },
                                            supportingContent = { Text("${file.album} · ${byteSize(file.size)}") },
                                            leadingContent = { Icon(if (file.id in selected) Icons.Outlined.CheckCircle else Icons.Outlined.InsertDriveFile, null) },
                                            trailingContent = { IconButton({ requestDownload(setOf(file.id)) }) { Icon(Icons.Outlined.Download, words("Download", "ডাউনলোড")) } },
                                            modifier = Modifier.combinedClickable(onClick = {
                                                if (selected.isNotEmpty()) {
                                                    selected = if (file.id in selected) selected - file.id else selected + file.id
                                                } else {
                                                    opening = file
                                                    model.preview(file.id) {
                                                        preview = it
                                                        if (it == null) opening = null
                                                    }
                                                }
                                            }, onLongClick = { selected += file.id })
                                        )
                                    }
                                }
                            }
                        }
                        DriveTab.ALBUMS -> {
                            Text(words("Collections", "সংগ্রহ"), Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium)
                            val albums = state.files.groupBy { it.album }
                            if (albums.isEmpty()) EmptyLibrary { upload.launch(arrayOf("*/*")) }
                            LazyVerticalGrid(GridCells.Adaptive(156.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                items(albums.keys.toList(), key = { it }) { name ->
                                    OutlinedCard(onClick = {
                                        album = name
                                        tab = DriveTab.FILES
                                    }, shape = RoundedCornerShape(24.dp)) {
                                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Icon(Icons.Outlined.Collections, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                            Text(name, style = MaterialTheme.typography.titleMedium)
                                            Text("${albums[name]?.size} " + words("files", "ফাইল"), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                        DriveTab.ACTIVITY -> {
                            Text(words("Transfer activity", "ট্রান্সফার কার্যক্রম"), Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium)
                            if (state.transfers.isEmpty()) Text(words("Your uploads and downloads appear here.", "আপলোড ও ডাউনলোড এখানে দেখা যাবে।"), Modifier.padding(24.dp))
                            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.transfers, key = { it.id }) { transfer ->
                                    OutlinedCard(shape = RoundedCornerShape(20.dp)) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(if (transfer.download) Icons.Outlined.Download else Icons.Outlined.Upload, null)
                                                Text(transfer.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2)
                                                if (transfer.phase != "COMPLETE" && transfer.phase != "CANCELLED") IconButton({ model.cancel(transfer.id) }) { Icon(Icons.Outlined.Close, words("Cancel", "বাতিল")) }
                                            }
                                            Text(transferPhase(transfer.phase), style = MaterialTheme.typography.bodySmall)
                                            if (transfer.total > 0) LinearProgressIndicator(progress = { (transfer.progress.toFloat() / transfer.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                            Text("${byteSize(transfer.progress)} / ${byteSize(transfer.total)}", style = MaterialTheme.typography.labelSmall)
                                            if (transfer.phase in setOf("FAILED", "INTERRUPTED", "CANCELLED")) TextButton({ model.retry(transfer.id) }) { Text(words("Retry", "আবার চেষ্টা")) }
                                        }
                                    }
                                }
                            }
                        }
                        DriveTab.SETTINGS -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text(words("Backup & privacy", "ব্যাকআপ ও গোপনীয়তা"), style = MaterialTheme.typography.headlineMedium)
                            OutlinedCard(shape = RoundedCornerShape(24.dp)) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Icon(Icons.Outlined.Backup, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(words("Automatic folder backup", "স্বয়ংক্রিয় ফোল্ডার ব্যাকআপ"), style = MaterialTheme.typography.titleMedium)
                                    Text(words("Choose a folder. New and changed items are encrypted and queued periodically. Android controls the timing; scans run while your phone is unlocked.", "একটি ফোল্ডার বাছুন। নতুন ও পরিবর্তিত ফাইল এনক্রিপ্ট করে নির্দিষ্ট বিরতিতে আপলোডের তালিকায় রাখা হবে। সময় অ্যান্ড্রয়েড নিয়ন্ত্রণ করে; ফোন আনলক থাকলে কাজ চলবে।"))
                                    Button({ backupWarning = true }) { Text(words("Choose backup folder", "ব্যাকআপ ফোল্ডার বাছুন")) }
                                }
                            }
                            state.backups.forEach { folder -> ListItem(headlineContent = { Text(folder.name) }, supportingContent = { Text(if (folder.wifiOnly) words("Wi-Fi only · Automatic", "শুধু Wi-Fi · স্বয়ংক্রিয়") else words("All networks · Automatic", "সব নেটওয়ার্ক · স্বয়ংক্রিয়")) }, trailingContent = { TextButton({ model.stopBackup(folder.id) }) { Text(words("Stop", "বন্ধ")) } }) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(words("Use system colors", "সিস্টেমের রং ব্যবহার"), Modifier.weight(1f))
                                Switch(dynamicColor, { enabled -> scope.launch { changeDynamicColor(enabled) } })
                            }
                            Text(words("English is the default app language. Vault locks after 2 minutes in the background. Recovery passphrase is required on a new phone.", "ভাষা অ্যান্ড্রয়েড সেটিংস অনুসরণ করে। ব্যাকগ্রাউন্ডে ২ মিনিট পর ভল্ট লক হবে। নতুন ফোনে রিকভারি পাসফ্রেজ লাগবে।"))
                            Text(words("Experimental APK: keep originals and record your recovery passphrase offline. Telegram limits and availability apply. No permanent storage guarantee.", "পরীক্ষামূলক APK: মূল ফাইল ও রিকভারি পাসফ্রেজ অফলাইনে রাখুন। টেলিগ্রামের সীমা ও প্রাপ্যতা প্রযোজ্য। স্থায়ী সংরক্ষণের নিশ্চয়তা নেই।"), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton({ logoutWarning = true }) { Text(words("Log out of Telegram", "টেলিগ্রাম লগআউট")) }
                        }
                    }
                }
            }
        }
    }
    if (exportWarning) {
        AlertDialog(
            onDismissRequest = { exportWarning = false },
            title = { Text(words("Export selected files?", "নির্বাচিত ফাইল এক্সপোর্ট করবেন?")) },
            text = { Text(words("Downloaded originals will be decrypted in the folder you choose. Other apps with access to that folder may read them.", "ডাউনলোড করা মূল ফাইল নির্বাচিত ফোল্ডারে ডিক্রিপ্ট করা থাকবে। সেই ফোল্ডারে অ্যাক্সেস থাকা অন্য অ্যাপ সেগুলো পড়তে পারবে।")) },
            confirmButton = {
                TextButton({
                    exportWarning = false
                    destination.launch(null)
                }) { Text(words("Choose folder", "ফোল্ডার বাছুন")) }
            },
            dismissButton = { TextButton({ exportWarning = false }) { Text(words("Cancel", "বাতিল")) } }
        )
    }
    if (backupWarning) {
        AlertDialog(
            onDismissRequest = { backupWarning = false },
            title = { Text(words("Enable folder backup", "ফোল্ডার ব্যাকআপ চালু")) },
            text = {
                Column {
                    Text(words("Only the folder you choose is scanned. Backups use your device-protected vault key while the phone is unlocked, even when the app is closed. Existing originals are never deleted.", "শুধু নির্বাচিত ফোল্ডার স্ক্যান হবে। ফোন আনলক থাকলে অ্যাপ বন্ধ থাকলেও ডিভাইসে সুরক্ষিত ভল্ট কী ব্যবহার করে ব্যাকআপ চলবে। মূল ফাইল মুছে ফেলা হবে না।"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(wifiOnly, { wifiOnly = it })
                        Text(words("Wi-Fi only", "শুধু Wi-Fi"))
                    }
                }
            },
            confirmButton = {
                TextButton({
                    backupWarning = false
                    backupPicker.launch(null)
                }) { Text(words("Choose folder", "ফোল্ডার বাছুন")) }
            },
            dismissButton = { TextButton({ backupWarning = false }) { Text(words("Cancel", "বাতিল")) } }
        )
    }
    if (logoutWarning) {
        AlertDialog(onDismissRequest = { logoutWarning = false }, title = { Text(words("Log out?", "লগআউট করবেন?")) }, text = { Text(words("Keep your recovery passphrase. Telegram documents are not deleted.", "রিকভারি পাসফ্রেজ রাখুন। টেলিগ্রামের ফাইল মুছে ফেলা হবে না।")) }, confirmButton = {
            TextButton({
                model.lock()
                logoutWarning = false
                logout()
            }) { Text(words("Log out", "লগআউট")) }
        }, dismissButton = { TextButton({ logoutWarning = false }) { Text(words("Cancel", "বাতিল")) } })
    }
    opening?.let { item ->
        PreviewDialog(item, preview, close = {
            model.closePreview()
            preview = null
            opening = null
        }, download = {
            model.closePreview()
            preview = null
            opening = null
            requestDownload(setOf(item.id))
        })
    }
}

@Composable
internal fun VaultForm(state: DriveSnapshot, model: DriveViewModel, unlocked: ((Boolean) -> Unit)? = null) {
    val passwordContext = LocalContext.current
    val passwordScope = rememberCoroutineScope()
    var phrase by remember(state.stage, state.vaultId) { mutableStateOf("") }
    var confirm by remember(state.stage, state.vaultId) { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    val create = state.stage == VaultStage.CREATE
    val choices by model.vaultChoices.collectAsStateWithLifecycle()
    var vaultName by remember(create) { mutableStateOf(if (create) model.newVaultName() else "") }
    PasswordScreenProtection()
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(androidx.compose.ui.res.painterResource(app.televault.R.drawable.tg_photos_logo), null, Modifier.size(48.dp))
            ForgottenVaultManager()
            Text("TG Photos", style = MaterialTheme.typography.titleLarge)
            Text("Private Gallery & Encrypted | Unlimited Storage", style = MaterialTheme.typography.labelSmall)
            Text(words("Your files. Your keys.", "আপনার ফাইল। আপনার কী।"), style = MaterialTheme.typography.titleMedium)
            Text(
                when (state.stage) {
                    VaultStage.CREATE -> words("No existing Drive found · set up a new encrypted Drive", "পুরোনো Drive পাওয়া যায়নি · নতুন এনক্রিপ্টেড Drive সেটআপ করুন")
                    VaultStage.DISCOVERING -> words("Looking for an existing Drive", "পুরোনো Drive খোঁজা হচ্ছে")
                    VaultStage.ERROR -> words("Could not check your Drive", "Drive যাচাই করা যায়নি")
                    else -> words("Existing Drive found · enter your recovery password", "পুরোনো Drive পাওয়া গেছে · রিকভারি পাসওয়ার্ড দিন")
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(words("Separate from Telegram login. Remember your password and keep a safe recovery copy. If it and your unlocked phone are lost, files cannot be recovered. Do not screenshot your password.", "এটি Telegram লগইন থেকে আলাদা। মনে রাখুন ও নিরাপদ কপি রাখুন। পাসওয়ার্ড ও আনলক করা ফোন দুটোই হারালে ফাইল ফেরত পাওয়া যাবে না। পাসওয়ার্ডের স্ক্রিনশট নেবেন না।"))
            if (state.stage == VaultStage.DISCOVERING || state.busy) {
                SecureLoader()
                Text(words("Finding your encrypted Drive…", "আপনার এনক্রিপ্টেড Drive খোঁজা হচ্ছে…"))
            }
            if (state.stage in setOf(VaultStage.CREATE, VaultStage.LOCKED, VaultStage.RECOVER)) {
                if (choices.isNotEmpty()) {
                    Text(words("Your vaults", "আপনার ভল্টগুলি"), style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { choice ->
                            FilterChip(selected = !create && choice.id == model.selectedVaultId, onClick = { model.selectVault(choice.id) }, enabled = !state.busy, label = { Text(choice.name, maxLines = 1) })
                        }
                    }
                }
                if (create) OutlinedTextField(vaultName, { vaultName = app.televault.domain.VaultNames.clean(it) }, Modifier.fillMaxWidth(), label = { Text(words("Vault name (optional)", "ভল্টের নাম (ঐচ্ছিক)")) }, singleLine = true)
                OutlinedTextField(model.passwordManagerAccount, {}, Modifier.fillMaxWidth().semantics { contentType = ContentType.Username }, label = { Text(words("Password Manager account", "Password Manager অ্যাকাউন্ট")) }, readOnly = true, singleLine = true)
                Text(words("Your password manager may offer to save or fill this phrase. Saving requires its permission; keep an offline recovery copy too.", "Password Manager পাসফ্রেজ সেভ বা পূরণ করার প্রস্তাব দিতে পারে। তার অনুমতি দরকার; অফলাইন রিকভারি কপিও রাখুন।"), style = MaterialTheme.typography.bodySmall)
                VaultSecretField(phrase, { phrase = it }, words("Recovery password", "রিকভারি পাসওয়ার্ড"), enabled = !state.busy, newPassword = create, fixedPattern = if (create || !state.patternRecoveryAvailable) false else null)
                if (!create) {
                    TextButton({
                        passwordScope.launch {
                            val saved = app.televault.security.VaultPasswordManager.fill(passwordContext, model.passwordManagerAccount)
                            if (saved != null) phrase = saved else model.report("No matching saved vault password was selected. Autofill or enter your recovery copy.")
                        }
                    }) { Text(words("Fill from Password Manager", "Password Manager থেকে পূরণ করুন")) }
                    Text(words("Forgot? Use fingerprint/phone PIN on your previously set-up phone, then reset in Settings. SMS/Telegram OTP cannot decrypt your vault.", "ভুলে গেছেন? আগে সেটআপ করা ফোনে ফিঙ্গারপ্রিন্ট/PIN দিয়ে খুলে Settings থেকে রিসেট করুন। SMS/Telegram OTP ভল্ট ডিক্রিপ্ট করতে পারে না।"), style = MaterialTheme.typography.bodySmall)
                }
                if (create) {
                    VaultSecretField(confirm, { confirm = it }, words("Confirm the same password", "একই পাসওয়ার্ড আবার দিন"), enabled = !state.busy, newPassword = true, error = confirm.isNotEmpty() && phrase != confirm, fixedPattern = false)
                    PasswordCreationGuidance(phrase) {
                        phrase = it
                        confirm = ""
                        acknowledged = false
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(acknowledged, { acknowledged = it })
                        Text(words("I saved a recovery copy. Without it or this unlocked phone, my vault cannot be recovered.", "রিকভারি কপি রেখেছি। এটি বা এই আনলক করা ফোন না থাকলে ভল্ট রিকভার হবে না।"), Modifier.weight(1f))
                    }
                }
            }
            if (!create && state.stage in setOf(VaultStage.LOCKED, VaultStage.RECOVER)) {
                TextButton({ model.beginNewVault() }, enabled = !state.busy) { Text(words("Forgot password? Create a new vault", "পাসওয়ার্ড ভুলে গেছেন? নতুন ভল্ট তৈরি করুন")) }
                TextButton({ model.findOtherVaults() }, enabled = !state.busy) { Text(words("Find my other vaults in Telegram", "Telegram-এ অন্য ভল্টগুলি খুঁজুন")) }
                ForgottenVaultStatus(state.vaultId, targetName = choices.firstOrNull { it.id == state.vaultId }?.name.orEmpty(), showPending = false)
            }
            if (state.notice.isNotBlank()) Text(state.notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.stage == VaultStage.ERROR) Button({ model.discover() }) { Text(words("Retry discovery", "আবার খুঁজুন")) }
        }
        if (state.stage in setOf(VaultStage.CREATE, VaultStage.LOCKED, VaultStage.RECOVER)) {
            Button(onClick = {
                val value = phrase.toCharArray()
                if (create) {
                    val toSave = value.copyOf()
                    val task = model.create(value, vaultName) { success ->
                        if (success) {
                            app.televault.security.VaultPasswordManager.offerSave(passwordContext, model.passwordManagerAccount, toSave, model::report)
                        } else {
                            toSave.fill('\u0000')
                        }
                        unlocked?.invoke(success)
                    }
                    task.invokeOnCompletion {
                        toSave.fill('\u0000')
                    }
                } else {
                    val toSave = value.copyOf()
                    val task = model.unlockFromGate(value) { verified ->
                        if (verified) app.televault.security.VaultPasswordManager.offerSave(passwordContext, model.passwordManagerAccountFor(toSave), toSave, model::report) else toSave.fill('\u0000')
                        unlocked?.invoke(verified)
                    }
                    task.invokeOnCompletion { toSave.fill('\u0000') }
                }
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 48.dp), enabled = !state.busy && app.televault.crypto.RecoveryPhrasePolicy.accepts(phrase) && (!create || app.televault.crypto.NewPasswordPolicy.accepts(phrase) && phrase == confirm && acknowledged)) { Text(if (create) words("Create vault", "ভল্ট তৈরি") else words("Unlock vault", "ভল্ট আনলক")) }
        }
    }
}

@Composable
private fun EmptyLibrary(upload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(words("Your memories, protected", "আপনার স্মৃতি, সুরক্ষিত"), style = MaterialTheme.typography.titleLarge)
        Text(words("Upload photos, videos or files. Only verified encrypted uploads appear here.", "ফটো, ভিডিও বা ফাইল আপলোড করুন। শুধু যাচাই করা এনক্রিপ্টেড আপলোড এখানে দেখা যাবে।"))
        Button(upload) { Text(words("Choose files", "ফাইল বাছুন")) }
    }
}

@Composable
private fun GalleryTile(file: DriveFile, selected: Boolean, model: DriveViewModel, click: () -> Unit, hold: () -> Unit) {
    var bitmap by remember(file.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.id) { bitmap = runCatching { model.thumbnail(file.id) }.getOrNull() }
    Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).combinedClickable(onClick = click, onLongClick = hold)) {
        val current = bitmap
        if (current != null) {
            Image(current.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(if (file.mime.startsWith("video/")) Icons.Outlined.PlayCircle else Icons.Outlined.Image, file.name, Modifier.size(40.dp).align(Alignment.Center), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (file.mime.startsWith("video/")) Icon(Icons.Outlined.PlayCircle, words("Video", "ভিডিও"), Modifier.padding(8.dp).align(Alignment.BottomEnd), tint = androidx.compose.ui.graphics.Color.White)
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))) {
                Icon(Icons.Outlined.CheckCircle, words("Selected", "নির্বাচিত"), Modifier.padding(8.dp).align(Alignment.TopEnd), tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

private fun byteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun transferPhase(phase: String) = when (phase) {
    "COMPLETE" -> words("Completed and verified", "সম্পন্ন ও যাচাইকৃত")
    "ENCRYPTING" -> words("Encrypting before upload", "আপলোডের আগে এনক্রিপশন")
    "UPLOADING" -> words("Uploading ciphertext", "এনক্রিপ্টেড ফাইল আপলোড")
    "VERIFYING" -> words("Verifying remote file", "ক্লাউডের ফাইল যাচাই")
    "COMMITTING_INDEX" -> words("Saving encrypted index", "এনক্রিপ্টেড ইনডেক্স সংরক্ষণ")
    "DOWNLOADING" -> words("Downloading and authenticating", "ডাউনলোড ও অখণ্ডতা যাচাই")
    "EXPORTING" -> words("Exporting verified original", "যাচাই করা মূল ফাইল সংরক্ষণ")
    "FAILED" -> words("Failed · check permission, connection, account file-size cap or free space", "ব্যর্থ · অনুমতি, সংযোগ, ফাইলের সীমা ও খালি জায়গা যাচাই করুন")
    "WAITING_TELEGRAM" -> words("Waiting for Telegram rate limit", "টেলিগ্রামের রেট লিমিট শেষ হওয়ার অপেক্ষা")
    "WAITING_UNLOCK" -> words("Unlock phone to continue", "চালিয়ে যেতে ফোন আনলক করুন")
    "CANCELLED" -> words("Cancelled", "বাতিল")
    "INTERRUPTED" -> words("Interrupted · retry available", "বাধাপ্রাপ্ত · আবার চেষ্টা করুন")
    else -> words("Queued · waiting for permitted network", "তালিকায় আছে · অনুমোদিত নেটওয়ার্কের অপেক্ষা")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewDialog(item: DriveFile, file: Uri?, close: () -> Unit, download: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AppSurface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopAppBar(title = { Text(item.name, maxLines = 1) }, navigationIcon = { IconButton(close) { Icon(Icons.Outlined.Close, words("Close preview", "প্রিভিউ বন্ধ")) } }, actions = { IconButton(download) { Icon(Icons.Outlined.Download, words("Save original", "মূল ফাইল সংরক্ষণ")) } })
                if (file == null) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        SecureLoader()
                        Spacer(Modifier.height(16.dp))
                        Text(words("Preparing verified download to preview…", "যাচাই করা ফাইল প্রিভিউয়ের জন্য নামানো হচ্ছে…"))
                    }
                } else {
                    when {
                        item.mime.startsWith("video/") || item.mime.startsWith("audio/") -> Player(file)
                        item.mime.startsWith("image/") || item.mime == "application/pdf" -> {
                            var image by remember(file) { mutableStateOf<Bitmap?>(null) }
                            LaunchedEffect(file) {
                                image = withContext(Dispatchers.IO) {
                                    runCatching {
                                        if (item.mime == "application/pdf") {
                                            PdfRenderer(checkNotNull(context.contentResolver.openFileDescriptor(file, "r"))).use { renderer ->
                                                renderer.openPage(0).use { page ->
                                                    val scale = 1200f / maxOf(page.width, page.height)
                                                    Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                                                        it.eraseColor(android.graphics.Color.WHITE)
                                                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                    }
                                                }
                                            }
                                        } else {
                                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            context.contentResolver.openInputStream(file)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                                            var sample = 1
                                            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                                            context.contentResolver.openInputStream(file)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                                        }
                                    }.getOrNull()
                                }
                            }
                            val current = image
                            if (current != null) {
                                Image(current.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            } else {
                                Text(words("This image/document could not be rendered. You can save the verified original.", "এই ছবি বা নথি দেখানো যায়নি। যাচাই করা মূল ফাইল সংরক্ষণ করতে পারেন।"), Modifier.padding(24.dp))
                            }
                        }
                        item.mime.startsWith("text/") -> {
                            var text by remember(file) { mutableStateOf("") }
                            LaunchedEffect(file) {
                                text = withContext(Dispatchers.IO) {
                                    checkNotNull(context.contentResolver.openInputStream(file)).use { input ->
                                        val bytes = ByteArray(256 * 1024)
                                        val count = input.read(bytes).coerceAtLeast(0)
                                        bytes.copyOf(count).toString(Charsets.UTF_8)
                                    }
                                }
                            }
                            Text(text, Modifier.verticalScroll(rememberScrollState()).padding(24.dp))
                        }
                        else -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(64.dp))
                            Text("${item.mime} · ${byteSize(item.size)}")
                            Text(words("Preview is not supported for this format. Save the verified original to a folder.", "এই ফরম্যাটের প্রিভিউ নেই। যাচাই করা মূল ফাইল ফোল্ডারে সংরক্ষণ করুন।"))
                            Button(download) { Text(words("Save original", "মূল ফাইল সংরক্ষণ")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Player(file: Uri) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.stop()
            player.release()
        }
    }
    AndroidView(factory = {
        PlayerView(it).apply {
            this.player = player
            useController = true
        }
    }, modifier = Modifier.fillMaxSize())
}
