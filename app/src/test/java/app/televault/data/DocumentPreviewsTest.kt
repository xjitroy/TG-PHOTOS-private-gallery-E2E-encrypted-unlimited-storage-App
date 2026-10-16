package app.televault.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DocumentPreviewsTest {
    private fun archive(entries: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, xml) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun workbookRelationshipsAndSparseCellsUseOriginalSheetNames() = runBlocking {
        val bytes = archive(
            mapOf(
                "xl/sharedStrings.xml" to "<sst><si><t>First</t></si><si><r><t>Hello </t></r><r><t>world</t></r></si></sst>",
                "xl/workbook.xml" to "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Budget\" r:id=\"rId9\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"rId9\" Target=\"worksheets/sheet2.xml\"/></Relationships>",
                "xl/worksheets/sheet2.xml" to "<worksheet><sheetData><row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>1</v></c><c r=\"D1\"><f>1+2</f><v>3</v></c></row></sheetData></worksheet>"
            )
        )
        val preview = DocumentPreviews.readOffice(ByteArrayInputStream(bytes), "xlsx")
        assertEquals("Budget", preview.sheets.single().name)
        assertEquals(listOf("First", "", "Hello world", "3"), preview.sheets.single().rows.single())
        assertFalse(preview.limited)
    }

    @Test fun documentEntitiesAreRejectedAndNormalParagraphsWork() = runBlocking {
        val normal = archive(mapOf("word/document.xml" to "<document><p><t>Hello</t></p><p><t>World</t></p></document>"))
        assertEquals("Hello\nWorld\n", DocumentPreviews.readOffice(ByteArrayInputStream(normal), "docx").text)
        val unsafe = archive(mapOf("word/document.xml" to "<!DOCTYPE document [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><document><p>&secret;</p></document>"))
        var rejected = false
        try {
            DocumentPreviews.readOffice(ByteArrayInputStream(unsafe), "docx")
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("External entity documents must fail closed", rejected)
    }

    @Test fun compressedExpansionAndLargeCellPreviewsStayBounded() = runBlocking {
        val oversized = archive(mapOf("word/document.xml" to "<document>" + "x".repeat(4 * 1024 * 1024 + 1) + "</document>"))
        var rejected = false
        try {
            DocumentPreviews.readOffice(ByteArrayInputStream(oversized), "docx")
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        val rows = (1..400).joinToString("") { "<row><c r=\"A$it\" t=\"inlineStr\"><is><t>Value$it</t></is></c></row>" }
        val preview = DocumentPreviews.readOffice(ByteArrayInputStream(archive(mapOf("xl/worksheets/sheet1.xml" to "<worksheet>$rows</worksheet>"))), "xlsx")
        assertEquals(300, preview.sheets.single().rows.size)
        assertTrue(preview.limited)
    }
}
