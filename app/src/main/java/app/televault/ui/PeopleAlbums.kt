package app.televault.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(person.cover, 0, person.cover.size, BitmapFactory.Options().apply { inMutable = true })?.let(slot::offer) }
        value = slot.publish()
    }
    DisposableEffect(slot) { onDispose { slot.retire() } }
    Surface(modifier.clip(CircleShape), shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null) }
    }
}

@Composable
internal fun PeopleAlbums(state: PeopleState, model: DriveViewModel, open: (PersonCard) -> Unit) {
    var consent by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<PersonCard?>(null) }
    var deferred by remember { mutableStateOf(setOf<String>()) }
    var automaticPrompt by remember { mutableStateOf(false) }
    val candidates = state.people.filter { it.suggestion != null && it.id !in deferred }
    LaunchedEffect(state.enabled, candidates.firstOrNull()?.id) {
        if (state.enabled && !automaticPrompt && candidates.isNotEmpty()) {
            delay(2500)
            review = candidates.first()
            automaticPrompt = true
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label("People", "মানুষ"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(label("Recognized on your phone", "আপনার ফোনেই শনাক্ত হয়"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.enabled, { value -> if (value) consent = true else model.peopleAction { model.people.enable(false) } })
        }
        if (state.enabled) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(if (state.busy) label("Finding faces gently in the background…", "পটভূমিতে ধীরে মুখ শনাক্ত হচ্ছে…") else label("${state.scanned} photos / video previews checked", "${state.scanned}টি ছবি / ভিডিও প্রিভিউ দেখা হয়েছে"), style = MaterialTheme.typography.bodyMedium)
                    Text(label("Faces can be mistaken. Review matches. Private Vault is excluded. Videos use a preview frame; tag other files yourself.", "মুখের মিল ভুল হতে পারে—যাচাই করুন। Private Vault বাদ থাকে। ভিডিওর প্রিভিউ দেখা হয়; অন্য ফাইলে নিজে ট্যাগ দিন।"), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton({
                            model.peopleAction { model.people.sync() }
                            model.indexPeople(model.state.value.files, model.localMedia.changes.value)
                        }) { Text(label(if (state.pendingSync) "Sync pending references" else "Sync & scan", "সিঙ্ক ও স্ক্যান")) }
                        if (candidates.isNotEmpty()) TextButton({ review = candidates.first() }) { Text(label("Review ${candidates.size}", "${candidates.size}টি মিল যাচাই")) }
                    }
                }
            }
        } else {
            Text(label("Turn on to group faces. Names and small reference photos can restore through encrypted Telegram storage.", "মুখ অনুযায়ী সাজাতে চালু করুন। নাম ও ছোট রেফারেন্স ছবি এনক্রিপ্টেড Telegram স্টোরেজ থেকে ফেরত আসবে।"), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.people.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(state.people, key = { it.id }) { person ->
                    Column(Modifier.width(86.dp).clickable { open(person) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        PersonPortrait(person, Modifier.size(80.dp))
                        Text(person.name.ifBlank { label("Add name", "নাম দিন") }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${person.members.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (consent) {
        AlertDialog(onDismissRequest = { consent = false }, icon = { Icon(Icons.Outlined.Face, null) }, title = { Text(label("Find people privately", "ব্যক্তিগতভাবে মুখ শনাক্ত")) }, text = { Text(label("Face detection and matching run only on this phone. Encrypted names, small reference faces and cloud-file tags are saved in your Telegram Saved Messages for recovery. This is optional and can make mistakes. It is not used for unlocking. Original files and Private Vault are unchanged.", "শনাক্তকরণ ও মিল খোঁজা এই ফোনেই হয়। পুনরুদ্ধারের জন্য নাম, ছোট রেফারেন্স মুখ ও ক্লাউড ফাইলের ট্যাগ এনক্রিপ্ট করে Telegram Saved Messages-এ রাখা হয়। এটি ঐচ্ছিক এবং ভুল হতে পারে। আনলকের জন্য ব্যবহৃত হয় না। মূল ফাইল ও Private Vault অপরিবর্তিত থাকে।")) }, confirmButton = {
            TextButton({
                consent = false
                model.peopleAction { model.people.enable(true) }
            }) { Text(label("Enable", "চালু করুন")) }
        }, dismissButton = { TextButton({ consent = false }) { Text(label("Not now", "এখন নয়")) } })
    }
    review?.let { candidate ->
        val current = state.people.find { it.id == candidate.id }
        val target = state.people.find { it.id == current?.suggestion }
        if (current != null && target != null) {
            AlertDialog(onDismissRequest = {
                deferred += candidate.id
                review = null
            }, title = { Text(label("Same person?", "একই ব্যক্তি?")) }, text = {
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
                    model.peopleAction { model.people.review(candidate.id, true) }
                    review = null
                }) { Text(label("Same person", "একই ব্যক্তি")) }
            }, dismissButton = {
                Row {
                    TextButton({
                        deferred += candidate.id
                        review = null
                    }) { Text(label("Later", "পরে")) }
                    TextButton({
                        model.peopleAction { model.people.review(candidate.id, false) }
                        review = null
                    }) { Text(label("Different", "আলাদা")) }
                }
            })
        } else {
            LaunchedEffect(candidate.id) { review = null }
        }
    }
}
