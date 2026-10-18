package app.televault.people

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.*
import app.televault.domain.FaceAlignment
import app.televault.domain.FaceSimilarity
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import kotlin.math.*

internal data class FaceSample(val embedding: FloatArray, val jpeg: ByteArray, val source: String = "", val contentHash: String = "") {
    fun wipe() {
        embedding.fill(0f)
        jpeg.fill(0)
    }
}

/** YuNet detection + five-point SFace recognition, CPU only, using verified downloaded models. */
internal class FaceEngine(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val detector: OrtSession
    private val recognizer: OrtSession
    private data class Detection(val box: RectF, val points: FloatArray, val score: Float)
    init {
        val models = (context.applicationContext as app.televault.TeleVaultApplication).faceModels
        fun session(name: String) = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            options.setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            environment.createSession(java.io.File(models.directory, name).absolutePath, options)
        }
        val first = session("face_detection_yunet_2023mar.onnx")
        try {
            recognizer = session("face_recognition_sface_2021dec.onnx")
            detector = first
        } catch (error: Throwable) {
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
        val candidates = mutableListOf<Detection>()
        val image = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(640 * 640)
        val input = FloatArray(3 * 640 * 640)
        val samples = mutableListOf<FaceSample>()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            val windows = mutableListOf(Rect(0, 0, source.width, source.height))
            // Five overlapping views cover the full original without dozens of high-resolution passes.
            if (max(source.width, source.height) > 1280) {
                val width = max(1, (source.width * .6f).roundToInt())
                val height = max(1, (source.height * .6f).roundToInt())
                for (y in listOf(0, source.height - height).distinct()) for (x in listOf(0, source.width - width).distinct()) windows += Rect(x, y, x + width, y + height)
            }
            for (window in windows) {
                val scale = 640f / max(window.width(), window.height())
                val dx = (640 - window.width() * scale) / 2
                val dy = (640 - window.height() * scale) / 2
                Canvas(image).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(source, window, RectF(dx, dy, dx + window.width() * scale, dy + window.height() * scale), paint)
                }
                image.getPixels(pixels, 0, 640, 0, 0, 640, 640)
                // YuNet/OpenCV blobFromImage uses BGR, scale 1 and zero mean.
                for (channel in 0..2) for (i in pixels.indices) input[channel * pixels.size + i] = ((pixels[i] shr (channel * 8)) and 255).toFloat()
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 640, 640)).use { tensor ->
                    detector.run(mapOf("input" to tensor)).use { output ->
                        for (stride in intArrayOf(8, 16, 32)) {
                            val cls = rows(output, "cls_$stride")
                            val obj = rows(output, "obj_$stride")
                            val boxes = rows(output, "bbox_$stride")
                            val points = rows(output, "kps_$stride")
                            val columns = 640 / stride
                            for (i in cls.indices) {
                                val score = sqrt(cls[i][0].coerceIn(0f, 1f) * obj[i][0].coerceIn(0f, 1f))
                                if (score < .9f) continue
                                val col = i % columns
                                val row = i / columns
                                val cx = (col + boxes[i][0]) * stride
                                val cy = (row + boxes[i][1]) * stride
                                val width = exp(boxes[i][2]) * stride
                                val height = exp(boxes[i][3]) * stride
                                val box = RectF((cx - width / 2 - dx) / scale + window.left, (cy - height / 2 - dy) / scale + window.top, (cx + width / 2 - dx) / scale + window.left, (cy + height / 2 - dy) / scale + window.top)
                                if (box.width() < 32 || box.height() < 32 || box.centerX() !in window.left.toFloat()..window.right.toFloat() || box.centerY() !in window.top.toFloat()..window.bottom.toFloat()) continue
                                val keypoints = FloatArray(10) { j -> ((points[i][j] + if (j % 2 == 0) col else row) * stride - if (j % 2 == 0) dx else dy) / scale + if (j % 2 == 0) window.left else window.top }
                                candidates += Detection(box, keypoints, score)
                            }
                            cls.forEach { it.fill(0f) }
                            obj.forEach { it.fill(0f) }
                            boxes.forEach { it.fill(0f) }
                            points.forEach { it.fill(0f) }
                        }
                    }
                }
            }
            val kept = mutableListOf<Detection>()
            for (face in candidates.sortedByDescending { it.score }) {
                if (kept.none { overlap(it.box, face.box) > .35f }) kept += face
                if (kept.size == 32) break
            }
            for (face in kept) {
                val transform = try {
                    FaceAlignment.matrix(face.points)
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val crop = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(crop).drawBitmap(source, Matrix().apply { setValues(transform) }, paint)
                    val vector = embedding(crop)
                    val jpeg = ByteArrayOutputStream().use {
                        crop.compress(Bitmap.CompressFormat.JPEG, 90, it)
                        it.toByteArray()
                    }
                    if (jpeg.size <= 24000) {
                        samples += FaceSample(vector, jpeg)
                    } else {
                        vector.fill(0f)
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
            input.fill(0f)
            candidates.forEach { it.points.fill(0f) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(result: OrtSession.Result, name: String): Array<FloatArray> = (result.get(name).get().value as Array<Array<FloatArray>>)[0]
    private fun embedding(crop: Bitmap): FloatArray {
        val pixels = IntArray(112 * 112)
        val input = FloatArray(3 * pixels.size)
        crop.getPixels(pixels, 0, 112, 0, 0, 112, 112)
        try {
            // SFace's graph contains its own (x - 127.5) / 128 normalization. Supply RGB 0..255.
            for (channel in 0..2) for (i in pixels.indices) input[channel * pixels.size + i] = ((pixels[i] shr (16 - channel * 8)) and 255).toFloat()
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 112, 112)).use { tensor ->
                recognizer.run(mapOf("data" to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = (result.get("fc1").get().value as Array<FloatArray>)[0]
                    return try {
                        FaceSimilarity.normalized(output)
                    } finally {
                        output.fill(0f)
                    }
                }
            }
        } finally {
            pixels.fill(0)
            input.fill(0f)
        }
    }
    fun upgrade(sample: FaceSample): FaceSample {
        val image = BitmapFactory.decodeByteArray(sample.jpeg, 0, sample.jpeg.size, BitmapFactory.Options().apply { inMutable = true }) ?: error("Face reference could not be decoded")
        try {
            val detected = faces(image)
            if (detected.isNotEmpty()) {
                val best = detected.first()
                detected.drop(1).forEach { it.wipe() }
                return FaceSample(best.embedding, best.jpeg, sample.source, sample.contentHash)
            }
            val resized = Bitmap.createScaledBitmap(image, 112, 112, true)
            return try {
                FaceSample(embedding(resized), sample.jpeg.clone(), sample.source, sample.contentHash)
            } finally {
                if (resized !== image) {
                    resized.eraseColor(0)
                    resized.recycle()
                }
            }
        } finally {
            image.eraseColor(0)
            image.recycle()
        }
    }
    private fun overlap(a: RectF, b: RectF): Float {
        val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        return intersection / (a.width() * a.height() + b.width() * b.height() - intersection).coerceAtLeast(1f)
    }
    override fun close() {
        detector.close()
        recognizer.close()
    }
}
