package app.televault.people

import android.content.Context
import android.graphics.*
import app.televault.domain.FaceSimilarity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.tensorflow.lite.Interpreter

internal data class FaceSample(val embedding: FloatArray, val jpeg: ByteArray, val source: String = "", val contentHash: String = "") {
    fun wipe() {
        embedding.fill(0f)
        jpeg.fill(0)
    }
}

/** CPU-only bundled models; caller serializes access and owns all transient output. */
internal class FaceEngine(private val context: Context) : AutoCloseable {
    private fun model(name: String): ByteBuffer = context.assets.open("models/$name.tflite").use { stream ->
        val bytes = stream.readBytes()
        try {
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        } finally {
            bytes.fill(0)
        }
    }
    private val detector: Interpreter
    private val recognizer: Interpreter
    private data class Detection(val box: RectF, val points: FloatArray, val score: Float)
    init {
        val first = Interpreter(model("blazeface_short_range"), Interpreter.Options().setNumThreads(2))
        var second: Interpreter? = null
        try {
            second = Interpreter(model("mobile_facenet"), Interpreter.Options().setNumThreads(2))
            check(first.getInputTensor(0).shape().contentEquals(intArrayOf(1, 128, 128, 3)))
            check(second.getInputTensor(0).shape().contentEquals(intArrayOf(1, 3, 112, 112)))
            check(second.getOutputTensor(0).shape().contentEquals(intArrayOf(2, 128)))
            detector = first
            recognizer = second
        } catch (error: Throwable) {
            second?.close()
            first.close()
            throw error
        }
    }
    fun faces(source: Bitmap): List<FaceSample> {
        val owned = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, true) else null
        return try {
            detect(owned ?: source)
        } finally {
            owned?.let {
                it.eraseColor(0)
                it.recycle()
            }
        }
    }
    private fun detect(source: Bitmap): List<FaceSample> {
        val side = max(source.width, source.height).toFloat()
        val scale = 128f / side
        val dx = (128 - source.width * scale) / 2
        val dy = (128 - source.height * scale) / 2
        val image = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        Canvas(image).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(128 * 128)
        val input = ByteBuffer.allocateDirect(128 * 128 * 3 * 4).order(ByteOrder.nativeOrder())
        val boxes = Array(1) { Array(896) { FloatArray(16) } }
        val scores = Array(1) { Array(896) { FloatArray(1) } }
        val samples = mutableListOf<FaceSample>()
        try {
            image.getPixels(pixels, 0, 128, 0, 0, 128, 128)
            for (p in pixels) {
                input.putFloat(((p shr 16) and 255) / 127.5f - 1)
                input.putFloat(((p shr 8) and 255) / 127.5f - 1)
                input.putFloat((p and 255) / 127.5f - 1)
            }
            input.rewind()
            detector.runForMultipleInputsOutputs(arrayOf(input), mutableMapOf<Int, Any>(0 to boxes, 1 to scores))
            val candidates = mutableListOf<Detection>()
            var index = 0
            for (grid in intArrayOf(16, 8)) {
                for (y in 0 until grid) {
                    for (x in 0 until grid) {
                        repeat(if (grid == 16) 2 else 6) {
                            val row = boxes[0][index]
                            val score = 1f / (1f + exp(-scores[0][index][0].coerceIn(-80f, 80f)))
                            index++
                            if (score >= .75f && row.all { it.isFinite() }) {
                                val cx = row[0] + (x + .5f) * 128 / grid
                                val cy = row[1] + (y + .5f) * 128 / grid
                                val box = RectF((cx - row[2] / 2 - dx) / scale, (cy - row[3] / 2 - dy) / scale, (cx + row[2] / 2 - dx) / scale, (cy + row[3] / 2 - dy) / scale)
                                if (box.width() >= 24 && box.height() >= 24 && box.centerX() in 0f..source.width.toFloat() && box.centerY() in 0f..source.height.toFloat()) {
                                    val points = FloatArray(12) { k -> (row[4 + k] + (if (k % 2 == 0) (x + .5f) * 128 / grid - dx else (y + .5f) * 128 / grid - dy)) / scale }
                                    candidates += Detection(box, points, score)
                                }
                            }
                        }
                    }
                }
            }
            val kept = mutableListOf<Detection>()
            for (candidate in candidates.sortedByDescending { it.score }) {
                if (kept.none { overlap(it.box, candidate.box) > .3f }) kept += candidate
                if (kept.size == 10) break
            }
            for (face in kept) {
                val crop = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
                try {
                    val from = floatArrayOf(face.points[0], face.points[1], face.points[2], face.points[3], face.points[4], face.points[5])
                    val align = Matrix()
                    if (!align.setPolyToPoly(from, 0, floatArrayOf(38f, 52f, 74f, 52f, 56f, 72f), 0, 3)) continue
                    Canvas(crop).drawBitmap(source, align, Paint(Paint.FILTER_BITMAP_FLAG))
                    val embedding = embedding(crop)
                    val jpeg = ByteArrayOutputStream().use {
                        crop.compress(Bitmap.CompressFormat.JPEG, 75, it)
                        it.toByteArray()
                    }
                    if (jpeg.size <= 24000) {
                        samples += FaceSample(embedding, jpeg)
                    } else {
                        embedding.fill(0f)
                        jpeg.fill(0)
                    }
                } finally {
                    crop.eraseColor(0)
                    crop.recycle()
                }
            }
            return samples
        } catch (error: Throwable) {
            samples.forEach { it.wipe() }
            throw error
        } finally {
            image.eraseColor(0)
            image.recycle()
            pixels.fill(0)
            input.clear()
            while (input.remaining() >= 4) input.putInt(0)
            boxes[0].forEach { it.fill(0f) }
            scores[0].forEach { it.fill(0f) }
        }
    }
    private fun embedding(crop: Bitmap): FloatArray {
        val pixels = IntArray(112 * 112)
        crop.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        val input = ByteBuffer.allocateDirect(3 * 112 * 112 * 4).order(ByteOrder.nativeOrder())
        val output = Array(2) { FloatArray(128) }
        try {
            for (shift in intArrayOf(16, 8, 0)) for (pixel in pixels) input.putFloat(((pixel shr shift) and 255) / 255f)
            input.rewind()
            val second = input.duplicate().order(ByteOrder.nativeOrder())
            second.rewind()
            recognizer.runForMultipleInputsOutputs(arrayOf(input, second), mutableMapOf<Int, Any>(0 to output))
            return FaceSimilarity.normalized(output[0])
        } finally {
            pixels.fill(0)
            output.forEach { it.fill(0f) }
            input.clear()
            while (input.remaining() >= 4) input.putInt(0)
        }
    }
    private fun overlap(a: RectF, b: RectF): Float {
        val intersect = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        return intersect / (a.width() * a.height() + b.width() * b.height() - intersect).coerceAtLeast(1f)
    }
    override fun close() {
        detector.close()
        recognizer.close()
    }
}
