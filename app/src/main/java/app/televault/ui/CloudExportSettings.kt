package app.televault.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.R
import app.televault.data.CloudExportHistory
import app.televault.data.CloudExportOptions
import app.televault.domain.MediaCategories
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TelegramStorageWarning(modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Text("DO NOT DELETE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(label("Keep Telegram Saved Messages", "Telegram-এর Saved Messages রাখুন"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Image(painterResource(R.drawable.telegram_saved_messages_guide), label("Example of the Saved Messages chat containing encrypted TG Photos files. Do not delete this chat or its backup attachments.", "Encrypted TG Photos ফাইল থাকা Saved Messages চ্যাটের নমুনা। এই চ্যাট বা ব্যাকআপ ফাইল মুছবেন না।"), Modifier.fillMaxWidth().aspectRatio(3f), contentScale = ContentScale.Fit)
            Text(label("Do not delete Saved Messages or TG Photos attachments, recovery and catalog messages inside it. They contain your encrypted backups and double-encrypted Private Vault files. Deleting them in Telegram can permanently lose your data. Manage backup deletion inside TG Photos.", "Saved Messages চ্যাট বা এর TG Photos ফাইল, recovery ও catalog message মুছবেন না। এখানে আপনার encrypted backup এবং ডবল encrypted Private Vault ফাইল থাকে। Telegram থেকে মুছে দিলে ডাটা স্থায়ীভাবে হারাতে পারেন। ব্যাকআপ মোছার কাজ TG Photos-এর ভিতর থেকে করুন।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

private fun exportTime(time: Long): String = if (time <= 0) "—" else DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

@Composable
private fun exportTypes(options: CloudExportOptions): String = if (options.kinds == 0) label("All file types", "সব ধরনের ফাইল") else listOf(1 to label("Photos", "ফটো"), 2 to label("Videos", "ভিডিও"), 4 to label("Audio", "অডিও"), 8 to label("Files", "ফাইল")).filter { options.kinds and it.first != 0 }.joinToString(", ") { it.second }

@Composable
private fun exportCategories(options: CloudExportOptions): String = if (options.categories == 0) label("All categories", "সব ক্যাটাগরি") else MediaCategories.Category.entries.filter { it.mask != 0 && options.categories and it.mask != 0 }.map { mediaCategoryLabel(it) }.joinToString(", ")
private fun exportDay(time: Long, zone: String): String = if (time <= 0) "—" else DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(time))

@Composable
private fun exportRange(options: CloudExportOptions): String = (if (options.backupTime) label("Backup date", "ব্যাকআপের তারিখ") else label("Original date", "মূল তারিখ")) + " · " + if (options.from == 0L && options.until == 0L) label("All time", "সব সময়") else exportDay(options.from, options.timeZone) + " → " + exportDay(options.until - 1, options.timeZone)

@Composable
private fun exportPhase(phase: String): String = when (phase) {
    "PREPARING" -> label("Preparing cloud library", "ক্লাউড লাইব্রেরি প্রস্তুত হচ্ছে")
    "QUEUED" -> label("Queued", "অপেক্ষায়")
    "EXPORTING" -> label("Downloading originals", "Original ফাইল ডাউনলোড হচ্ছে")
    "VERIFYING" -> label("Verifying saved ZIP", "সেভ করা ZIP যাচাই হচ্ছে")
    "COMPLETE" -> label("Saved", "সেভ হয়েছে")
    "FAILED" -> label("Needs attention", "মনোযোগ প্রয়োজন")
    "CANCELLED" -> label("Cancelled", "বাতিল")
    "RETRYING" -> label("Waiting to retry", "আবার চেষ্টার অপেক্ষায়")
    else -> label("Waiting for network, Telegram or phone unlock", "নেটওয়ার্ক, Telegram অথবা ফোন আনলকের অপেক্ষায়")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CloudExportSettings(model: DriveViewModel, modifier: Modifier = Modifier) {
    val state by model.contentState.collectAsStateWithLifecycle()
    val history by model.cloudExports.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kinds by rememberSaveable { mutableIntStateOf(0) }
    var categories by rememberSaveable { mutableIntStateOf(0) }
    var from by rememberSaveable { mutableLongStateOf(0) }
    var until by rememberSaveable { mutableLongStateOf(0) }
    var backupTime by rememberSaveable { mutableStateOf(false) }
    var timeZone by rememberSaveable { mutableStateOf(ZoneId.systemDefault().id) }
    var dates by rememberSaveable { mutableStateOf(false) }
    var showCategories by rememberSaveable { mutableStateOf(false) }
    var customFolder by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var pendingSpec by rememberSaveable { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    val options = CloudExportOptions(kinds, categories, from, until, backupTime, timeZone)
    val summary by produceState(0 to 0L, state.files, options) {
        value = withContext(Dispatchers.Default) {
            var count = 0
            var bytes = 0L
            state.files.forEach {
                if (it.trashedAt == 0L && options.matches(it)) {
                    count++
                    bytes += it.size
                }
            }
            count to bytes
        }
    }
    val active = starting || history.any { it.phase !in setOf("COMPLETE", "FAILED", "CANCELLED") }
    val last = history.filter { it.phase == "COMPLETE" }.maxByOrNull { it.completedAt }
    val rememberedFolder = history.firstOrNull { !it.automaticDownloads && it.directory.isNotBlank() }?.directory
    val modernDownloads = Build.VERSION.SDK_INT >= 29
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun startExport(chosen: CloudExportOptions, uri: Uri?) {
        starting = true
        scope.launch {
            try {
                model.exportCloud(chosen, uri).join()
            } finally {
                starting = false
            }
        }
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val localFolderContract = remember {
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: android.content.Context, input: Uri?) = super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
        }
    }
    val folder = rememberLauncherForActivityResult(localFolderContract) { uri ->
        val chosen = runCatching { pendingSpec?.let { CloudExportOptions.read(org.json.JSONObject(it)) } }.getOrNull()
        pendingSpec = null
        if (uri != null && chosen != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                startExport(chosen, uri)
            } catch (_: Exception) {
                model.report("Choose a writable local phone folder for the background ZIP export.")
            }
        }
    }
    LaunchedEffect(model, active) {
        model.refreshCloudExports()
        if (active) {
            while (true) {
                delay(2000)
                model.refreshCloudExports()
            }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(label("Export your cloud library", "ক্লাউড লাইব্রেরি এক্সপোর্ট"), style = MaterialTheme.typography.headlineSmall)
            Text(label("Choose what to save. We prepare, download and verify your ZIP in the background.", "কী সেভ করবেন বেছে নিন। ব্যাকগ্রাউন্ডে ZIP তৈরি, ডাউনলোড ও যাচাই হবে।"), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.History, null)
                        Text(label("Last successful export", "সর্বশেষ সফল এক্সপোর্ট"), style = MaterialTheme.typography.titleMedium)
                    }
                    if (last == null) {
                        Text(label("No completed export yet", "এখনও কোনো এক্সপোর্ট সম্পূর্ণ হয়নি"))
                    } else {
                        Text(exportTime(last.completedAt), style = MaterialTheme.typography.titleSmall)
                        Text(exportTypes(last.options) + " · " + exportCategories(last.options), style = MaterialTheme.typography.bodyMedium)
                        Text(exportRange(last.options), style = MaterialTheme.typography.bodyMedium)
                        if (last.coveredFrom > 0) Text(label("Files covered: ", "অন্তর্ভুক্ত ফাইলের সময়: ") + exportDay(last.coveredFrom, last.options.timeZone) + " → " + exportDay(last.coveredUntil, last.options.timeZone), style = MaterialTheme.typography.bodySmall)
                        Text("${last.count} " + label("files", "ফাইল") + " · " + sizeLabel(last.total), style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ openCloudZip(context, last) { model.report(it) } }) { Text(label("Open ZIP", "ZIP খুলুন")) }
                            TextButton({
                                kinds = last.options.kinds
                                categories = last.options.categories
                                from = last.options.from
                                until = last.options.until
                                backupTime = last.options.backupTime
                                timeZone = last.options.timeZone
                            }) { Text(label("Use these filters", "এই ফিল্টার ব্যবহার করুন")) }
                        }
                    }
                }
            }
        }
        item {
            ExportSetupCard(label("1 · What to export", "১ · কী এক্সপোর্ট করবেন")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kinds == 0 && categories == 0, {
                        kinds = 0
                        categories = 0
                    }, { Text(label("Everything", "সবকিছু")) })
                    FilterChip(kinds == 1 && categories == MediaCategories.Category.CAMERA.mask, {
                        kinds = 1
                        categories = MediaCategories.Category.CAMERA.mask
                    }, { Text(label("Camera photos", "ক্যামেরার ফটো")) })
                    FilterChip(kinds == 1 && categories == MediaCategories.Category.SCREENSHOTS.mask, {
                        kinds = 1
                        categories = MediaCategories.Category.SCREENSHOTS.mask
                    }, { Text(label("Screenshots", "স্ক্রিনশট")) })
                    FilterChip(kinds == 2 && categories == 0, {
                        kinds = 2
                        categories = 0
                    }, { Text(label("Videos only", "শুধু ভিডিও")) })
                }
                Text(label("File types", "ফাইলের ধরন"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kinds == 0, { kinds = 0 }, { Text(label("All", "সব")) })
                    listOf(1 to label("Photos", "ফটো"), 2 to label("Videos", "ভিডিও"), 4 to label("Audio", "অডিও"), 8 to label("Files / documents", "ফাইল / ডকুমেন্ট")).forEach { (mask, title) -> FilterChip(kinds and mask != 0, { kinds = kinds xor mask }, { Text(title) }) }
                }
                TextButton({ showCategories = !showCategories }) {
                    Text(label("Categories: ", "ক্যাটাগরি: ") + exportCategories(options), Modifier.weight(1f))
                    Icon(if (showCategories) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
                if (showCategories) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(categories == 0, { categories = 0 }, { Text(label("All", "সব")) })
                        MediaCategories.Category.entries.filter { it != MediaCategories.Category.ALL }.forEach { category -> FilterChip(categories and category.mask != 0, { categories = categories xor category.mask }, { Text(mediaCategoryLabel(category)) }) }
                    }
                }
                Text(label("Hidden and Clean view do not limit exports. Pending uploads, Trash and Private Vault are excluded.", "Hidden ও Clean view এখানে প্রভাব ফেলবে না। Pending upload, Trash ও Private Vault অন্তর্ভুক্ত নয়।"), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ExportSetupCard(label("2 · Choose time frame", "২ · সময়সীমা বাছুন")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!backupTime, { backupTime = false }, { Text(label("Original date", "মূল তারিখ")) })
                    FilterChip(backupTime, { backupTime = true }, { Text(label("Backup date", "ব্যাকআপের তারিখ")) })
                }
                Text(exportRange(options), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ dates = true }) { Text(label("From → To", "শুরু → শেষ তারিখ")) }
                    TextButton({
                        from = 0
                        until = 0
                    }) { Text(label("All time", "সব সময়")) }
                }
                Text(label("Both selected dates are included. Time zone: ", "নির্বাচিত দুই তারিখই অন্তর্ভুক্ত। টাইমজোন: ") + timeZone, style = MaterialTheme.typography.bodySmall)
                if (backupTime) Text(label("Unknown older backup dates are included in All time, but excluded from a specific range.", "পুরোনো ব্যাকআপের সময় অজানা হলে All time-এ থাকবে; নির্দিষ্ট সীমায় থাকবে না।"), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            ExportSetupCard(label("3 · Save ZIP", "৩ · ZIP সেভ করুন")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Download, null)
                    Text(
                        if (modernDownloads && !customFolder) {
                            "Downloads / TG Photos"
                        } else if (!customFolder && rememberedFolder != null) {
                            label("Previously chosen phone folder", "আগের নির্বাচিত ফোন ফোল্ডার")
                        } else {
                            label("Choose your Downloads or phone folder", "Downloads অথবা ফোনের ফোল্ডার বাছুন")
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                if (modernDownloads && !customFolder) Text(label("Saved automatically after verification. No folder picker is needed.", "যাচাই শেষে অটোমেটিক সেভ হবে। ফোল্ডার বাছতে হবে না।"), style = MaterialTheme.typography.bodySmall)
                TextButton({ customFolder = !customFolder }) { Text(if (customFolder) label("Use default destination", "ডিফল্ট ফোল্ডার ব্যবহার করুন") else label("Choose another folder", "অন্য ফোল্ডার বাছুন")) }
                Text("${summary.first} " + label("matching files", "মিলে যাওয়া ফাইল") + " · " + sizeLabel(summary.second), style = MaterialTheme.typography.titleMedium)
                Text(label("This is a preview of the current index. The background job refreshes the cloud library before freezing the final selection. ZIP names include export date/time, categories, date range, file count and original bytes.", "এটি বর্তমান index-এর হিসাব। ব্যাকগ্রাউন্ডে cloud library refresh করে চূড়ান্ত তালিকা হবে। ZIP-এর নামে export-এর তারিখ-সময়, ক্যাটাগরি, সময়সীমা, ফাইল সংখ্যা ও মূল সাইজ থাকবে।"), style = MaterialTheme.typography.bodySmall)
                Button({ confirm = true }, Modifier.fillMaxWidth(), enabled = !active && state.indexLoaded && !state.indexNeedsRepair) {
                    Icon(Icons.Outlined.FolderZip, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (active) label("Export in progress", "এক্সপোর্ট চলছে") else label("Start background export", "ব্যাকগ্রাউন্ড এক্সপোর্ট শুরু"))
                }
            }
        }
        item { Text(label("Export history", "এক্সপোর্ট হিস্ট্রি"), style = MaterialTheme.typography.titleLarge) }
        if (history.isEmpty()) item { Text(label("Your exports and their date ranges will appear here.", "আপনার এক্সপোর্ট ও তাদের সময়সীমা এখানে দেখা যাবে।")) }
        items(history, key = { it.id }) { entry -> ExportHistoryCard(entry, model) }
    }
    if (dates) {
        ExportDateDialog(from, until, timeZone, dismiss = { dates = false }) { start, end ->
            from = start
            until = end
            dates = false
        }
    }
    if (confirm) {
        AlertDialog(onDismissRequest = { confirm = false }, title = { Text(label("Export this selection?", "এই তালিকা এক্সপোর্ট করবেন?")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(exportTypes(options) + " · " + exportCategories(options))
                Text(exportRange(options))
                Text(label("This normal Cloud ZIP contains decrypted originals, dates and location metadata. Anyone with the ZIP can read it. Private Vault uses its separate encrypted ZIP export. Cloud backups stay unchanged.", "এই normal Cloud ZIP-এ decrypted original, সময় ও location metadata থাকবে। ZIP যার কাছে থাকবে সে পড়তে পারবে। Private Vault-এর encrypted ZIP export আলাদা। Cloud backup অক্ষত থাকবে।"))
            }
        }, confirmButton = {
            TextButton({
                confirm = false
                val remembered = rememberedFolder?.let(Uri::parse)?.takeIf { uri -> context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission } }
                if (!customFolder && modernDownloads) {
                    startExport(options, null)
                } else if (!customFolder && remembered != null) {
                    startExport(options, remembered)
                } else {
                    pendingSpec = options.json().toString()
                    folder.launch(remembered ?: Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"))
                }
            }) { Text(label("Export ZIP", "ZIP এক্সপোর্ট")) }
        }, dismissButton = { TextButton({ confirm = false }) { Text(label("Cancel", "বাতিল")) } })
    }
}

@Composable
private fun ExportSetupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExportHistoryCard(entry: CloudExportHistory, model: DriveViewModel) {
    val context = LocalContext.current
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val active = entry.phase !in setOf("COMPLETE", "FAILED", "CANCELLED")
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (entry.phase == "COMPLETE") Icons.Outlined.TaskAlt else Icons.Outlined.FolderZip, null)
                Text(exportPhase(entry.phase), Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
                IconButton({ expanded = !expanded }) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, label("Export details", "এক্সপোর্টের বিস্তারিত")) }
            }
            Text(exportTypes(entry.options) + " · " + exportCategories(entry.options), style = MaterialTheme.typography.bodyMedium)
            Text(exportRange(entry.options), style = MaterialTheme.typography.bodySmall)
            Text("${entry.done}/${entry.count} " + label("files", "ফাইল") + " · " + sizeLabel(entry.bytes) + " / " + sizeLabel(entry.total), style = MaterialTheme.typography.bodySmall)
            if (active) {
                if (entry.phase == "EXPORTING" && entry.total > 0) LinearProgressIndicator(progress = { (entry.bytes.toFloat() / entry.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Text(label("Saved: ", "সেভ হয়েছে: ") + exportTime(entry.completedAt), style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                Text(entry.name, style = MaterialTheme.typography.bodySmall)
                Text(label("Requested: ", "শুরু: ") + exportTime(entry.requestedAt), style = MaterialTheme.typography.bodySmall)
                if (entry.coveredFrom > 0) Text(label("Files covered: ", "অন্তর্ভুক্ত ফাইলের সময়: ") + exportDay(entry.coveredFrom, entry.options.timeZone) + " → " + exportDay(entry.coveredUntil, entry.options.timeZone), style = MaterialTheme.typography.bodySmall)
                if (entry.unknownTimes > 0) Text("${entry.unknownTimes} " + label("files have unknown dates", "ফাইলের সময় অজানা"), style = MaterialTheme.typography.bodySmall)
                Text(if (entry.automaticDownloads) "Downloads / TG Photos" else label("Chosen phone folder", "নির্বাচিত ফোন ফোল্ডার"), style = MaterialTheme.typography.bodySmall)
            }
            if (entry.phase == "FAILED") Text(if (entry.problem == "EMPTY_SELECTION") label("No cloud files match these dates and categories. Choose a wider date range, All time or another category.", "এই তারিখ ও ক্যাটাগরিতে ক্লাউড ফাইল নেই। বড় সময়সীমা, All time অথবা অন্য ক্যাটাগরি বাছুন।") else label("Check connection, phone unlock, free space and folder access; then retry. Completed files resume from their last checkpoint.", "সংযোগ, ফোন আনলক, খালি জায়গা ও ফোল্ডারের অনুমতি দেখুন, তারপর আবার চেষ্টা করুন। সম্পূর্ণ ফাইলের পর থেকে resume হবে।"), style = MaterialTheme.typography.bodySmall)
            if (entry.phase == "CANCELLED" && !entry.automaticDownloads) Text(label("An incomplete .partial.zip may remain in your chosen folder; remove it with Files.", "নির্বাচিত ফোল্ডারে অসম্পূর্ণ .partial.zip থাকতে পারে; Files থেকে মুছতে পারেন।"), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.phase == "COMPLETE") TextButton({ openCloudZip(context, entry) { model.report(it) } }) { Text(label("Open ZIP", "ZIP খুলুন")) }
                if (entry.phase == "FAILED" || entry.phase == "RETRYING") TextButton({ model.retryCloudExport(entry.id) }) { Text(label("Retry", "আবার চেষ্টা")) }
                if (entry.phase !in setOf("COMPLETE", "CANCELLED")) TextButton({ model.cancelCloudExport(entry.id) }) { Text(label("Cancel export", "এক্সপোর্ট বাতিল")) }
            }
        }
    }
}

internal fun openCloudZip(context: android.content.Context, entry: CloudExportHistory, failure: (String) -> Unit) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(entry.destination), "application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: Exception) {
        failure("ZIP is saved in your selected folder. Open it with your file manager or a ZIP app.")
    }
}

@Composable
internal fun CloudExportCompletion(model: DriveViewModel) {
    val history by model.cloudExports.history.collectAsStateWithLifecycle()
    val completed = history.firstOrNull { it.phase == "COMPLETE" && !it.noticed } ?: return
    val context = LocalContext.current
    AlertDialog(onDismissRequest = { model.acknowledgeCloudExport(completed.id) }, icon = { Icon(Icons.Outlined.TaskAlt, null) }, title = { Text(label("Cloud ZIP saved", "Cloud ZIP সেভ হয়েছে")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(completed.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${completed.count} " + label("files · ", "ফাইল · ") + exportTime(completed.completedAt))
            Text(label("Saved and verified on your phone. See the full name and details in Settings → Export Cloud library → Export history.", "ফোনে সেভ ও যাচাই হয়েছে। পুরো নাম ও বিস্তারিত Settings → Export Cloud library → Export history-এ আছে।"))
        }
    }, confirmButton = {
        TextButton({
            openCloudZip(context, completed) { model.report(it) }
            model.acknowledgeCloudExport(completed.id)
        }) { Text(label("Open ZIP", "ZIP খুলুন")) }
    }, dismissButton = { TextButton({ model.acknowledgeCloudExport(completed.id) }) { Text(label("Done", "ঠিক আছে")) } })
}

@Composable
internal fun TelegramStorageFirstOpen(preferences: app.televault.PreferencesRepository) {
    val seen by preferences.telegramStorageWarningSeen.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    if (!seen) AlertDialog(onDismissRequest = {}, title = { Text(label("Keep Telegram backup files", "Telegram-এর ব্যাকআপ ফাইল রাখুন")) }, text = { Box(Modifier.verticalScroll(rememberScrollState())) { TelegramStorageWarning() } }, confirmButton = { TextButton({ scope.launch { preferences.markTelegramStorageWarningSeen() } }) { Text(label("I understand", "বুঝেছি")) } })
}
