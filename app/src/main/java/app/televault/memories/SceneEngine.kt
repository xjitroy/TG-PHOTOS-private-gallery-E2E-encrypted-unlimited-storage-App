package app.televault.memories

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import app.televault.data.MediaReadSession
import app.televault.domain.SceneLabels
import java.nio.FloatBuffer

/** CPU-only 224px centre crop, using the upstream ImageNet normalization. */
internal class SceneEngine(context: Context, models: MemoryModels) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val labels = context.assets.open("memory-labels.txt").bufferedReader().use { it.readLines() }
    private val session = OrtSession.SessionOptions().use {
        it.setIntraOpNumThreads(1)
        it.setInterOpNumThreads(1)
        it.setCPUArenaAllocator(false)
        it.setMemoryPatternOptimization(false)
        environment.createSession(java.io.File(models.directory, "mobilenetv2_7.onnx").absolutePath, it)
    }
    fun classify(context: Context, source: Uri): String = MediaReadSession.open(context, source).use { read ->
        val image = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, read.uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val size = app.televault.domain.AnalysisImageSize.fit(info.size.width, info.size.height, 512, 262144)
                decoder.setTargetSize(size.width(), size.height())
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(read.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            check(bounds.outWidth > 0 && bounds.outHeight > 0)
            val options = BitmapFactory.Options().apply {
                inSampleSize = app.televault.domain.ThumbnailQuality.sample(bounds.outWidth, bounds.outHeight, 512)
                inMutable = true
            }
            val decoded = checkNotNull(context.contentResolver.openInputStream(read.uri)?.use { BitmapFactory.decodeStream(it, null, options) })
            app.televault.data.MediaTools.orientThumbnail(context, read.uri, decoded)
        }
        try {
            classifyPixels(image)
        } finally {
            image.eraseColor(0)
            image.recycle()
        }
    }
    fun classifyPixels(source: Bitmap): String {
        val owned = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, true) else null
        try {
            return infer(owned ?: source)
        } finally {
            owned?.let {
                it.eraseColor(0)
                it.recycle()
            }
        }
    }
    private fun infer(image: Bitmap): String = run {
        val crop = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(224 * 224)
        val input = FloatArray(3 * pixels.size)
        try {
            val edge = minOf(image.width, image.height)
            val left = (image.width - edge) / 2
            val top = (image.height - edge) / 2
            Canvas(crop).drawBitmap(image, Rect(left, top, left + edge, top + edge), Rect(0, 0, 224, 224), Paint(Paint.FILTER_BITMAP_FLAG))
            crop.getPixels(pixels, 0, 224, 0, 0, 224, 224)
            val mean = floatArrayOf(.485f, .456f, .406f)
            val std = floatArrayOf(.229f, .224f, .225f)
            for (channel in 0..2) for (i in pixels.indices) input[channel * pixels.size + i] = (((pixels[i] shr (16 - channel * 8)) and 255) / 255f - mean[channel]) / std[channel]
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 224, 224)).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { output ->
                    val scores = ((output[0].value as Array<*>)[0] as FloatArray)
                    try {
                        SceneLabels.select(scores, labels)
                    } finally {
                        scores.fill(0f)
                    }
                }
            }
        } finally {
            input.fill(0f)
            pixels.fill(0)
            crop.eraseColor(0)
            crop.recycle()
        }
    }
    override fun close() = session.close()
}
