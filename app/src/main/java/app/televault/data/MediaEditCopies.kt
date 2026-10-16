package app.televault.data

import android.content.Context
import android.graphics.*
import android.media.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import java.nio.ByteBuffer
import kotlinx.coroutines.*

/** Explicit user-selected exports only. No plaintext staging or replacement of source bytes. */
internal object MediaEditCopies {
    internal fun destination(context: Context, source: Uri, target: Uri): ParcelFileDescriptor {
        require(source != target)
        val output = checkNotNull(context.contentResolver.openFileDescriptor(target, "rw"))
        try {
            context.contentResolver.openFileDescriptor(source, "r")!!.use { input ->
                val a = Os.fstat(input.fileDescriptor)
                val b = Os.fstat(output.fileDescriptor)
                check(a.st_dev != b.st_dev || a.st_ino != b.st_ino) { "Choose a different file." }
            }
            Os.lseek(output.fileDescriptor, 0, OsConstants.SEEK_SET)
            return output
        } catch (e: Exception) {
            output.close()
            throw e
        }
    }
    suspend fun image(context: Context, source: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > 4_000_000) sample *= 2
        val decoded = context.contentResolver.openInputStream(source)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("Unsupported image")
        val orientation = context.contentResolver.openInputStream(source)!!.use { runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1) }
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            6 -> matrix.setRotate(90f)
            7 -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            8 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }
    fun adjust(original: Bitmap, rotation: Int, ratio: Float, exposure: Float, saturation: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(rotation * 90f) }
        val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        val width = if (ratio <= 0) rotated.width else minOf(rotated.width, (rotated.height * ratio).toInt()).coerceAtLeast(1)
        val height = if (ratio <= 0) rotated.height else minOf(rotated.height, (width / ratio).toInt()).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(rotated, (rotated.width - width) / 2, (rotated.height - height) / 2, width, height)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val colors = ColorMatrix().apply { setSaturation(saturation) }
        val shift = (exposure.coerceIn(-1f, 1f) * 100)
        colors.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, shift, 0f, 1f, 0f, 0f, shift, 0f, 0f, 1f, 0f, shift, 0f, 0f, 0f, 1f, 0f)))
        Canvas(output).drawBitmap(crop, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(colors) })
        if (crop !== original && crop !== rotated) crop.recycle()
        if (rotated !== original) rotated.recycle()
        return output
    }
    suspend fun saveImage(context: Context, source: Uri, target: Uri, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        var safe = false
        try {
            destination(context, source, target).use { descriptor ->
                safe = true
                Os.ftruncate(descriptor.fileDescriptor, 0)
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.flush()
                    output.fd.sync()
                }
            }
        } catch (e: Exception) {
            if (safe) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw e
        }
    }

    /** Lossless encoded-track trim. Starts at the preceding keyframe; no transcoding/crop. */
    suspend fun saveVideo(context: Context, source: Uri, target: Uri, startMs: Long, endMs: Long, rotation: Int, mute: Boolean) = withContext(Dispatchers.IO) {
        require(startMs >= 0 && endMs > startMs)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var safe = false
        try {
            extractor.setDataSource(context, source, null)
            val video = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(video)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStart = extractor.sampleTime.coerceAtLeast(0)
            check(actualStart < endMs * 1000)
            destination(context, source, target).use { output ->
                safe = true
                Os.ftruncate(output.fileDescriptor, 0)
                val writer = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = writer
                val format = extractor.getTrackFormat(video)
                val originalRotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                writer.setOrientationHint((originalRotation + rotation * 90) % 360)
                val map = mutableMapOf<Int, Int>()
                var bufferSize = 2 * 1024 * 1024
                for (index in 0 until extractor.trackCount) {
                    val track = extractor.getTrackFormat(index)
                    val mime = track.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("video/") || !mute && mime.startsWith("audio/")) {
                        map[index] = writer.addTrack(track)
                        extractor.selectTrack(index)
                        if (track.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufferSize = maxOf(bufferSize, track.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
                check(bufferSize <= 16 * 1024 * 1024) { "This codec needs a larger sample buffer." }
                extractor.seekTo(actualStart, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                writer.start()
                started = true
                val bytes = ByteBuffer.allocateDirect(bufferSize)
                val info = MediaCodec.BufferInfo()
                var writtenVideo = false
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val track = extractor.sampleTrackIndex
                    val time = extractor.sampleTime
                    if (track < 0 || time >= endMs * 1000) break
                    if (track in map && time >= actualStart) {
                        bytes.clear()
                        val count = extractor.readSampleData(bytes, 0)
                        if (count < 0) break
                        check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0)
                        info.set(0, count, time - actualStart, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        writer.writeSampleData(map.getValue(track), bytes, info)
                        if (track == video) writtenVideo = true
                    }
                    extractor.advance()
                }
                check(writtenVideo)
                writer.stop()
                started = false
                writer.release()
                muxer = null
                Os.fsync(output.fileDescriptor)
            }
        } catch (e: Exception) {
            if (safe) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw e
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
