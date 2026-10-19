package app.televault.ui

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import app.televault.ocr.OcrText
import app.televault.ocr.decodeOcrPhoto
import app.televault.security.SensitiveClipboard
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoTextSelection(result: OcrText, uri: Uri, selectPhoto: Boolean, close: () -> Unit) {
    val context = LocalContext.current
    val shareTitle = label("Share selected text", "নির্বাচিত লেখা শেয়ার")
    var photo by remember(result, uri) { mutableStateOf(selectPhoto && result.words.isNotEmpty()) }
    var selected by remember(result, uri) { mutableStateOf(setOf<Int>()) }
    var anchor by remember { mutableIntStateOf(-1) }
    var edge by remember { mutableIntStateOf(-1) }
    var copied by remember { mutableStateOf(false) }
    val slot = remember(uri) { vaultImageSlot() }
    val bitmap by produceState<android.graphics.Bitmap?>(null, slot) {
        guardedProjection(Unit) {
            withContext(Dispatchers.IO) {
                decodeOcrPhoto(context, uri)?.let(slot::offer)
                Unit
            }
        }
        value = slot.publish()
    }
    DisposableEffect(slot) { onDispose { slot.retire() } }
    val chosen = remember(selected, result) { result.words.filterIndexed { i, _ -> i in selected }.joinToString(" ") { it.text } }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        AppSurface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(title = { Text(label("Text in photo", "ছবির লেখা")) }, navigationIcon = { IconButton(close) { Icon(Icons.Outlined.Close, label("Close", "বন্ধ")) } }, windowInsets = WindowInsets(0, 0, 0, 0))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(photo, { photo = true }, enabled = result.words.isNotEmpty(), label = { Text(label("On photo", "ছবিতে নির্বাচন")) })
                    FilterChip(!photo, { photo = false }, label = { Text(label("All text", "সমস্ত লেখা")) })
                }
                if (photo) {
                    Text(label("Tap words or drag to select. Use two fingers to move and zoom.", "শব্দে ট্যাপ করুন বা টেনে নির্বাচন করুন। দুই আঙুলে সরান ও বড় করুন।"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    var viewport by remember { mutableStateOf(IntSize.Zero) }
                    var zoom by remember { mutableFloatStateOf(1f) }
                    var pan by remember { mutableStateOf(Offset.Zero) }
                    val accent = androidx.compose.ui.graphics.Color(0xFF1A73E8)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Canvas(
                            Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }.pointerInput(result, viewport) {
                                fun wordAt(point: Offset): Int {
                                    val scale = min(viewport.width.toFloat() / result.width, viewport.height.toFloat() / result.height) * zoom
                                    if (scale <= 0)return -1
                                    val left = (viewport.width - result.width * scale) / 2 + pan.x
                                    val top = (viewport.height - result.height * scale) / 2 + pan.y
                                    val x = (point.x - left) / scale
                                    val y = (point.y - top) / scale
                                    return result.words.indexOfFirst { x >= it.left - 6 && x <= it.right + 6 && y >= it.top - 6 && y <= it.bottom + 6 }
                                }
                                fun select(point: Offset, start: Boolean) {
                                    val index = wordAt(point)
                                    if (index >= 0) {
                                        if (start || anchor < 0)anchor = index
                                        edge = index
                                        selected = (minOf(anchor, edge)..maxOf(anchor, edge)).toSet()
                                    }
                                }
                                awaitEachGesture {
                                    val first = awaitFirstDown(requireUnconsumed = false)
                                    var moved = false
                                    var two = false
                                    var handle = 0
                                    if (selected.isEmpty()) {
                                        anchor = -1
                                        edge = -1
                                    }
                                    if (selected.isNotEmpty()) {
                                        val scale = min(viewport.width.toFloat() / result.width, viewport.height.toFloat() / result.height) * zoom
                                        val origin = Offset((viewport.width - result.width * scale) / 2, (viewport.height - result.height * scale) / 2) + pan
                                        val firstWord = result.words[selected.min()]
                                        val lastWord = result.words[selected.max()]
                                        val startPoint = origin + Offset(firstWord.left * scale, firstWord.bottom * scale + 17.dp.toPx())
                                        val endPoint = origin + Offset(lastWord.right * scale, lastWord.bottom * scale + 17.dp.toPx())
                                        if ((first.position - startPoint).getDistance() < 32.dp.toPx()) {
                                            handle = -1
                                            anchor = selected.max()
                                        } else if ((first.position - endPoint).getDistance() < 32.dp.toPx()) {
                                            handle = 1
                                            anchor = selected.min()
                                        }
                                    }
                                    if (handle == 0) {
                                        anchor = -1
                                        select(first.position, true)
                                    }
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        if (pressed >= 2) {
                                            two = true
                                            val next = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                                            pan += event.calculatePan()
                                            zoom = next
                                            if (zoom == 1f)pan = Offset.Zero
                                        } else if (!two) {
                                            val point = event.changes.firstOrNull()?.position ?: first.position
                                            if ((point - first.position).getDistance() > viewConfiguration.touchSlop)moved = true
                                            if (handle != 0) {
                                                select(point - Offset(0f, 17.dp.toPx()), false)
                                            } else if (moved || pressed == 0) {
                                                if (!moved) {
                                                    select(point, true)
                                                } else {
                                                    if (anchor < 0)select(first.position, true)
                                                    select(point, false)
                                                }
                                            }
                                        }
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                        ) {
                            val scale = min(size.width / result.width, size.height / result.height) * zoom
                            val origin = Offset((size.width - result.width * scale) / 2, (size.height - result.height * scale) / 2) + pan
                            bitmap?.let { drawImage(it.asImageBitmap(), dstOffset = IntOffset(origin.x.toInt(), origin.y.toInt()), dstSize = IntSize((result.width * scale).toInt().coerceAtLeast(1), (result.height * scale).toInt().coerceAtLeast(1))) }
                            result.words.forEachIndexed { i, word ->
                                val topLeft = origin + Offset(word.left * scale, word.top * scale)
                                val extent = androidx.compose.ui.geometry.Size((word.right - word.left) * scale, (word.bottom - word.top) * scale)
                                if (i in selected)drawRect(accent.copy(alpha = .35f), topLeft, extent)
                                if (i in selected)drawRect(accent.copy(alpha = .8f), topLeft, extent, style = Stroke(1.dp.toPx()))
                            }
                            if (selected.isNotEmpty()) {
                                val a = result.words[selected.min()]
                                val b = result.words[selected.max()]
                                for (point in listOf(Offset(a.left * scale, a.bottom * scale), Offset(b.right * scale, b.bottom * scale))) {
                                    val at = origin + point
                                    drawLine(accent, at, at + Offset(0f, 14.dp.toPx()), 3.dp.toPx())
                                    drawCircle(accent, 7.dp.toPx(), at + Offset(0f, 17.dp.toPx()))
                                }
                            }
                        }
                        if (selected.isNotEmpty()) {
                            AppSurface(Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(8.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
                                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                                    TextButton({
                                        SensitiveClipboard.copy(context, chosen, "Photo text")
                                        copied = true
                                    }) { Text(label("Copy", "কপি"), color = MaterialTheme.colorScheme.inverseOnSurface) }
                                    TextButton({
                                        selected = result.words.indices.toSet()
                                        anchor = 0
                                        edge = result.words.lastIndex
                                    }) { Text(label("Select all", "সব নির্বাচন"), color = MaterialTheme.colorScheme.inverseOnSurface) }
                                    TextButton({
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, chosen))
                                        } catch (_: android.content.ActivityNotFoundException) { }
                                    }) { Text(label("Search", "সার্চ"), color = MaterialTheme.colorScheme.inverseOnSurface) }
                                    TextButton({
                                        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, chosen)
                                        try {
                                            context.startActivity(Intent.createChooser(intent, shareTitle))
                                        } catch (_: android.content.ActivityNotFoundException) { }
                                    }) { Text(label("Share", "শেয়ার"), color = MaterialTheme.colorScheme.inverseOnSurface) }
                                }
                            }
                        }
                    }
                    if (chosen.isNotBlank()) Text(chosen, Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()).padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                } else {
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) { Text(result.text.ifBlank { label("No readable text was found.", "পড়ার মতো লেখা পাওয়া যায়নি।") }, style = MaterialTheme.typography.bodyLarge) }
                }
                Text(if (copied) label("Copied · clipboard clears after 1 minute", "কপি হয়েছে · ১ মিনিট পরে ক্লিপবোর্ড পরিষ্কার হবে") else label("Check recognized text before using it.", "ব্যবহারের আগে লেখাটি যাচাই করুন।"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        selected = emptySet()
                        anchor = -1
                        edge = -1
                    }, enabled = selected.isNotEmpty()) { Text(label("Clear selection", "নির্বাচন বাদ")) }
                    Button({
                        SensitiveClipboard.copy(context, if (photo && chosen.isNotBlank())chosen else result.text, "Photo text")
                        copied = true
                    }, Modifier.weight(1f), enabled = result.text.isNotBlank()) { Text(if (photo && selected.isNotEmpty()) label("Copy selected", "নির্বাচিত কপি") else label("Copy all", "সব কপি")) }
                }
            }
        }
    }
}
