package app.televault.ocr

import android.graphics.Bitmap
import android.graphics.Matrix
import com.googlecode.tesseract.android.TessBaseAPI

internal object OcrScript {
    init {
        System.loadLibrary("jpeg")
        System.loadLibrary("pngx")
        System.loadLibrary("leptonica")
        System.loadLibrary("tesseract")
        System.loadLibrary("tgocrscript")
    }
    external fun nativeScript(directory: String, image: Bitmap): String?
}

/** Only authorized normal-photo pixels enter here; caller owns and wipes them. */
internal class OcrEngine(private val models: OcrModels) : AutoCloseable {
    private val recognizer = TessBaseAPI()
    private var language = ""
    suspend fun read(image: Bitmap, thorough: Boolean = false): OcrText {
        models.prepare(false)
        val script = OcrScript.nativeScript(java.io.File(models.directory, "tessdata").absolutePath, image)?.lineSequence()?.firstOrNull()
        val selected = if (thorough) {
            "ben+hin+eng+mar+tam+tel+ara+spa"
        } else {
            when (script) {
                "Bengali" -> "ben+eng"
                "Devanagari" -> "hin+mar+eng"
                "Tamil" -> "tam+eng"
                "Telugu" -> "tel+eng"
                "Arabic" -> "ara+eng"
                "Latin" -> "eng+spa"
                else -> "ben+hin+eng"
            }
        }
        if (language != selected) {
            check(recognizer.init(models.directory.absolutePath + "/", selected, TessBaseAPI.OEM_LSTM_ONLY))
            recognizer.setVariable("debug_file", "/dev/null")
            recognizer.setVariable("tessedit_write_images", "0")
            recognizer.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO_OSD)
            language = selected
        }
        recognizer.setImage(image)
        return try {
            val text = recognizer.getUTF8Text().orEmpty().take(64000).trim()
            val words = mutableListOf<OcrWord>()
            val iterator = recognizer.resultIterator
            if (iterator != null) {
                iterator.begin()
                do {
                    val value = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD).orEmpty().trim().take(256)
                    val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (value.isNotEmpty() && box.size == 4 && words.size < 4096) words += OcrWord(value, box[0].coerceIn(0, image.width), box[1].coerceIn(0, image.height), box[2].coerceIn(box[0].coerceIn(0, image.width), image.width), box[3].coerceIn(box[1].coerceIn(0, image.height), image.height))
                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD) && words.size < 4096)
                iterator.delete()
            }
            OcrText(text, words, image.width, image.height)
        } finally {
            recognizer.clear()
        }
    }
    override fun close() {
        recognizer.recycle()
    }
}
