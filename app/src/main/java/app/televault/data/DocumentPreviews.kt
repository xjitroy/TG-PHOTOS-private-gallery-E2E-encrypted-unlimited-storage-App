package app.televault.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import app.televault.domain.DelimitedPreview
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal data class DocumentSheet(val name: String, val rows: List<List<String>>)
internal data class DocumentText(val sheets: List<DocumentSheet> = emptyList(), val text: String = "", val limited: Boolean = false) {
    val weight: Int get() = text.length + sheets.sumOf { s -> s.name.length + s.rows.sumOf { r -> r.sumOf { it.length + 16 } } }
}

/** Preview text lives only in bounded RAM. Original cloud bytes use the ciphertext range cache. */
internal class DocumentPreviewCache {
    private data class Entry(val data: DocumentText, val time: Long)
    private val values = object : LruCache<String, Entry>(1000000) {
        override fun sizeOf(key: String, value: Entry) = value.data.weight.coerceAtLeast(1)
    }
    private var generation = 0L

    @Synchronized fun epoch() = generation

    @Synchronized fun get(key: String): DocumentText? {
        val value = values.get(key) ?: return null
        if (SystemClock.elapsedRealtime() - value.time >= 86400000L) {
            values.remove(key)
            return null
        }
        return value.data
    }

    @Synchronized fun put(key: String, data: DocumentText, epoch: Long) {
        if (generation == epoch && data.weight <= 500000) values.put(key, Entry(data, SystemClock.elapsedRealtime()))
    }

    @Synchronized fun clear() {
        generation++
        values.evictAll()
    }
}

internal object DocumentPreviews {
    fun supported(name: String, mime: String) = name.substringAfterLast('.').lowercase() in setOf("xlsx", "csv", "tsv", "txt", "md", "log", "json", "xml", "docx") || mime.startsWith("text/")
    suspend fun read(context: Context, uri: Uri, name: String): DocumentText = withContext(Dispatchers.IO) {
        val extension = name.substringAfterLast('.').lowercase()
        if (extension !in setOf("xlsx", "docx")) {
            checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { reader ->
                if (extension == "csv" || extension == "tsv") {
                    val result = DelimitedPreview.read(reader, if (extension == "tsv") '\t' else ',')
                    DocumentText(listOf(DocumentSheet(name, result.rows())), limited = result.limited())
                } else {
                    val output = StringBuilder()
                    val chars = CharArray(4096)
                    try {
                        while (output.length < 250000) {
                            coroutineContext.ensureActive()
                            val count = reader.read(chars, 0, minOf(chars.size, 250000 - output.length))
                            if (count < 0) break
                            output.append(chars, 0, count)
                        }
                        DocumentText(text = output.toString(), limited = output.length == 250000)
                    } finally {
                        chars.fill('\u0000')
                    }
                }
            }
        } else {
            readOffice(checkNotNull(context.contentResolver.openInputStream(uri)), extension)
        }
    }
    internal suspend fun readOffice(input: java.io.InputStream, extension: String): DocumentText {
        val selected = LinkedHashMap<String, ByteArray>()
        var inflated = 0L
        var entries = 0
        val buffer = ByteArray(16384)
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    check(++entries <= 2048) { "Too many document entries" }
                    val keep = if (extension == "docx") entry.name == "word/document.xml" else entry.name in setOf("xl/sharedStrings.xml", "xl/workbook.xml", "xl/_rels/workbook.xml.rels") || entry.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) && selected.keys.count { it.startsWith("xl/worksheets/") } < 10
                    val output = if (keep) ByteArrayOutputStream() else null
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        inflated += count
                        check(inflated <= 16L * 1024 * 1024) { "Document preview too large" }
                        if (output != null) {
                            check(output.size() + count <= 4 * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                    }
                    if (output != null) selected[entry.name] = output.toByteArray()
                }
            }
            if (extension == "docx") {
                val source = checkNotNull(selected["word/document.xml"])
                val text = StringBuilder()
                xml(source) { parser ->
                    if ((parser.eventType == XmlPullParser.TEXT || parser.eventType == XmlPullParser.ENTITY_REF) && text.length < 250000) text.append(parser.text.take(250000 - text.length))
                    if (parser.eventType == XmlPullParser.END_TAG && parser.name == "p" && text.length < 250000) text.append('\n')
                }
                return DocumentText(text = text.toString(), limited = text.length >= 250000)
            }
            val strings = ArrayList<String>()
            var shared = StringBuilder()
            var sharedChars = 0
            selected["xl/sharedStrings.xml"]?.let { bytes ->
                xml(bytes) { p ->
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "si") shared = StringBuilder()
                    if ((p.eventType == XmlPullParser.TEXT || p.eventType == XmlPullParser.ENTITY_REF) && shared.length < 4096) shared.append(p.text.take(4096 - shared.length))
                    if (p.eventType == XmlPullParser.END_TAG && p.name == "si" && strings.size < 10000) {
                        val value = shared.toString().take((250000 - sharedChars).coerceAtLeast(0))
                        strings += value
                        sharedChars += value.length
                    }
                }
            }
            val names = LinkedHashMap<String, String>()
            selected["xl/workbook.xml"]?.let { bytes ->
                xml(bytes) { p ->
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                        val relation = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                        if (relation != null) names[relation] = p.getAttributeValue(null, "name") ?: "Sheet"
                    }
                }
            }
            val sheetNames = LinkedHashMap<String, String>()
            selected["xl/_rels/workbook.xml.rels"]?.let { bytes ->
                xml(bytes) { p ->
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                        val target = p.getAttributeValue(null, "Target").orEmpty().removePrefix("/xl/")
                        val id = p.getAttributeValue(null, "Id")
                        if (target.matches(Regex("worksheets/sheet[0-9]+\\.xml")) && p.getAttributeValue(null, "TargetMode") != "External") names[id]?.let { sheetNames["xl/$target"] = it }
                    }
                }
            }
            var limited = names.size > 10 || strings.size >= 10000 || sharedChars >= 250000
            var cellChars = 0
            val sheets = selected.filterKeys { it.startsWith("xl/worksheets/") }.entries.sortedBy { it.key.substringAfterLast("sheet").substringBefore('.').toIntOrNull() ?: 0 }.map { entry ->
                val rows = ArrayList<List<String>>()
                var row = ArrayList<String>()
                var type = ""
                var value = StringBuilder()
                var inValue = false
                var inCell = false
                var skipCell = false
                xml(entry.value) { p ->
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "row" -> row = ArrayList()
                            "c" -> {
                                type = p.getAttributeValue(null, "t") ?: ""
                                value = StringBuilder()
                                inCell = true
                                val ref = p.getAttributeValue(null, "r").orEmpty()
                                var column = 0
                                for (ch in ref.takeWhile { it in 'A'..'Z' }.take(3)) column = column * 26 + ch.code - 'A'.code + 1
                                skipCell = column > 64 || rows.size >= 300
                                if (!skipCell) while (row.size < (column - 1).coerceAtMost(63)) row.add("")
                            }
                            "v", "t" -> inValue = inCell
                        }
                        XmlPullParser.TEXT, XmlPullParser.ENTITY_REF -> if (inValue && value.length < 4096) value.append(p.text.take(4096 - value.length))
                        XmlPullParser.END_TAG -> when (p.name) {
                            "v", "t" -> inValue = false
                            "c" -> {
                                if (!skipCell && row.size < 64 && rows.size < 300 && cellChars < 250000) {
                                    val raw = if (type == "s") strings.getOrNull(value.toString().toIntOrNull() ?: -1) ?: "…" else value.toString()
                                    val cell = raw.take((250000 - cellChars).coerceAtLeast(0))
                                    row += cell
                                    cellChars += cell.length
                                } else {
                                    limited = true
                                }
                                inCell = false
                            }
                            "row" -> if (rows.size < 300) rows += row.toList() else limited = true
                        }
                    }
                }
                DocumentSheet(sheetNames[entry.key] ?: entry.key.substringAfterLast('/'), rows)
            }
            check(sheets.isNotEmpty()) { "No worksheet available" }
            return DocumentText(sheets, limited = limited)
        } finally {
            buffer.fill(0)
            selected.values.forEach { it.fill(0) }
        }
    }
    private suspend fun xml(bytes: ByteArray, visit: (XmlPullParser) -> Unit) {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }.onFailure { check(!parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) }
        parser.setInput(bytes.inputStream(), "UTF-8")
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            coroutineContext.ensureActive()
            check(parser.eventType != XmlPullParser.DOCDECL) { "Document entities are not supported" }
            if (parser.eventType == XmlPullParser.ENTITY_REF) check(parser.text != null) { "Unknown document entity" }
            visit(parser)
            parser.nextToken()
        }
    }
}
