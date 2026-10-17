package app.televault.ocr
import org.json.JSONArray
import org.json.JSONObject
internal data class OcrWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class OcrText(val text: String, val words: List<OcrWord>, val width: Int, val height: Int) {
    fun json(): JSONObject = JSONObject().put("text", text).put("width", width).put("height", height).put("words", JSONArray(words.map { JSONArray(listOf(it.text, it.left, it.top, it.right, it.bottom)) }))
    companion object {
        fun read(body: JSONObject): OcrText {
            val text = body.optString("text")
            check(text.length <= 64000)
            val width = body.optInt("width")
            val height = body.optInt("height")
            check(width in 1..2400 && height in 1..2400)
            val rows = body.optJSONArray("words") ?: JSONArray()
            check(rows.length() <= 4096)
            val words = (0 until rows.length()).map { i ->
                rows.getJSONArray(i).let { row ->
                    val word = OcrWord(row.getString(0), row.getInt(1), row.getInt(2), row.getInt(3), row.getInt(4))
                    check(word.text.length <= 256 && word.left in 0..width && word.right in word.left..width && word.top in 0..height && word.bottom in word.top..height)
                    word
                }
            }
            return OcrText(text, words, width, height)
        }
        val EMPTY = OcrText("", emptyList(), 1, 1)
    }
}
