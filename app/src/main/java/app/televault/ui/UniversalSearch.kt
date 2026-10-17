package app.televault.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.domain.MediaCategories
import app.televault.domain.PeopleMediaFilter
import app.televault.domain.UniversalMediaSearch
import app.televault.people.PeopleState
import java.time.ZoneId

/** One search across normal Photos/device/cloud media; never includes Private Vault or Trash. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UniversalSearch(files: List<MediaEntry>, people: PeopleState, model: DriveViewModel, initialPerson: String?, close: () -> Unit, open: (MediaEntry, List<MediaEntry>) -> Unit) {
    val textIndex by model.ocr.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var dates by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(if (initialPerson != null) "Photos" else "All") }
    var personId by remember { mutableStateOf(initialPerson) }
    var name by remember(personId) { mutableStateOf(people.people.find { it.id == personId }?.name.orEmpty()) }
    var alone by remember { mutableStateOf(false) }
    var companion by remember { mutableStateOf<String?>(null) }
    var chooseCompanion by remember { mutableStateOf(false) }
    var chooseMerge by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf<MediaEntry?>(null) }
    val person = people.people.find { it.id == personId }
    val parsed = remember(query, start, end) { UniversalMediaSearch.parse(query, start, end) }
    val scope = listOf(model.playbackEpoch, query, start, end, type, personId, companion, alone, people.faceCounts, textIndex.indexed)
    val results = rememberBackgroundProjection(scope, files to people, emptyList<MediaEntry>()) { (all, index) ->
        val personNames = mutableMapOf<String, MutableList<String>>()
        index.people.filter { it.name.isNotBlank() }.forEach { group -> group.members.forEach { personNames.getOrPut(it) { mutableListOf() }.add(group.name) } }
        val members = index.people.find { it.id == personId }?.members
        kotlinx.coroutines.delay(250)
        val dateQuery = UniversalMediaSearch.Query("", parsed.start(), parsed.end(), parsed.valid())
        fun metadata(file: MediaEntry): String {
            val tags = MediaCategories.Category.entries.filter { it.mask and file.mediaCategories != 0 }.joinToString(" ") { it.name.replace('_', ' ') }
            return "${personNames[file.id].orEmpty().joinToString(" ")} $tags ${file.local?.folder ?: file.cloud?.album.orEmpty()}"
        }
        val candidates = all.filter { file ->
            val typeMatch = when (type) {
                "Photos" -> file.mime.startsWith("image/")
                "Videos" -> file.mime.startsWith("video/")
                "Audio", "Music" -> file.mime.startsWith("audio/")
                "Files" -> !file.mime.startsWith("image/") && !file.mime.startsWith("video/") && !file.mime.startsWith("audio/")
                else -> true
            }
            typeMatch && (!alone || file.mime.startsWith("image/")) && (personId == null || PeopleMediaFilter.matches(file.id, members.orEmpty(), companion?.let { id -> index.people.find { it.id == id }?.members }.orEmpty(), companion != null, alone, index.faceCounts[file.id] ?: -1)) && UniversalMediaSearch.matches(dateQuery, "", "", file.date, ZoneId.systemDefault())
        }
        val matches = model.ocr.search(candidates.associate { it.id to "${it.name} ${metadata(it)}" }, parsed.text())
        candidates.filter { it.id in matches }.sortedByDescending { it.date }
    }.value
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(title = { Text(person?.name?.ifBlank { label("People", "মানুষ") } ?: label("Search everything", "সবকিছু খুঁজুন"), maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(close) { Icon(Icons.Outlined.ArrowBack, label("Back", "পিছনে")) } }, windowInsets = WindowInsets(0, 0, 0, 0))
                if (initialPerson == null) TextSearchControls(model)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text(label("Name, person or 17/10/2026", "নাম, ব্যক্তি বা ১৭/১০/২০২৬")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    listOf("All", "Photos", "Videos", "Audio", "Files").forEach { key -> FilterChip(type == key, { type = key }, label = { Text(sectionLabel(key)) }, modifier = Modifier.padding(end = 6.dp), shape = RoundedCornerShape(24.dp)) }
                    FilterChip(dates, { dates = !dates }, label = { Text(label("Dates", "তারিখ")) }, leadingIcon = { Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp)) })
                }
                if (dates) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(start, { start = it }, Modifier.weight(1f), label = { Text(label("From", "শুরু")) }, placeholder = { Text("17/10/2026") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                        OutlinedTextField(end, { end = it }, Modifier.weight(1f), label = { Text(label("Through", "শেষ")) }, placeholder = { Text("17/10/2026") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    }
                }
                if (!parsed.valid()) Text(label("Enter valid dates, with the start before the end.", "সঠিক তারিখ দিন; শুরু শেষের আগে হবে।"), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                if (initialPerson == null && people.people.any { it.name.isNotBlank() }) {
                    Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(personId == null, { personId = null }, label = { Text(label("Everyone", "সবাই")) })
                        people.people.filter { it.name.isNotBlank() }.forEach { group -> FilterChip(personId == group.id, { personId = group.id }, label = { Text(group.name) }) }
                    }
                }
                if (person != null) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PersonPortrait(person, Modifier.size(44.dp))
                        OutlinedTextField(name, { name = it.take(80) }, Modifier.weight(1f), label = { Text(label("Person's name", "ব্যক্তির নাম")) }, singleLine = true, shape = RoundedCornerShape(16.dp))
                        IconButton({ model.peopleAction { model.people.rename(person.id, name) } }) { Icon(Icons.Outlined.Check, label("Save name", "নাম সেভ")) }
                        IconButton({ chooseMerge = true }) { Icon(Icons.Outlined.MergeType, label("Merge duplicate person", "একই ব্যক্তির গ্রুপ মেশান")) }
                        IconButton({ remove = true }) { Icon(Icons.Outlined.PersonRemove, label("Remove group", "গ্রুপ সরান")) }
                    }
                }
                if (person != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!alone && companion == null, {
                            alone = false
                            companion = null
                        }, label = { Text(label("All photos", "সব ছবি")) })
                        FilterChip(alone, {
                            alone = !alone
                            companion = null
                        }, label = { Text(label("Alone", "একা")) })
                        FilterChip(companion != null, { chooseCompanion = true }, label = { Text(companion?.let { id -> people.people.find { it.id == id }?.name?.ifBlank { label("Together", "একসঙ্গে") } } ?: label("With someone", "কারও সঙ্গে")) }, leadingIcon = { Icon(Icons.Outlined.People, null, Modifier.size(18.dp)) })
                    }
                    if (alone) Text(label("Only photos with one detected face; unprocessed photos are excluded.", "একটি মুখ শনাক্ত হওয়া ছবি; স্ক্যান না হওয়া ছবি বাদ থাকে।"), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
                }
                Text(label("${results.size} results · newest first", "${results.size}টি ফলাফল · নতুন আগে"), Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (initialPerson != null) {
                    LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(results, key = { it.id }) { item -> MediaThumbnail(item, model, Modifier.fillMaxWidth().aspectRatio(1f).clickable { open(item, results) }, pixels = 320) }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(results, key = { it.id }) { item ->
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.clickable { open(item, results) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    MediaThumbnail(item, model, Modifier.size(68.dp), pixels = 160)
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text("${sizeLabel(item.size)} · ${java.text.SimpleDateFormat("d MMM yyyy",java.util.Locale.getDefault()).format(java.util.Date(item.date))}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (people.people.any { it.name.isNotBlank() }) IconButton({ tagging = item }) { Icon(Icons.Outlined.PersonAdd, label("Tag a person", "ব্যক্তির ট্যাগ দিন")) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if ((chooseCompanion || chooseMerge) && person != null) {
        AlertDialog(onDismissRequest = {
            chooseCompanion = false
            chooseMerge = false
        }, title = { Text(if (chooseMerge) label("Merge into…", "এই গ্রুপে মেশান…") else label("Photos together with…", "একসঙ্গে থাকা ছবি…")) }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                people.people.filter { it.id != person.id }.forEach { other ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (chooseMerge) {
                                mergeTarget = other.id
                            } else {
                                companion = other.id
                                alone = false
                            }
                            chooseMerge = false
                            chooseCompanion = false
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PersonPortrait(other, Modifier.size(48.dp))
                        Text(other.name.ifBlank { label("Unnamed person", "নাম দেওয়া হয়নি") }, Modifier.weight(1f))
                        Text("${other.members.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }, confirmButton = {
            TextButton({
                chooseCompanion = false
                chooseMerge = false
            }) { Text(label("Cancel", "বাতিল")) }
        })
    }
    mergeTarget?.let { targetId ->
        val target = people.people.find { it.id == targetId }
        if (person != null && target != null) {
            AlertDialog(onDismissRequest = { mergeTarget = null }, title = { Text(label("Merge these people?", "এই দুই গ্রুপ মেশাবেন?")) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        PersonPortrait(person, Modifier.size(80.dp))
                        PersonPortrait(target, Modifier.size(80.dp))
                    }
                    Text(label("Confirm they are the same person. Photos and confirmed face references will be combined. Original files are kept.", "একই ব্যক্তি হলেই নিশ্চিত করুন। ছবি ও নিশ্চিত মুখের রেফারেন্স মিশবে। মূল ফাইল থাকবে।"))
                }
            }, confirmButton = {
                TextButton({
                    model.peopleAction { model.people.merge(person.id, target.id) }
                    personId = target.id
                    mergeTarget = null
                    alone = false
                    companion = null
                }) { Text(label("Merge", "মেশান")) }
            }, dismissButton = { TextButton({ mergeTarget = null }) { Text(label("Cancel", "বাতিল")) } })
        }
    }
    if (remove && person != null) {
        AlertDialog(onDismissRequest = { remove = false }, title = { Text(label("Remove this People group?", "এই People গ্রুপ সরাবেন?")) }, text = { Text(label("Original photos and files are kept. The encrypted reference will be removed from active People recovery after sync.", "মূল ছবি ও ফাইল থাকবে। সিঙ্কের পরে সক্রিয় People পুনরুদ্ধার থেকে এনক্রিপ্টেড রেফারেন্স সরবে।")) }, confirmButton = {
            TextButton({
                model.peopleAction { model.people.remove(person.id) }
                remove = false
                personId = null
            }) { Text(label("Remove group", "গ্রুপ সরান")) }
        }, dismissButton = { TextButton({ remove = false }) { Text(label("Cancel", "বাতিল")) } })
    }
    tagging?.let { item ->
        AlertDialog(onDismissRequest = { tagging = null }, title = { Text(label("Tag a person", "ব্যক্তির ট্যাগ দিন")) }, text = {
            Column(Modifier.heightIn(max = 320.dp).horizontalScroll(rememberScrollState())) {
                people.people.filter { it.name.isNotBlank() }.forEach { group ->
                    TextButton({
                        model.peopleAction { if (item.id in group.members) model.people.untag(group.id, item.id) else model.people.tag(group.id, setOf(item.id)) }
                        tagging = null
                    }) { Text(group.name) }
                }
            }
        }, confirmButton = { TextButton({ tagging = null }) { Text(label("Done", "হয়েছে")) } })
    }
}
