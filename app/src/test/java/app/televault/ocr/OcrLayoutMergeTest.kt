package app.televault.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrLayoutMergeTest {
    private fun word(text: String, x: Int, y: Int) = OcrWord(text, x, y, x + 40, y + 20)

    @Test fun sparseLabelsJoinDocumentTextWithoutDuplicatePixels() {
        val a = OcrText("Brand", listOf(word("Brand", 10, 10)), 300, 200)
        val b = OcrText("Brand 200g", listOf(word("Brand", 11, 10), word("200g", 100, 80)), 300, 200)
        val merged = mergeOcrLayouts(a, b)
        assertEquals(listOf("Brand", "200g"), merged.words.map { it.text })
        assertEquals("Brand 200g", merged.text)
    }

    @Test fun conflictingLayoutKeepsPrimaryReadingAndParagraphs() {
        val a = OcrText("সঠিক\nলেখা", listOf(word("সঠিক", 10, 10)), 300, 200)
        val b = OcrText("wrong", listOf(word("wrong", 10, 10)), 300, 200)
        assertEquals(a, mergeOcrLayouts(a, b))
        assertEquals(a, mergeOcrLayouts(a, b.copy(width = 400)))
    }

    @Test fun mixedLinesUseTheirOwnReadingDirection() {
        val a = OcrText("A", listOf(word("A", 10, 10)), 300, 200)
        val b = OcrText("", listOf(word("B", 100, 10), word("سلام", 100, 80), word("عالم", 10, 80)), 300, 200)
        assertEquals(listOf("A", "B", "سلام", "عالم"), mergeOcrLayouts(a, b).words.map { it.text })
    }

    @Test fun blankAutoLayoutCanUseSparseText() {
        val a = OcrText("", emptyList(), 300, 200)
        val b = OcrText("Label", listOf(word("Label", 10, 10)), 300, 200)
        assertEquals(b, mergeOcrLayouts(a, b))
    }

    @Test fun confidentIndianLabelCanReplaceWeakLatinGuessAtTheSamePixels() {
        val a = OcrText("noise", listOf(word("noise", 10, 10).copy(confidence = 20f)), 300, 200)
        val b = OcrText("বাংলা", listOf(word("বাংলা", 10, 10).copy(confidence = 90f)), 300, 200)
        assertEquals("বাংলা", mergeOcrLayouts(a, b).text)
    }
}
