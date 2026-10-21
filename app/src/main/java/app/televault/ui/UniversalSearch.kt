package app.televault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.domain.MediaCategories
import app.televault.domain.UniversalMediaSearch
import app.televault.people.PeopleState
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SearchIndexInput(val files: List<MediaEntry>, val people: PeopleState, val textRevision: Pair<Long, Int>, val favorites: Set<String>, val sceneLabels: Map<String, String>, val smartRevision: Long, val smartModel: Pair<String, Boolean>, val semantic: Map<String, Float>)

/** One search across normal Photos/device/cloud media; never includes Private Vault or Trash. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UniversalSearch(files: List<MediaEntry>, people: PeopleState, model: DriveViewModel, initialPerson: String?, viewedId: String = "", viewerOpen: Boolean = false, close: () -> Unit, open: (MediaEntry, List<MediaEntry>) -> Unit) {
    val sceneIndex by model.scenes.state.collectAsStateWithLifecycle()
    val smartRevision by model.smart.revision.collectAsStateWithLifecycle()
    val smartModel by remember(model.smartModels) { model.smartModels.state.map { it.active to it.enabled }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = "" to false)
    val textIndex by model.ocr.state.collectAsStateWithLifecycle()
    val mediaState by model.contentState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var dates by remember { mutableStateOf(false) }
    var draftStart by remember { mutableStateOf("") }
    var draftEnd by remember { mutableStateOf("") }
    var calendar by remember { mutableStateOf(false) }
    val resultFocus = remember { BrowseFocusState() }
    var wasViewing by remember { mutableStateOf(false) }
    val returnToItem by model.preferences.returnToViewedItem.collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(viewerOpen, viewedId, returnToItem) {
        if (viewerOpen) {
            wasViewing = true
        } else if (wasViewing) {
            resultFocus.request(viewedId.takeIf { returnToItem })
            wasViewing = false
        }
    }
    var type by remember { mutableStateOf(if (initialPerson != null) "Photos" else "All") }
    var personId by remember { mutableStateOf(initialPerson) }
    var name by remember(personId) { mutableStateOf(people.people.find { it.id == personId }?.name.orEmpty()) }
    var alone by remember { mutableStateOf(false) }
    var companion by remember { mutableStateOf<String?>(null) }
    var chooseCompanion by remember { mutableStateOf(false) }
    var chooseMerge by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var savingName by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf("") }
    var personMenu by remember { mutableStateOf(false) }
    var density by remember { mutableFloatStateOf(4f) }
    var mosaic by remember { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val uiScope = rememberCoroutineScope()
    val nameFailure = label("Name could not be saved. Please retry.", "নাম সেভ হয়নি। আবার চেষ্টা করুন।")
    val filters = rememberMediaFilters(model) { }
    fun everyone() {
        personId = null
        alone = false
        companion = null
    }
    fun resetSearch() {
        everyone()
        query = ""
        start = ""
        end = ""
        draftStart = ""
        draftEnd = ""
        type = "All"
        filters.reset()
    }

    val person = people.people.find { it.id == personId }
    val parsed = remember(query, start, end) { UniversalMediaSearch.parseNatural(query, start, end, java.time.LocalDate.now()) }
    val scope = listOf(model.playbackEpoch, query, start, end, type, personId, companion, alone, filters.options)
    // Exact filenames/OCR/People results render independently of a slow cold AI process.
    val semanticKey = listOf(model.playbackEpoch, parsed.text(), smartModel)
    var semantic by remember(semanticKey) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    LaunchedEffect(semanticKey, files, smartRevision) {
        if (parsed.text().isBlank() || smartModel != ("embedding" to true)) return@LaunchedEffect
        delay(300)
        try {
            val ranked = withContext(Dispatchers.IO) { model.smart.rank(files.mapTo(hashSetOf()) { it.id }, parsed.text()) }
            currentCoroutineContext().ensureActive()
            semantic = ranked
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Existing exact matches stay usable when semantic inference is unavailable.
        }
    }
    val projection = rememberCoalescedProjection(scope, SearchIndexInput(files, people, textIndex.processed to textIndex.indexed, mediaState.localFavorites, sceneIndex.labels, smartRevision, smartModel, semantic), emptyList<MediaEntry>()) { input ->
        val (all, index, _, favorites, sceneLabels) = input
        val smartDescriptions = model.smart.descriptions(all.mapTo(hashSetOf()) { it.id })
        val personNames = mutableMapOf<String, MutableList<String>>()
        index.people.filter { it.name.isNotBlank() }.forEach { group -> group.members.forEach { personNames.getOrPut(it) { mutableListOf() }.add(group.name) } }
        val dateQuery = UniversalMediaSearch.Query("", parsed.start(), parsed.end(), parsed.valid())
        fun metadata(file: MediaEntry): String {
            val tags = MediaCategories.Category.entries.filter { it.mask and file.mediaCategories != 0 }.joinToString(" ") { it.name.replace('_', ' ') }
            return "${personNames[file.id].orEmpty().joinToString(" ")} ${sceneLabels[file.id].orEmpty()} ${smartDescriptions[file.id].orEmpty()} $tags ${file.local?.folder ?: file.cloud?.album.orEmpty()}"
        }
        val selection = SearchMediaFilter(type, personId, alone, companion, filters.options)
        val candidates = all.filter { file -> selection.matches(file, index, favorites) && UniversalMediaSearch.matches(dateQuery, "", "", file.date, ZoneId.systemDefault()) }
        val matches = model.ocr.search(candidates.associate { it.id to "${it.name} ${metadata(it)}" }, parsed.text())
        val semantic = input.semantic
        candidates.filter { it.id in matches || it.id in semantic }.sortedWith(compareByDescending<MediaEntry> { semantic[it.id] ?: 0f }.thenByDescending { it.date })
    }
    val draftDates = remember(draftStart, draftEnd) { UniversalMediaSearch.parse("", draftStart, draftEnd) }
    fun applyDates() {
        if (!draftDates.valid()) return
        start = draftStart
        end = draftEnd
        keyboard?.hide()
        focus.clearFocus()
    }
    val results = projection.value
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AppSurface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                TopAppBar(title = { Text(person?.name?.ifBlank { label("People", "মানুষ") } ?: label("Search everything", "সবকিছু খুঁজুন"), maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(close) { Icon(Icons.Outlined.ArrowBack, label("Back", "পিছনে")) } }, actions = {
                    if (query.isNotBlank() || personId != null || type != "All" || dates || start.isNotBlank() || end.isNotBlank() || filters.options.active) {
                        TextButton({
                            resetSearch()
                            dates = false
                        }) { Text(label("Clear all", "সব রিসেট")) }
                    }
                }, windowInsets = WindowInsets(0, 0, 0, 0))
                val grid = remember(scope) { LazyGridState() }
                val introduction: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (initialPerson == null) {
                            TextSearchControls(model)
                            SmartSuggestionControls(model)
                        }
                        OutlinedTextField(
                            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text(label("Search people, dates, text or subjects", "নাম, তারিখ বা ছবির লেখা খুঁজুন")) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = {
                                Row {
                                    if (query.isNotBlank()) IconButton({ query = "" }) { Icon(Icons.Outlined.Close, label("Clear search", "সার্চ মুছুন")) }
                                    IconButton({
                                        keyboard?.hide()
                                        focus.clearFocus()
                                        projection.retry()
                                    }) { Icon(Icons.Outlined.Search, label("Search", "সার্চ করুন")) }
                                }
                            }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                keyboard?.hide()
                                focus.clearFocus()
                                projection.retry()
                            }),
                            shape = RoundedCornerShape(28.dp)
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            listOf("All", "Photos", "Videos", "Audio", "Files", "Favorites").forEach { key -> FilterChip(type == key, { type = key }, label = { Text(sectionLabel(key)) }, modifier = Modifier.padding(end = 6.dp), shape = RoundedCornerShape(24.dp)) }
                            FilterChip(dates || start.isNotBlank() || end.isNotBlank(), { dates = !dates }, label = { Text(label("Dates", "তারিখ")) }, leadingIcon = { Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp)) })
                        }
                        MediaFilterBar(filters)
                        if (dates) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(draftStart, { draftStart = it }, Modifier.weight(1f), label = { Text(label("From", "শুরু")) }, placeholder = { Text("DD/MM/YYYY") }, singleLine = true, isError = !draftDates.valid(), shape = RoundedCornerShape(16.dp))
                                OutlinedTextField(draftEnd, { draftEnd = it }, Modifier.weight(1f), label = { Text(label("Through", "শেষ")) }, placeholder = { Text("DD/MM/YYYY") }, singleLine = true, isError = !draftDates.valid(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { applyDates() }), shape = RoundedCornerShape(16.dp))
                            }
                            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({
                                    keyboard?.hide()
                                    focus.clearFocus()
                                    calendar = true
                                }) {
                                    Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(label("Choose dates", "তারিখ বাছুন"))
                                }
                                Button({ applyDates() }, enabled = draftDates.valid() && (draftStart.isNotBlank() || draftEnd.isNotBlank())) { Text(label("Apply dates", "তারিখে সার্চ করুন")) }
                                TextButton({
                                    draftStart = ""
                                    draftEnd = ""
                                    start = ""
                                    end = ""
                                }) { Text(label("Clear", "মুছুন")) }
                            }
                            if (!draftDates.valid()) Text(label("Use DD/MM/YYYY or YYYY-MM-DD; start must be before the end.", "DD/MM/YYYY অথবা YYYY-MM-DD দিন; শুরু শেষের আগে হবে।"), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                            if (start.isNotBlank() || end.isNotBlank()) Text(label("Applied: ", "সক্রিয়: ") + start + " – " + end, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        if (!parsed.valid()) Text(label("Enter valid dates, with the start before the end.", "সঠিক তারিখ দিন; শুরু শেষের আগে হবে।"), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                        if (initialPerson == null && people.people.any { it.name.isNotBlank() }) {
                            Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(personId == null, { everyone() }, label = { Text(label("Everyone", "সবাই")) })
                                people.people.filter { it.name.isNotBlank() }.forEach { group ->
                                    FilterChip(personId == group.id, {
                                        personId = group.id
                                        alone = false
                                        companion = null
                                    }, label = { Text(group.name) })
                                }
                            }
                        }
                        if (person != null) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PersonPortrait(person, Modifier.size(52.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(person.name.ifBlank { label("Add name", "নাম দিন") }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(label("${person.members.size} tagged items", "${person.members.size}টি ট্যাগ"), style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton({
                                    name = person.name
                                    nameError = ""
                                    editingName = true
                                }) { Icon(Icons.Outlined.Edit, label("Edit name", "নাম বদলান")) }
                                Box {
                                    IconButton({ personMenu = true }) { Icon(Icons.Outlined.MoreVert, label("Person options", "ব্যক্তির অপশন")) }
                                    DropdownMenu(personMenu, { personMenu = false }) {
                                        DropdownMenuItem(text = { Text(label("Merge duplicate person", "একই ব্যক্তি মেশান")) }, onClick = {
                                            personMenu = false
                                            chooseMerge = true
                                        })
                                        DropdownMenuItem(text = { Text(label("Remove group", "গ্রুপ সরান")) }, onClick = {
                                            personMenu = false
                                            remove = true
                                        })
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label("This is me · personalize Memories", "এটি আমার ফেস · স্মৃতি সাজান"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(people.selfId == person.id, { selected -> model.peopleAction { model.people.selectSelf(person.id, selected) } })
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(!alone && companion == null, {
                                    alone = false
                                    companion = null
                                }, label = { Text(label("All photos", "সব ছবি")) })
                                FilterChip(alone, {
                                    alone = !alone
                                    companion = null
                                }, label = { Text(label("Alone", "একা")) })
                                FilterChip(companion != null, { chooseCompanion = true }, label = { Text(companion?.let { id -> people.people.find { it.id == id }?.name?.ifBlank { label("Together", "একসঙ্গে") } } ?: label("With someone", "কারও সঙ্গে")) })
                            }
                        }
                        if (projection.failed) {
                            Text(label("Search could not finish. Your media is kept.", "সার্চ সম্পূর্ণ হয়নি। আপনার মিডিয়া রাখা আছে।"), Modifier.padding(horizontal = 16.dp))
                            TextButton(projection.retry) { Text(label("Retry search", "সার্চ আবার চেষ্টা")) }
                        }
                        TimelineControls(density, { density = it }, results.size, mosaic, { mosaic = it })
                        if (results.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (!projection.ready) label("Searching your library…", "লাইব্রেরি খোঁজা হচ্ছে…") else label("No matching items", "মেলেনি"), style = MaterialTheme.typography.titleMedium)
                                if (projection.ready && textIndex.enabled) Text(label("Text results grow as background indexing finishes.", "ব্যাকগ্রাউন্ডে লেখা পড়া শেষ হলে আরও ফলাফল পাওয়া যাবে।"), style = MaterialTheme.typography.bodySmall)
                                TextButton({ resetSearch() }) { Text(label("Show all items", "সব দেখান")) }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalBrowseFocus provides resultFocus) {
                    MediaGrid(results, emptySet(), model, false, {
                        keyboard?.hide()
                        focus.clearFocus()
                        open(it, results)
                    }, {
                        keyboard?.hide()
                        focus.clearFocus()
                        open(it, results)
                    }, density, { density = it }, grid = grid, mosaic = mosaic, leadingContent = introduction)
                }
            }
        }
    }
    if (calendar) {
        fun utcMillis(text: String): Long? = SearchCalendarDates.utcMillis(text)
        val range = rememberDateRangePickerState(initialSelectedStartDateMillis = if (draftDates.valid()) utcMillis(draftStart) ?: utcMillis(draftEnd) else null, initialSelectedEndDateMillis = if (draftDates.valid() && draftStart.isNotBlank() && draftEnd.isNotBlank()) utcMillis(draftEnd) else null)
        DatePickerDialog(onDismissRequest = { calendar = false }, confirmButton = {
            TextButton({
                fun day(value: Long?) = value?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()
                draftStart = day(range.selectedStartDateMillis)
                draftEnd = day(range.selectedEndDateMillis)
                start = draftStart
                end = draftEnd
                keyboard?.hide()
                focus.clearFocus()
                calendar = false
            }, enabled = range.selectedStartDateMillis != null && range.selectedEndDateMillis != null) { Text(label("Apply dates", "তারিখে সার্চ করুন")) }
        }, dismissButton = { TextButton({ calendar = false }) { Text(label("Cancel", "বাতিল")) } }) {
            DateRangePicker(range, Modifier.heightIn(max = 520.dp), title = { Text(label("Search date range", "তারিখ অনুযায়ী সার্চ"), Modifier.padding(20.dp)) }, showModeToggle = true)
        }
    }
    if (editingName && person != null) {
        AlertDialog(onDismissRequest = { if (!savingName) editingName = false }, title = { Text(label("Edit name", "নাম বদলান")) }, text = {
            Column {
                OutlinedTextField(name, {
                    name = it.take(80)
                    nameError = ""
                }, Modifier.fillMaxWidth(), label = { Text(label("Person's name", "ব্যক্তির নাম")) }, singleLine = true, enabled = !savingName)
                if (nameError.isNotBlank()) Text(nameError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(label("Saved encrypted on this phone; Telegram sync follows automatically.", "এই ফোনে এনক্রিপ্ট করে সেভ হবে; তারপর Telegram সিঙ্ক হবে।"), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = !savingName, onClick = {
                savingName = true
                uiScope.launch {
                    try {
                        model.people.rename(person.id, name)
                        editingName = false
                        keyboard?.hide()
                        focus.clearFocus()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        nameError = nameFailure
                    } finally {
                        savingName = false
                    }
                }
            }) { Text(if (savingName) label("Saving…", "সেভ হচ্ছে…") else label("Save", "সেভ")) }
        }, dismissButton = { TextButton(enabled = !savingName, onClick = { editingName = false }) { Text(label("Cancel", "বাতিল")) } })
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
}
