package app.televault.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.domain.MediaCategories
import app.televault.domain.MediaCategories.Category
import kotlinx.coroutines.launch

internal data class MediaFilterOptions(val category: Category = Category.ALL, val hidden: Int = 0, val clean: Boolean = false) {
    val active: Boolean get() = category != Category.ALL || hidden != 0 || clean
    fun allows(item: MediaEntry) = MediaCategories.visible(item.mediaCategories, item.mime, category, hidden, clean)
}
internal data class MediaFilterControls(val options: MediaFilterOptions, val select: (Category) -> Unit, val clean: (Boolean) -> Unit, val hide: (Category, Boolean) -> Unit, val reset: () -> Unit)

@Composable
internal fun rememberMediaFilters(model: DriveViewModel?, changed: () -> Unit): MediaFilterControls {
    var selected by rememberSaveable { mutableStateOf("ALL") }
    val cleanFlow = remember(model) { model?.preferences?.cleanMedia ?: kotlinx.coroutines.flow.MutableStateFlow(false) }
    val hiddenFlow = remember(model) { model?.preferences?.hiddenMediaCategories ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>()) }
    val clean by cleanFlow.collectAsStateWithLifecycle(initialValue = false)
    val hidden by hiddenFlow.collectAsStateWithLifecycle(initialValue = emptySet())
    val scope = rememberCoroutineScope()
    val options = remember(selected, hidden, clean) { MediaFilterOptions(MediaCategories.category(selected), MediaCategories.hiddenMask(hidden), clean) }
    return MediaFilterControls(options, {
        selected = it.name
        changed()
    }, { enabled ->
        changed()
        scope.launch { model?.preferences?.setCleanMedia(enabled) }
    }, { category, hide ->
        changed()
        scope.launch { model?.preferences?.setMediaCategoryHidden(category.name, hide) }
    }, {
        selected = "ALL"
        changed()
        scope.launch { model?.preferences?.resetMediaFilters() }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaFilterBar(controls: MediaFilterControls, modifier: Modifier = Modifier) {
    var sheet by rememberSaveable { mutableStateOf(false) }
    val options = controls.options
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(options.category != Category.ALL || options.hidden != 0, { sheet = true }, label = { Text(if (options.category == Category.ALL) label("Categories", "ক্যাটাগরি") else mediaCategoryLabel(options.category)) }, leadingIcon = { Icon(Icons.Outlined.FilterAlt, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) })
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label("Clean view", "ক্লিন ভিউ"), style = MaterialTheme.typography.labelLarge)
            val cleanDescription = label("Clean view", "ক্লিন ভিউ")
            Switch(options.clean, controls.clean, Modifier.semantics { contentDescription = cleanDescription })
        }
        FilterChip(options.category == Category.MOVIES, { controls.select(if (options.category == Category.MOVIES) Category.ALL else Category.MOVIES) }, label = { Text(mediaCategoryLabel(Category.MOVIES)) })
    }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(label("Category filters", "ক্যাটাগরি ফিল্টার"), style = MaterialTheme.typography.titleLarge)
                Text(label("Change what you see. Background backup keeps running. Tap the eye to hide a category.", "শুধু প্রদর্শন বদলাবে। ব্যাকগ্রাউন্ড ব্যাকআপ চলবে। ক্যাটাগরি লুকাতে চোখে ট্যাপ করুন।"), style = MaterialTheme.typography.bodySmall)
                if (options.clean) Text(label("Clean view hides clearly identified screenshots, screen recordings and social PNG graphics. Ordinary videos, movies, documents and uncertain photos remain. No files are deleted.", "ক্লিন ভিউতে শনাক্ত স্ক্রিনশট, স্ক্রিন রেকর্ডিং ও সোশ্যাল PNG গ্রাফিক্স লুকাবে। সাধারণ ভিডিও, মুভি, ডকুমেন্ট ও অনিশ্চিত ছবি থাকবে। কোনো ফাইল মুছবে না।"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                items(Category.entries, key = { it.name }) { category ->
                    val hidden = options.hidden and category.mask != 0
                    ListItem(
                        headlineContent = { Text(mediaCategoryLabel(category)) },
                        supportingContent = if (hidden) ({ Text(label("Hidden from view", "প্রদর্শন থেকে লুকানো")) }) else null,
                        leadingContent = { Icon(if (options.category == category) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked, null) },
                        trailingContent = if (category != Category.ALL) ({ IconButton({ controls.hide(category, !hidden) }) { Icon(if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, label(if (hidden) "Show category" else "Hide category", if (hidden) "ক্যাটাগরি দেখান" else "ক্যাটাগরি লুকান") + ": " + mediaCategoryLabel(category)) } }) else null,
                        modifier = Modifier.selectable(options.category == category, role = Role.RadioButton) {
                            controls.select(category)
                            sheet = false
                        }
                    )
                }
                item {
                    TextButton({
                        controls.reset()
                        sheet = false
                    }, Modifier.padding(horizontal = 16.dp)) { Text(label("Reset category filters", "ক্যাটাগরি ফিল্টার রিসেট")) }
                }
            }
        }
    }
}

@Composable
internal fun mediaCategoryLabel(category: Category): String = when (category) {
    Category.ALL -> label("All categories", "সব ক্যাটাগরি")
    Category.MOVIES -> label("Movies · 60 min+", "মুভি · ৬০ মিনিট+")
    Category.CAMERA -> label("Camera", "ক্যামেরা")
    Category.DOCUMENTS -> label("Documents & scans", "ডকুমেন্ট ও স্ক্যান")
    Category.SCREENSHOTS -> label("Screenshots", "স্ক্রিনশট")
    Category.SCREEN_RECORDINGS -> label("Screen recordings", "স্ক্রিন রেকর্ডিং")
    Category.INSTAGRAM -> "Instagram"
    Category.WHATSAPP -> "WhatsApp"
    Category.TELEGRAM -> "Telegram"
    Category.FACEBOOK -> "Facebook / Messenger"
    Category.DOWNLOADS -> label("Downloads", "ডাউনলোড")
    Category.PNG -> label("PNG images", "PNG ছবি")
    Category.OTHER -> label("Other", "অন্যান্য")
}
