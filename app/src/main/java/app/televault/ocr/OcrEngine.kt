package app.televault.ocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.ensureActive

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
    private var recognizer: TessBaseAPI? = null
    private var language = ""
    private var lastConfidence = 0
    suspend fun read(image: Bitmap, thorough: Boolean = false): OcrText {
        models.prepare(false)
        val script = try {
            OcrScript.nativeScript(java.io.File(models.directory, "tessdata").absolutePath, image)?.lineSequence()?.firstOrNull()
        } catch (_: LinkageError) {
            null
        } catch (_: RuntimeException) {
            null
        }
        var best = OcrText.EMPTY
        var bestConfidence = -1
        for ((pass, selected) in app.televault.domain.OcrLanguagePolicy.candidates(script, thorough).withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            var result = readLanguage(image, selected, TessBaseAPI.PageSegMode.PSM_AUTO_OSD)
            var confidence = lastConfidence
            if (bestConfidence < 0 && (thorough || confidence < 75 || result.words.size < 12)) {
                val sparse = readLanguage(image, selected, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                if (lastConfidence >= 35) result = mergeOcrLayouts(result, sparse)
                confidence = maxOf(confidence, lastConfidence)
            }
            if (result.text.isNotBlank() && (confidence >= 35 || best.text.isBlank())) {
                best = if (best.words.isEmpty()) result else mergeOcrLayouts(best, result)
                bestConfidence = maxOf(bestConfidence, confidence)
            }
            if (!thorough && pass + 1 >= app.televault.domain.OcrLanguagePolicy.candidates(script, false).size && bestConfidence >= 65 && best.words.isNotEmpty()) break
        }
        return best
    }
    private fun readLanguage(image: Bitmap, selected: String, mode: Int): OcrText {
        if (language != selected || recognizer == null) {
            recognizer?.recycle()
            recognizer = null
            language = ""
            val fresh = TessBaseAPI()
            try {
                check(fresh.init(models.directory.absolutePath + "/", selected, TessBaseAPI.OEM_LSTM_ONLY))
                fresh.setVariable("debug_file", "/dev/null")
                fresh.setVariable("tessedit_write_images", "0")
                fresh.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO_OSD)
                recognizer = fresh
                language = selected
            } catch (error: Throwable) {
                fresh.recycle()
                throw error
            }
        }
        val api = checkNotNull(recognizer)
        api.setPageSegMode(mode)
        api.setImage(image)
        return try {
            val text = api.getUTF8Text().orEmpty().take(64000).trim()
            val words = mutableListOf<OcrWord>()
            val iterator = api.resultIterator
            if (iterator != null) {
                try {
                    iterator.begin()
                    do {
                        val value = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD).orEmpty().trim().take(256)
                        val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                        if (value.isNotEmpty() && box.size == 4 && words.size < 4096) words += OcrWord(value, box[0].coerceIn(0, image.width), box[1].coerceIn(0, image.height), box[2].coerceIn(box[0].coerceIn(0, image.width), image.width), box[3].coerceIn(box[1].coerceIn(0, image.height), image.height), iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD).takeIf { it.isFinite() }?.coerceIn(0f, 100f) ?: 0f)
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD) && words.size < 4096)
                } finally {
                    iterator.delete()
                }
            }
            lastConfidence = api.meanConfidence()
            OcrText(text, words, image.width, image.height)
        } finally {
            api.clear()
        }
    }
    override fun close() {
        recognizer?.recycle()
        recognizer = null
        language = ""
    }
}
