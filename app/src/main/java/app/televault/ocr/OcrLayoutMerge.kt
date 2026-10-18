package app.televault.ocr

/** Keep complementary label words from sparse layout without duplicating the same pixels. */
internal fun mergeOcrLayouts(primary: OcrText, sparse: OcrText): OcrText {
    if (primary.width != sparse.width || primary.height != sparse.height) return primary
    if (primary.words.isEmpty()) return if (sparse.words.isNotEmpty()) sparse else primary
    val combined = primary.words.toMutableList()
    var changed = false
    fun overlaps(a: OcrWord, b: OcrWord): Boolean {
        val area = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0).toLong() * (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val smaller = minOf((a.right - a.left).toLong() * (a.bottom - a.top), (b.right - b.left).toLong() * (b.bottom - b.top))
        return smaller > 0 && area.toDouble() / smaller >= .6
    }
    for (word in sparse.words) {
        if (combined.size == 4096)break
        val conflicts = combined.filter { overlaps(it, word) }
        if (conflicts.isEmpty()) {
            combined += word
            changed = true
        } else if (conflicts.size == 1 && word.confidence >= 45 && word.confidence >= conflicts.first().confidence + 12) {
            combined[combined.indexOf(conflicts.first())] = word
            changed = true
        }
    }
    if (!changed)return primary
    // Build physical lines first; RTL scripts retain their natural horizontal reading direction.
    val remaining = combined.sortedWith(compareBy<OcrWord> { it.top }.thenBy { it.left }).toMutableList()
    val ordered = mutableListOf<OcrWord>()
    while (remaining.isNotEmpty()) {
        val seed = remaining.removeAt(0)
        val center = (seed.top + seed.bottom) / 2
        val line = remaining.filter { center in it.top..it.bottom }.toMutableList()
        remaining.removeAll(line.toSet())
        line += seed
        val rtl = line.sumOf { it.text.count { ch -> Character.getDirectionality(ch) == Character.DIRECTIONALITY_RIGHT_TO_LEFT || Character.getDirectionality(ch) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC } } > line.sumOf { it.text.count { ch -> Character.getDirectionality(ch) == Character.DIRECTIONALITY_LEFT_TO_RIGHT } }
        ordered += if (rtl) line.sortedByDescending { it.right } else line.sortedBy { it.left }
    }
    return OcrText(ordered.joinToString(" ") { it.text }.take(64000), ordered, primary.width, primary.height)
}
