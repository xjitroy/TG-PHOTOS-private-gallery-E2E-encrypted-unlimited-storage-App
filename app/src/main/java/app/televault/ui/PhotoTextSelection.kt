package app.televault.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
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
    var photo by remember { mutableStateOf(selectPhoto) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var copied by remember { mutableStateOf(false) }
    val slot = remember(uri) { vaultImageSlot() }
    val bitmap by produceState<android.graphics.Bitmap?>(null, slot) {
        withContext(Dispatchers.IO) { decodeOcrPhoto(context, uri)?.let(slot::offer) }
        value = slot.publish()
    }
    DisposableEffect(slot) { onDispose { slot.retire() } }
    val chosen = remember(selected, result) { result.words.filterIndexed { i, _ -> i in selected }.joinToString(" ") { it.text } }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            Column {
                TopAppBar(title = { Text(label("Text in photo", "ছবির লেখা")) }, navigationIcon = { IconButton(close) { Icon(Icons.Outlined.Close, label("Close", "বন্ধ")) } }, windowInsets = WindowInsets(0, 0, 0, 0))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(photo, { photo = true }, label = { Text(label("On photo", "ছবিতে নির্বাচন")) })
                    FilterChip(!photo, { photo = false }, label = { Text(label("All text", "সমস্ত লেখা")) })
                }
                if (photo) {
                    Text(label("Tap words or drag to select. Use two fingers to move and zoom.", "শব্দে ট্যাপ করুন বা টেনে নির্বাচন করুন। দুই আঙুলে সরান ও বড় করুন।"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                    var viewport by remember { mutableStateOf(IntSize.Zero) }
                    var zoom by remember { mutableFloatStateOf(1f) }
                    var pan by remember { mutableStateOf(Offset.Zero) }
                    val accent = MaterialTheme.colorScheme.primary
                    Canvas(
                        Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }.pointerInput(result, viewport) {
                            fun pick(point: Offset) {
                                val scale = min(viewport.width.toFloat() / result.width, viewport.height.toFloat() / result.height) * zoom
                                if (scale <= 0)return
                                val left = (viewport.width - result.width * scale) / 2 + pan.x
                                val top = (viewport.height - result.height * scale) / 2 + pan.y
                                val x = (point.x - left) / scale
                                val y = (point.y - top) / scale
                                val index = result.words.indexOfFirst { x >= it.left - 3 && x <= it.right + 3 && y >= it.top - 3 && y <= it.bottom + 3 }
                                if (index >= 0)selected = selected + index
                            }
                            awaitEachGesture {
                                val first = awaitFirstDown(requireUnconsumed = false)
                                var moved = false
                                var two = false
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
                                        if (moved || pressed == 0)pick(point)
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
                            drawRect(accent.copy(alpha = if (i in selected) 1f else .45f), topLeft, extent, style = Stroke(if (i in selected) 2.dp.toPx() else 1.dp.toPx()))
                        }
                    }
                    if (chosen.isNotBlank()) Text(chosen, Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()).padding(16.dp), style = MaterialTheme.typography.bodyLarge)
                } else {
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) { Text(result.text.ifBlank { label("No readable text was found.", "পড়ার মতো লেখা পাওয়া যায়নি।") }, style = MaterialTheme.typography.bodyLarge) }
                }
                Text(if (copied) label("Copied · clipboard clears after 1 minute", "কপি হয়েছে · ১ মিনিট পরে ক্লিপবোর্ড পরিষ্কার হবে") else label("Check recognized text before using it.", "ব্যবহারের আগে লেখাটি যাচাই করুন।"), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ selected = emptySet() }, enabled = selected.isNotEmpty()) { Text(label("Clear selection", "নির্বাচন বাদ")) }
                    Button({
                        SensitiveClipboard.copy(context, if (photo && chosen.isNotBlank())chosen else result.text, "Photo text")
                        copied = true
                    }, Modifier.weight(1f), enabled = result.text.isNotBlank()) { Text(if (photo && selected.isNotEmpty()) label("Copy selected", "নির্বাচিত কপি") else label("Copy all", "সব কপি")) }
                }
            }
        }
    }
}
