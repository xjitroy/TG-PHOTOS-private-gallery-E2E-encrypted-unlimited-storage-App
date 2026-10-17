package app.televault.ui

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
import androidx.compose.ui.text.font.FontWeight
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

@Composable
internal fun rememberPhotoMemories(files: List<MediaEntry>, scope: Any, people: List<app.televault.people.PersonCard> = emptyList()): List<PhotoMemory> {
    val zone = ZoneId.systemDefault()
    val today by produceState(LocalDate.now(zone), zone) {
        while (true) {
            delay(60_000)
            value = LocalDate.now(zone)
        }
    }
    val named = people.filter { it.name.isNotBlank() }.map { Triple(it.id, it.name, it.members) }
    return rememberBackgroundProjection(listOf(scope, today, named), files, emptyList<PhotoMemory>()) { input ->
        val hidden = Category.SCREENSHOTS.mask or Category.SCREEN_RECORDINGS.mask or Category.DOCUMENTS.mask
        val eligible = input.filter { it.mime.startsWith("image/") && it.mediaCategories and hidden == 0 }
        val byId = eligible.associateBy { it.id }
        val dateCards = MemoryHighlights.select(eligible.map { MemoryHighlights.Photo(it.id, it.date) }, today, zone).map { card ->
            PhotoMemory(card.key(), card.yearsAgo(), card.date(), card.ids().mapNotNull(byId::get))
        }.filter { it.entries.isNotEmpty() }
        val together = mutableMapOf<Pair<String, String>, MutableList<MediaEntry>>()
        val old = eligible.filter { it.date > 0 && java.time.Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate().isBefore(today.minusDays(30)) }
        for (photo in old) {
            val present = named.filter { photo.id in it.third }.take(6)
            for (i in present.indices) {
                for (j in i + 1 until present.size) {
                    val key = listOf(present[i].first, present[j].first).sorted().let { it[0] to it[1] }
                    together.getOrPut(key) { mutableListOf() }.add(photo)
                }
            }
        }
        val pairs = together.entries.filter { (_, photos) -> photos.map { java.time.Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }.distinct().size >= 2 && photos.size >= 3 }.sortedByDescending { it.value.maxOf { photo -> photo.date } }.take(2).map { (ids, photos) ->
            val names = named.filter { it.first == ids.first || it.first == ids.second }.joinToString(" & ") { it.second }
            val entries = photos.distinctBy { it.id }.sortedByDescending { it.date }.take(30)
            PhotoMemory("together:${ids.first}:${ids.second}", 0, java.time.Instant.ofEpochMilli(entries.first().date).atZone(zone).toLocalDate(), entries, names)
        }
        pairs + dateCards
    }.value
}

@Composable
internal fun PhotoMemories(cards: List<PhotoMemory>, model: DriveViewModel, open: (PhotoMemory) -> Unit) {
    if (cards.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp)) {
        Text(label("Memories", "স্মৃতি"), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(cards, key = { it.key }) { card ->
                val title = card.title ?: if (card.years > 0) label(if (card.years == 1) "1 year ago" else "${card.years} years ago", "${card.years} বছর আগে") else label("A day to remember", "মনে রাখার একটি দিন")
                Box(Modifier.width(152.dp).height(168.dp).clip(RoundedCornerShape(26.dp)).clickable { open(card) }) {
                    MediaThumbnail(card.entries.first(), model, Modifier.fillMaxSize(), pixels = 384)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(card.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())), color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PhotoSectionPills(section: String, choose: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .94f), shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("All", "Photos", "Videos", "Favorites").forEach { key ->
                FilterChip(section == key, { choose(key) }, label = { Text(sectionLabel(key)) }, shape = RoundedCornerShape(28.dp), border = null, colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = MaterialTheme.colorScheme.primaryContainer))
            }
        }
    }
}
