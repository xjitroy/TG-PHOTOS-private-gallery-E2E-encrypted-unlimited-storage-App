package app.televault.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.PrivateProfile
import app.televault.domain.VideoPresentation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*

@Composable
internal fun ProfileAvatar(profile: PrivateProfile?, modifier: Modifier) {
    val encoded = profile?.avatar.orEmpty()
    val bitmap = remember(encoded) {
        runCatching {
            if (encoded.isBlank() || encoded.length > 32768) {
                null
            } else {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } finally {
                    bytes.fill(0)
                }
            }
        }.getOrNull()
    }
    // Tiny avatar RAM is shared by Compose drawing; never recycle a bitmap still in a frame.
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(profile?.name?.take(1)?.uppercase().orEmpty().ifBlank { "•" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateProfileScreen(model: DriveViewModel, favorites: List<MediaEntry>, close: () -> Unit, open: (MediaEntry, List<MediaEntry>) -> Unit) {
    val snapshot by model.state.collectAsStateWithLifecycle()
    val profile = snapshot.privateProfile
    val context = LocalContext.current
    var category by remember { mutableStateOf("Photos") }
    var edit by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val portraitProbes = remember { mutableStateMapOf<String, Boolean>() }
    val localVideos = favorites.filter { it.mime.startsWith("video/") && it.local != null }.take(128)
    LaunchedEffect(localVideos) {
        for (entry in localVideos) {
            val local = entry.local ?: continue
            if (portraitProbes.containsKey(local.fingerprint)) continue
            val facts = withContext(Dispatchers.IO) { app.televault.data.MediaFacts.read(context, local.uri, local.mime) }
            portraitProbes[local.fingerprint] = VideoPresentation.shortLayout(facts.width, facts.height, 1f)
        }
    }
    val probeResults = portraitProbes.toMap()
    val items = remember(favorites, category, probeResults) {
        favorites.filter { item ->
            when (category) {
                "Photos" -> item.mime.startsWith("image/")
                "Reels" -> item.mime.startsWith("video/") && ((probeResults[item.local?.fingerprint] ?: VideoPresentation.shortLayout(item.cloud?.facts?.width ?: item.local?.width ?: 0, item.cloud?.facts?.height ?: item.local?.height ?: 0, 1f)))
                else -> true
            }
        }.sortedWith(compareByDescending<MediaEntry> { it.date }.thenBy { it.id })
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                TopAppBar(title = { Text(label("Profile", "প্রোফাইল")) }, navigationIcon = { IconButton(close) { Icon(Icons.Outlined.ArrowBack, label("Back", "ফিরুন")) } }, actions = {
                    IconButton({
                        try {
                            val text = listOfNotNull(profile?.name, profile?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }, profile?.bio?.takeIf { it.isNotBlank() }).joinToString("\n")
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).apply { clipData = ClipData.newPlainText("Profile details", text) }, "Share profile details"))
                        } catch (_: Exception) {
                            message = "No compatible sharing app."
                        }
                    }, enabled = profile != null) { Icon(Icons.Outlined.Share, label("Share profile details", "প্রোফাইলের তথ্য শেয়ার")) }
                })
                Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProfileAvatar(profile, Modifier.size(88.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile?.name.orEmpty().ifBlank { label("Your private collection", "আপনার ব্যক্তিগত সংগ্রহ") }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!profile?.username.isNullOrBlank()) Text("@${profile?.username}", style = MaterialTheme.typography.bodyMedium)
                        Text(label("Only you · Cloud E2EE", "শুধু আপনি · Cloud E2EE"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Photos" to favorites.count { it.mime.startsWith("image/") }, "Videos" to favorites.count { it.mime.startsWith("video/") }, "Favorites" to favorites.size).forEach { (title, count) ->
                        Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$count", style = MaterialTheme.typography.titleLarge)
                                Text(title, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                if (!profile?.bio.isNullOrBlank()) Text(profile?.bio.orEmpty(), Modifier.padding(horizontal = 24.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ edit = true }, enabled = !snapshot.indexNeedsRepair) {
                        Icon(Icons.Outlined.Edit, null)
                        Text(label("Edit profile", "প্রোফাইল এডিট"))
                    }
                    TextButton({ model.sync() }) { Text(label("Sync cloud", "ক্লাউড সিঙ্ক")) }
                }
                Text(label("Favorites from this device and your encrypted cloud", "ডিভাইস ও এনক্রিপ্টেড ক্লাউডের প্রিয় ফাইল"), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Photos", "Reels", "Favorites").forEach { value -> FilterChip(category == value, { category = value }, label = { Text(if (value == "Reels") label("Reels", "রিলস") else sectionLabel(value)) }) }
                }
                if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
                val dateFormat = remember { SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault()) }
                LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(label("No favorites here yet. Add a heart to your photos or portrait videos.", "এখানে এখনও প্রিয় ফাইল নেই। ছবি বা লম্বা ভিডিওতে লাভ দিন।"), Modifier.padding(24.dp)) }
                    items(items, key = { it.id }) { item ->
                        Column(Modifier.clip(RoundedCornerShape(20.dp)).clickable { open(item, items) }.background(MaterialTheme.colorScheme.surfaceContainer)) {
                            Text(dateFormat.format(Date(item.date)), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            Box(Modifier.fillMaxWidth().aspectRatio(if (category == "Reels") 9f / 16f else 1f)) {
                                MediaThumbnail(item, model, Modifier.fillMaxSize(), 384, ContentScale.Crop, adaptive = true)
                                if (item.mime.startsWith("video/")) Icon(Icons.Outlined.PlayCircle, null, Modifier.align(Alignment.BottomEnd).padding(8.dp), tint = androidx.compose.ui.graphics.Color.White)
                            }
                        }
                    }
                }
            }
        }
        if (edit) ProfileEditor(model, profile ?: PrivateProfile(), { edit = false })
    }
}

@Composable
private fun ProfileEditor(model: DriveViewModel, initial: PrivateProfile, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var username by remember { mutableStateOf(initial.username) }
    var bio by remember { mutableStateOf(initial.bio) }
    var avatar by remember { mutableStateOf(initial.avatar) }
    var saving by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !saving) {
            scope.launch {
                processing = true
                try {
                    avatar = withContext(Dispatchers.IO) { PrivateProfile.avatar(context, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "Could not read this photo."
                } finally {
                    processing = false
                }
            }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving && !processing) close() }, title = { Text(label("Edit private profile", "ব্যক্তিগত প্রোফাইল এডিট")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileAvatar(PrivateProfile(name, username, bio, avatar), Modifier.size(64.dp).align(Alignment.CenterHorizontally))
            Row {
                TextButton({ photo.launch("image/*") }, enabled = !saving && !processing) { Text(label("Choose photo", "ছবি বাছুন")) }
                TextButton({ avatar = "" }, enabled = !saving && !processing) { Text(label("Remove", "সরান")) }
            }
            OutlinedTextField(name, { if (it.length <= 80) name = it }, label = { Text(label("Name", "নাম")) }, enabled = !saving, singleLine = true)
            OutlinedTextField(username, { if (it.removePrefix("@").length <= 64) username = it.removePrefix("@") }, label = { Text(label("Username", "ইউজারনেম")) }, enabled = !saving, singleLine = true)
            OutlinedTextField(bio, { if (it.length <= 500) bio = it }, label = { Text(label("Bio", "বায়ো")) }, enabled = !saving, maxLines = 4)
            Text(label("Saved E2EE in your Drive. These edits do not change your Telegram account. Sharing exports profile text only.", "আপনার Drive-এ E2EE হিসেবে সেভ হবে। Telegram account বদলাবে না। Share শুধু প্রোফাইলের লেখা পাঠায়।"), style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton({
            scope.launch {
                saving = true
                error = ""
                try {
                    model.saveProfile(PrivateProfile(name.trim(), username.trim(), bio.trim(), avatar))
                    close()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "Could not save to encrypted cloud. Reconnect and retry; your edits remain here."
                } finally {
                    saving = false
                }
            }
        }, enabled = name.isNotBlank() && !saving && !processing) { Text(if (saving) label("Saving…", "সেভ হচ্ছে…") else label("Save E2EE", "E2EE সেভ")) }
    }, dismissButton = { TextButton(close, enabled = !saving && !processing) { Text(label("Cancel", "বাতিল")) } })
}
