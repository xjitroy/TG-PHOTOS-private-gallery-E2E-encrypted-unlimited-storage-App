package app.televault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.televault.DriveViewModel
import app.televault.domain.MediaCategories.Category
import app.televault.domain.MemoryHighlights
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

internal data class PhotoMemory(val key: String, val years: Int, val date: LocalDate, val entries: List<MediaEntry>, val title: String? = null)
internal fun PhotoMemory.receipt() = memoryReceipt(key, entries.map { Triple(it.local?.fingerprint ?: it.cloud?.hash ?: it.id, it.date, it.size) })

@Composable
internal fun rememberPhotoMemories(files: List<MediaEntry>, scope: Any, people: List<app.televault.people.PersonCard> = emptyList(), selfId: String? = null, scenes: Map<String, String> = emptyMap(), deviceLinks: Map<String, String> = emptyMap()): List<PhotoMemory> {
    val zone = ZoneId.systemDefault()
    val today by produceState(LocalDate.now(zone), zone) {
        while (true) {
            delay(60_000)
            value = LocalDate.now(zone)
        }
    }
    val you = label("You", "আপনি")
    val ownTitle = label("Your moments", "আপনার মুহূর্তগুলো")
    val named = people.filter { it.name.isNotBlank() || it.id == selfId }.map { Triple(it.id, if (it.id == selfId) you else it.name, it.members) }
    return rememberBackgroundProjection(listOf(scope, today, named, selfId, you, ownTitle, scenes, deviceLinks), files, emptyList<PhotoMemory>()) { input ->
        val hidden = Category.SCREENSHOTS.mask or Category.SCREEN_RECORDINGS.mask or Category.DOCUMENTS.mask
        val eligible = input.filter { it.mime.startsWith("image/") && it.mediaCategories and hidden == 0 }
        fun canonical(photo: MediaEntry) = photo.local?.let { deviceLinks[it.fingerprint] } ?: photo.id
        val byId = eligible.associateBy { it.id }
        val canonicalIds = eligible.mapTo(hashSetOf()) { canonical(it) }
        val dateCards = MemoryHighlights.select(eligible.map { MemoryHighlights.Photo(it.id, it.date) }, today, zone).map { card ->
            PhotoMemory(card.key(), card.yearsAgo(), card.date(), card.ids().mapNotNull(byId::get), if (card.key().startsWith("recent:")) "Recent highlights" else null)
        }.filter { it.entries.isNotEmpty() }
        val self = people.find { it.id == selfId }
        val own = if (self == null) emptyList() else eligible.filter { canonical(it) in self.members }
        val personal = MemoryHighlights.select(own.map { MemoryHighlights.Photo(it.id, it.date) }, today, zone).map { card ->
            PhotoMemory("self:$selfId:${card.key()}", card.yearsAgo(), card.date(), card.ids().mapNotNull(byId::get), if (card.yearsAgo() == 0) ownTitle else null)
        }.filter { it.entries.isNotEmpty() }.take(2)
        val members = mutableMapOf<String, MutableList<Triple<String, String, Set<String>>>>()
        named.forEach { person -> person.third.forEach { id -> if (id in canonicalIds) members.getOrPut(id) { mutableListOf() }.let { if (it.size < 6) it.add(person) } } }
        val together = mutableMapOf<Pair<String, String>, MutableList<MediaEntry>>()
        val old = eligible.filter { it.date > 0 && java.time.Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate().isBefore(today.minusDays(30)) }
        for (photo in old) {
            val present = members[canonical(photo)].orEmpty()
            for (i in present.indices) {
                for (j in i + 1 until present.size) {
                    val key = listOf(present[i].first, present[j].first).sorted().let { it[0] to it[1] }
                    if (key !in together && together.size >= 2000) continue
                    val samples = together.getOrPut(key) { mutableListOf() }
                    if (samples.size < 60) {
                        samples.add(photo)
                    } else {
                        val day = java.time.Instant.ofEpochMilli(photo.date).atZone(zone).toLocalDate()
                        if (samples.none { java.time.Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() == day }) samples[samples.lastIndex] = photo
                    }
                }
            }
        }
        val pairs = together.entries.filter { (_, photos) -> photos.map { java.time.Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }.distinct().size >= 2 && photos.size >= 3 }.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, MutableList<MediaEntry>>> { it.key.first == selfId || it.key.second == selfId }.thenByDescending { it.value.maxOf { photo -> photo.date } }).take(2).map { (ids, photos) ->
            val names = named.filter { it.first == ids.first || it.first == ids.second }.joinToString(" & ") { it.second }
            val ordered = photos.distinctBy { it.id }.sortedByDescending { it.date }
            val entries = if (ordered.size <= 30) ordered else (0 until 30).map { ordered[it * (ordered.size - 1) / 29] }
            PhotoMemory("together:${ids.first}:${ids.second}", 0, java.time.Instant.ofEpochMilli(entries.first().date).atZone(zone).toLocalDate(), entries, names)
        }
        val themed = eligible.filter { it.date > 0 && it.date <= System.currentTimeMillis() }.groupBy { app.televault.domain.SceneLabels.theme((scenes[it.id] ?: scenes[canonical(it)]).orEmpty()) }.filter { it.key.isNotBlank() && it.value.size >= 3 }.entries.sortedBy { it.key }.let { groups ->
            if (groups.isEmpty()) emptyList() else listOf(groups[Math.floorMod(today.toEpochDay(), groups.size.toLong()).toInt()])
        }.map { (title, photos) ->
            val ordered = photos.sortedBy { it.date }
            val selected = if (ordered.size <= 30) ordered else (0 until 30).map { ordered[it * (ordered.size - 1) / 29] }
            PhotoMemory("subject:$title", 0, java.time.Instant.ofEpochMilli(selected.first().date).atZone(zone).toLocalDate(), selected, title)
        }
        (personal + pairs + themed + dateCards.filter { card -> personal.none { ownCard -> ownCard.entries.map { it.id }.toSet() == card.entries.map { it.id }.toSet() } }).take(6)
    }.value
}

@Composable
internal fun PhotoMemories(cards: List<PhotoMemory>, model: DriveViewModel, open: (PhotoMemory) -> Unit) {
    val revision by model.memorySeen.revision.collectAsState()
    val watched by produceState(emptySet<String>(), model.playbackEpoch, revision) { value = model.memorySeen.load(model.playbackEpoch) }
    var settings by remember { mutableStateOf(false) }
    if (settings) {
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Memories & suggestions") }, text = {
            Column {
                Text("Stories use dates and confirmed People tags on this phone. On-device subject suggestions also improve the same universal Search. Private Vault is excluded.")
                SmartSuggestionControls(model)
            }
        }, confirmButton = { TextButton({ settings = false }) { Text("Done") } })
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label("Memories", "স্মৃতি"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton({ settings = true }) { Text("Personalize") }
        }
        if (cards.isEmpty()) Text("Your moments will appear as your photo library grows.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cards, key = { it.key }) { card ->
                val seen = remember(card, watched) { card.receipt() in watched }
                val dim by animateFloatAsState(if (seen) .22f else 0f, tween(280), label = "Watched memory")
                val watchDescription = if (seen) label("Viewed", "দেখা হয়েছে") else label("Not viewed", "এখনও দেখা হয়নি")
                val title = card.title ?: if (card.years > 0) label(if (card.years == 1) "1 year ago" else "${card.years} years ago", "${card.years} বছর আগে") else label("A day to remember", "মনে রাখার একটি দিন")
                Box(Modifier.width(152.dp).height(168.dp).clip(RoundedCornerShape(26.dp)).semantics { stateDescription = watchDescription }.clickable { open(card) }) {
                    MediaThumbnail(card.entries.first(), model, Modifier.fillMaxSize(), pixels = 384, adaptive = true)
                    if (dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .35f to Color.Transparent, 1f to Color.Black.copy(alpha = .82f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(card.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())), color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        if (seen) Text(label("Viewed", "দেখা হয়েছে"), color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PhotoSectionPills(section: String, choose: (String) -> Unit, modifier: Modifier = Modifier) {
    AppSurface(modifier, shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("All", "Photos", "Videos", "Favorites").forEach { key ->
                FilterChip(section == key, { choose(key) }, label = { Text(sectionLabel(key)) }, shape = RoundedCornerShape(28.dp), border = null, colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
            }
        }
    }
}
