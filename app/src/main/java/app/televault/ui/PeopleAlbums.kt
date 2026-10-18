package app.televault.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.people.PeopleState
import app.televault.people.PersonCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun PersonPortrait(person: PersonCard, modifier: Modifier = Modifier) {
    val slot = remember(person.id, person.cover) { EphemeralImageSlot<android.graphics.Bitmap>({ if (it.isMutable && !it.isRecycled) it.eraseColor(0) }, { if (!it.isRecycled) it.recycle() }) }
    val image by produceState<android.graphics.Bitmap?>(null, slot) {
        guardedProjection(Unit) {
            withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(person.cover, 0, person.cover.size, BitmapFactory.Options().apply { inMutable = true })?.let(slot::offer)
                Unit
            }
        }
        value = slot.publish()
    }
    DisposableEffect(slot) { onDispose { slot.retire() } }
    Surface(modifier.clip(CircleShape), shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null) }
    }
}

@Composable
internal fun PeopleAlbums(state: PeopleState, model: DriveViewModel, open: (PersonCard) -> Unit) {
    var allPeople by remember { mutableStateOf(false) }
    var faceQuery by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<PersonCard?>(null) }
    var deferred by remember { mutableStateOf(setOf<String>()) }
    var continuous by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val pack by model.people.models.state.collectAsStateWithLifecycle()
    LaunchedEffect(pack.installed, state.enabled) {
        if (pack.installed && state.enabled) model.indexPeople(model.state.value.files, model.localMedia.changes.value)
    }
    val known = state.people.filter { !it.provisional }
    val unsorted = state.people.filter { it.provisional }
    val displayed = (known + unsorted).take(20)
    val candidates = state.people.filter { pack.installed && it.model == app.televault.people.PeopleStore.MODEL && it.suggestion != null && it.id !in deferred }
    LaunchedEffect(state.enabled, candidates.firstOrNull()?.id, review?.id, continuous) {
        if (state.enabled && continuous && review == null && candidates.isNotEmpty()) {
            delay(700)
            review = candidates.first()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label("People", "মানুষ"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(label("Recognized on your phone", "আপনার ফোনেই শনাক্ত হয়"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.people.isNotEmpty()) TextButton({ allPeople = true }) { Text(label("View all", "সব দেখুন")) }
            IconButton({ settings = true }) { Icon(Icons.Outlined.Settings, label("People settings", "People সেটিংস")) }
            Switch(state.enabled, { value -> if (value) consent = true else model.setPeopleEnabled(false) })
        }
        if (state.enabled && !pack.installed) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New on-device face recognition", style = MaterialTheme.typography.titleMedium)
                    Text("Download YuNet + SFace once (39 MB). Recognition runs on-device; no photo goes to an AI service. Previous names and confirmed tags are kept; automatic groups are rechecked with the new models.", style = MaterialTheme.typography.bodySmall)
                    if (pack.busy) {
                        val progress = (pack.downloaded.toFloat() / pack.total.coerceAtLeast(1)).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}% downloaded")
                        TextButton({ model.people.models.cancel() }) { Text("Pause download") }
                    } else {
                        Button({ model.people.models.install() }) { Text("Download & start") }
                    }
                    if (pack.notice.isNotBlank()) Text(pack.notice, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.enabled && pack.installed) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(if (state.busy) label("Finding faces gently in the background…", "পটভূমিতে ধীরে মুখ শনাক্ত হচ্ছে…") else label("${state.scanned} photos / video previews checked", "${state.scanned}টি ছবি / ভিডিও প্রিভিউ দেখা হয়েছে"), style = MaterialTheme.typography.bodyMedium)
                    Text(label("${known.size} groups · ${unsorted.size} unsorted faces · ${candidates.size} suggestions", "${known.size}টি গ্রুপ · ${unsorted.size}টি অসম্পূর্ণ মুখ · ${candidates.size}টি পরামর্শ"), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton({
                            model.peopleAction { model.people.sync() }
                            model.indexPeople(model.state.value.files, model.localMedia.changes.value)
                        }) { Text(label(if (state.pendingSync) "Sync pending references" else "Sync & scan", "সিঙ্ক ও স্ক্যান")) }
                        if (candidates.isNotEmpty()) {
                            TextButton({
                                continuous = true
                                deferred = emptySet()
                                review = candidates.first()
                            }) { Text(label("Review ${candidates.size}", "${candidates.size}টি মিল যাচাই")) }
                        }
                    }
                }
            }
        } else if (!state.enabled) {
            Text(label("Turn on to group faces. Names and small reference photos can restore through encrypted Telegram storage.", "মুখ অনুযায়ী সাজাতে চালু করুন। নাম ও ছোট রেফারেন্স ছবি এনক্রিপ্টেড Telegram স্টোরেজ থেকে ফেরত আসবে।"), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.people.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(displayed, key = { it.id }) { person ->
                    Column(Modifier.width(86.dp).clickable { open(person) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        PersonPortrait(person, Modifier.size(80.dp))
                        Text(person.name.ifBlank { if (person.provisional) label("Unsorted face", "অসম্পূর্ণ মুখ") else label("Add name", "নাম দিন") }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${person.members.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (allPeople) {
        Dialog(onDismissRequest = { allPeople = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label("People", "মানুষ"), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        IconButton({ allPeople = false }) { Icon(Icons.Outlined.Close, label("Close", "বন্ধ")) }
                    }
                    OutlinedTextField(faceQuery, { faceQuery = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text(label("Find by name", "নাম দিয়ে খুঁজুন")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(24.dp))
                    LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.weight(1f), contentPadding = PaddingValues(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(state.people.filter { it.name.contains(faceQuery, ignoreCase = true) }, key = { it.id }) { person ->
                            Column(
                                Modifier.clickable {
                                    allPeople = false
                                    open(person)
                                },
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                PersonPortrait(person, Modifier.size(88.dp))
                                Text(person.name.ifBlank { if (person.provisional) label("Unsorted face", "অসম্পূর্ণ মুখ") else label("Add name", "নাম দিন") }, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${person.members.size}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (consent) {
        AlertDialog(onDismissRequest = { consent = false }, icon = { Icon(Icons.Outlined.Face, null) }, title = { Text(label("Find people privately", "ব্যক্তিগতভাবে মুখ শনাক্ত")) }, text = { Text(label("Face detection and matching run only on this phone. Encrypted names, small reference faces and cloud-file tags are saved in your Telegram Saved Messages for recovery. This is optional and can make mistakes. It is not used for unlocking. Original files and Private Vault are unchanged.", "শনাক্তকরণ ও মিল খোঁজা এই ফোনেই হয়। পুনরুদ্ধারের জন্য নাম, ছোট রেফারেন্স মুখ ও ক্লাউড ফাইলের ট্যাগ এনক্রিপ্ট করে Telegram Saved Messages-এ রাখা হয়। এটি ঐচ্ছিক এবং ভুল হতে পারে। আনলকের জন্য ব্যবহৃত হয় না। মূল ফাইল ও Private Vault অপরিবর্তিত থাকে।")) }, confirmButton = {
            TextButton({
                consent = false
                model.setPeopleEnabled(true)
            }) { Text(label("Enable", "চালু করুন")) }
        }, dismissButton = { TextButton({ consent = false }) { Text(label("Not now", "এখন নয়")) } })
    }
    if (settings) {
        AlertDialog(onDismissRequest = { settings = false }, title = { Text(label("People settings", "People সেটিংস")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label("Matching runs on your phone. Your reviews improve encrypted face references; rejected pairs are remembered. Names, tags and references sync encrypted. Downloaded YuNet + SFace run on your phone. Uncertain matches stay unsorted; reviews start only when you choose Review. Private Vault is excluded. Videos use a preview frame.", "মিল এই ফোনেই খোঁজা হয়। আপনার রিভিউ থেকে এনক্রিপ্টেড রেফারেন্স উন্নত হয়; আলাদা মুখের সিদ্ধান্ত মনে থাকে। নাম ও রেফারেন্স এনক্রিপ্ট করে সিঙ্ক হয়। Private Vault বাদ থাকে। ভিডিওর প্রিভিউ দেখা হয়।"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label("Continuous reviews", "একের পর এক রিভিউ"), Modifier.weight(1f))
                    Switch(continuous, { continuous = it })
                }
                TextButton({
                    model.peopleAction {
                        model.people.refreshFaceTags()
                        model.indexPeople(model.state.value.files, model.localMedia.changes.value)
                    }
                    settings = false
                }) { Text(label("Update missing face tags", "অসম্পূর্ণ ফেস-ট্যাগ আপডেট")) }
                FaceResetControls(model)
                Text(label("Open a person to view photos, filter alone/together, rename or merge duplicate groups. Reviews improve matching examples, not the bundled model weights.", "ব্যক্তিতে ট্যাপ করে ছবি, একা/একসঙ্গে ফিল্টার, নাম ও duplicate merge পাবেন। রিভিউ মিলের রেফারেন্স উন্নত করে; মূল মডেলের ওজন বদলায় না।"), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ settings = false }) { Text(label("Done", "হয়েছে")) } })
    }
    review?.let { candidate ->
        val current = state.people.find { it.id == candidate.id }
        val target = state.people.find { it.id == current?.suggestion }
        if (current != null && target != null) {
            AlertDialog(onDismissRequest = {
                continuous = false
                review = null
            }, title = { Text(label("Same person? · ${candidates.size} remaining", "একই ব্যক্তি? · ${candidates.size}টি বাকি")) }, text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        PersonPortrait(current, Modifier.size(100.dp))
                        PersonPortrait(target, Modifier.size(100.dp))
                    }
                    Text(target.name.ifBlank { label("Review this match", "মিলটি যাচাই করুন") }, Modifier.padding(top = 12.dp))
                    Text(label("Only confirm if you recognize them.", "চিনতে পারলেই নিশ্চিত করুন।"), style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = {
                TextButton({
                    deferred += candidate.id
                    model.peopleAction { model.people.review(candidate.id, true, target.id) }
                    review = null
                }) { Text(label("Same person", "একই ব্যক্তি")) }
            }, dismissButton = {
                Row {
                    TextButton({
                        deferred += candidate.id
                        review = null
                    }) { Text(label("Later", "পরে")) }
                    TextButton({
                        deferred += candidate.id
                        model.peopleAction { model.people.review(candidate.id, false, target.id) }
                        review = null
                    }) { Text(label("Different", "আলাদা")) }
                }
            })
        } else {
            LaunchedEffect(candidate.id) { review = null }
        }
    }
}
