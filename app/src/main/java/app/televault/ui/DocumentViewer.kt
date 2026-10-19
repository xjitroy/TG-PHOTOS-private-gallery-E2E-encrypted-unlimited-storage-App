package app.televault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.televault.DriveViewModel
import app.televault.data.DocumentPreviews
import app.televault.data.DocumentText
import java.io.Closeable
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native seekable PDF pages; no plaintext temporary file, WebView, JavaScript or network viewer. */
private class PdfSession(context: Context, private val reads: app.televault.data.MediaReadSession) : Closeable {
    private val descriptor = reads.descriptor(context)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Throwable) {
        descriptor.close()
        throw e
    }
    private val mutex = Mutex()
    private var closed = false
    val count = renderer.pageCount
    suspend fun render(index: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed)
            renderer.openPage(index).use { page ->
                val scale = minOf(1400.0 / page.width.coerceAtLeast(1), sqrt(2000000.0 / (page.width.toDouble() * page.height).coerceAtLeast(1.0)))
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } catch (failure: Throwable) {
                    bitmap.recycle()
                    throw failure
                }
            }
        }
    }
    override fun close() {
        reads.close()
        cleanup.launch {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    renderer.close()
                    descriptor.close()
                }
            }
        }
    }
    private companion object {
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

@Composable internal fun DocumentViewer(source: Uri, item: MediaEntry, model: DriveViewModel, openWith: () -> Unit) {
    if (item.mime == "application/pdf" || item.name.endsWith(".pdf", true)) {
        PdfDocument(source, item, model, openWith)
    } else if (DocumentPreviews.supported(item.name, item.mime)) {
        TextDocument(source, item, model, openWith)
    } else {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            Text(label("Preview unavailable for this format", "এই ফরম্যাটের প্রিভিউ নেই"), Modifier.padding(vertical = 12.dp))
            OutlinedButton(openWith) { Text(label("Open with another app", "অন্য অ্যাপে খুলুন")) }
        }
    }
}

@Composable private fun PdfDocument(source: Uri, item: MediaEntry, model: DriveViewModel, openWith: () -> Unit) {
    var session by remember(source) { mutableStateOf<PdfSession?>(null) }
    var error by remember(source) { mutableStateOf(false) }
    var retry by remember(source) { mutableIntStateOf(0) }
    var scale by remember(source) { mutableFloatStateOf(1f) }
    val context = LocalContext.current
    val current = session
    val reads = remember(source, retry) { app.televault.data.MediaReadSession.open(context, source) }
    DisposableEffect(reads) { onDispose { reads.close() } }
    LaunchedEffect(source, retry) {
        error = false
        var created: PdfSession? = null
        try {
            withContext(Dispatchers.IO) { created = PdfSession(context, reads) }
            ensureActive()
            session = created
            created = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            error = true
        } catch (_: Exception) {
            error = true
        } finally {
            created?.close()
        }
    }
    DisposableEffect(current) { onDispose { current?.close() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(current?.let { "PDF · ${it.count} " + label("pages", "পৃষ্ঠা") } ?: "PDF", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton({ scale = (scale - .25f).coerceAtLeast(1f) }) { Text("−") }
            Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            TextButton({ scale = (scale + .25f).coerceAtMost(2.5f) }) { Text("+") }
        }
        when {
            error -> DocumentError({ retry++ }, openWith)
            current == null -> Text(label("Opening document…", "ডকুমেন্ট খুলছে…"), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(current.count, key = { it }) { index -> PdfPage(current, source, item, index, scale, model) }
            }
        }
    }
}

@Composable private fun PdfPage(session: PdfSession, source: Uri, item: MediaEntry, index: Int, scale: Float, model: DriveViewModel) {
    val key = "pdf:${item.id}:${item.local?.fingerprint ?: item.cloud?.hash}:$index"
    var bitmap by remember(source, index) { mutableStateOf(model.photoPreviews.get(key)?.bitmap) }
    var error by remember(source, index) { mutableStateOf(false) }
    var retry by remember(source, index) { mutableIntStateOf(0) }
    LaunchedEffect(source, index, retry) {
        if (bitmap != null) return@LaunchedEffect
        val epoch = model.photoPreviews.epoch()
        try {
            var frame: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    frame = session.render(index)
                    frame?.let { model.photoPreviews.remember(key, it, it.width, it.height, 0, epoch) }
                }
                ensureActive()
                bitmap = frame
                frame = null
            } finally {
                frame?.let { abandoned -> app.televault.data.MediaReadSession.dispose { abandoned.recycle() } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            error = true
        } catch (_: Exception) {
            error = true
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("${index + 1}", Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall)
        val image = bitmap
        when {
            image != null -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val pageWidth = maxWidth * scale
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Image(image.asImageBitmap(), label("PDF page", "PDF পৃষ্ঠা") + " ${index + 1}", Modifier.width(pageWidth).aspectRatio(image.width.toFloat() / image.height), contentScale = ContentScale.FillWidth)
                }
            }
            error -> TextButton({
                error = false
                retry++
            }) { Text(label("Page unavailable · retry", "পৃষ্ঠা পাওয়া যায়নি · আবার চেষ্টা করুন")) }
            else -> AppSurface(Modifier.fillMaxWidth().height(200.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Box(contentAlignment = Alignment.Center) { Text(label("Page", "পৃষ্ঠা") + " ${index + 1}", style = MaterialTheme.typography.labelSmall) } }
        }
    }
}

@Composable private fun TextDocument(source: Uri, item: MediaEntry, model: DriveViewModel, openWith: () -> Unit) {
    val key = "document:${item.id}:${item.local?.fingerprint ?: item.cloud?.hash}"
    var data by remember(key) { mutableStateOf<DocumentText?>(model.documentPreviews.get(key)) }
    var error by remember(key) { mutableStateOf(false) }
    var retry by remember(key) { mutableIntStateOf(0) }
    var sheet by remember(key) { mutableIntStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(key, source, retry) {
        if (data != null) return@LaunchedEffect
        val epoch = model.documentPreviews.epoch()
        try {
            val loaded = DocumentPreviews.read(context, source, item.name)
            ensureActive()
            model.documentPreviews.put(key, loaded, epoch)
            data = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            error = true
        } catch (_: Exception) {
            error = true
        }
    }
    val loaded = data
    when {
        error -> DocumentError({
            error = false
            retry++
        }, openWith)
        loaded == null -> Text(label("Opening document…", "ডকুমেন্ট খুলছে…"), Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall)
        else -> Column(Modifier.fillMaxSize()) {
            Text(label("Read-only preview · formulas and external links do not run", "শুধু প্রিভিউ · formula বা বাইরের লিংক চালু হয় না"), Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
            if (loaded.limited) Text(label("Preview limited to keep your phone fast. Open with for the complete document.", "ফোন দ্রুত রাখতে সীমিত প্রিভিউ। পুরো ডকুমেন্টের জন্য অন্য অ্যাপে খুলুন।"), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
            if (loaded.sheets.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    loaded.sheets.forEachIndexed { index, page -> FilterChip(sheet == index, { sheet = index }, label = { Text(page.name) }, modifier = Modifier.padding(end = 6.dp)) }
                }
                val selected = loaded.sheets.getOrNull(sheet) ?: loaded.sheets.first()
                Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    LazyColumn(Modifier.width(150.dp * (selected.rows.maxOfOrNull { it.size } ?: 1).coerceIn(1, 64)), contentPadding = PaddingValues(8.dp)) {
                        item {
                            Row {
                                repeat((selected.rows.maxOfOrNull { it.size } ?: 1).coerceIn(1, 64)) { index ->
                                    val title = (if (index >= 26) ('A'.code + index / 26 - 1).toChar().toString() else "") + ('A'.code + index % 26).toChar()
                                    Text(title, Modifier.width(150.dp).padding(8.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        itemsIndexed(selected.rows) { index, row ->
                            AppSurface(color = if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface) {
                                Row { row.forEach { cell -> SelectionContainer(Modifier.width(150.dp).padding(8.dp)) { Text(cell, style = MaterialTheme.typography.bodySmall) } } }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) { item { SelectionContainer { Text(loaded.text, style = MaterialTheme.typography.bodyMedium) } } }
            }
        }
    }
}

@Composable private fun DocumentError(retry: () -> Unit, openWith: () -> Unit) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label("Preview could not open. The document may be protected, too large or unsupported.", "প্রিভিউ খোলা যায়নি। ডকুমেন্টটি সুরক্ষিত, বেশি বড় বা অসমর্থিত হতে পারে।"))
        TextButton(retry) { Text(label("Retry", "আবার চেষ্টা করুন")) }
        OutlinedButton(openWith) { Text(label("Open with another app", "অন্য অ্যাপে খুলুন")) }
    }
}
