package app.televault.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

internal object MediaTools {
    fun mime(context: Context, uri: Uri): String {
        val header = ByteArray(64)
        val count = context.contentResolver.openInputStream(uri)!!.use { it.read(header) }.coerceAtLeast(0)
        val prefix = header.copyOf(count)
        val text = prefix.toString(Charsets.ISO_8859_1)
        return when {
            count >= 3 && prefix[0] == 0xff.toByte() && prefix[1] == 0xd8.toByte() && prefix[2] == 0xff.toByte() -> "image/jpeg"
            text.startsWith("\u0089PNG") -> "image/png"
            text.startsWith("GIF8") -> "image/gif"
            text.startsWith("RIFF") && text.drop(8).startsWith("WEBP") -> "image/webp"
            count >= 12 && text.substring(4, 8) == "ftyp" -> if (text.substring(8, 12) in setOf("heic", "heix", "mif1", "avif")) {
                "image/heif"
            } else if (text.substring(8, 12) in setOf("M4A ", "M4B ")) {
                "audio/mp4"
            } else {
                "video/mp4"
            }
            text.startsWith("BM") -> "image/bmp"
            prefix.take(4) == listOf(0x1a.toByte(), 0x45.toByte(), 0xdf.toByte(), 0xa3.toByte()) -> "video/x-matroska"
            text.startsWith("%PDF-") -> "application/pdf"
            text.startsWith("PK\u0003\u0004") -> when (displayName(context, uri).substringAfterLast('.').lowercase(java.util.Locale.ROOT)) {
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                else -> "application/zip"
            }
            text.startsWith("ID3") -> "audio/mpeg"
            text.startsWith("OggS") -> "audio/ogg"
            text.startsWith("fLaC") -> "audio/flac"
            text.startsWith("RIFF") && text.drop(8).startsWith("WAVE") -> "audio/wav"
            count > 0 && prefix.all { it == 9.toByte() || it == 10.toByte() || it == 13.toByte() || (it.toInt() and 255) >= 32 } &&
                runCatching {
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(prefix))
                    true
                }.getOrDefault(false) -> "text/plain"
            else -> context.contentResolver.getType(uri)?.takeIf { it.matches(Regex("(?:image|video|audio)/[A-Za-z0-9.+_-]+")) } ?: "application/octet-stream"
        }
    }
    private fun displayName(context: Context, uri: Uri): String = if (uri.scheme == "file") {
        java.io.File(uri.path.orEmpty()).name
    } else {
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "" }
        }.getOrNull().orEmpty()
    }
    fun captureDate(context: Context, uri: Uri): Long {
        val taken = if (uri.scheme == "file") 0L else runCatching { context.contentResolver.query(uri, arrayOf("datetaken"), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L }.getOrDefault(0)
        if (taken > 0) return taken
        val exif = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                val tags = androidx.exifinterface.media.ExifInterface(it)
                app.televault.domain.CaptureTime.exif(tags.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL), tags.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL), tags.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_SUBSEC_TIME_ORIGINAL), java.time.ZoneId.systemDefault())
            }
        }.getOrNull()
        if (exif != null && exif > 0) return exif
        val modified = sourceTimes(context, uri).second
        return if (modified > 0) modified else System.currentTimeMillis()
    }

    /** Provider timestamps in milliseconds: added first, modified second. No location tracking. */
    fun sourceTimes(context: Context, uri: Uri): Pair<Long, Long> {
        if (uri.scheme == "file") return 0L to java.io.File(checkNotNull(uri.path)).lastModified().coerceAtLeast(0)
        val media = runCatching {
            context.contentResolver.query(uri, arrayOf("date_added", "date_modified"), null, null, null)?.use {
                if (it.moveToFirst()) app.televault.domain.CaptureTime.milliseconds(it.getLong(0)) to app.televault.domain.CaptureTime.milliseconds(it.getLong(1)) else null
            }
        }.getOrNull()
        if (media != null && (media.first > 0 || media.second > 0)) return media
        val modified = runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L }.getOrDefault(0)
        return 0L to modified.coerceAtLeast(0)
    }
    fun thumbnail(context: Context, uri: Uri, mime: String): ByteArray? = runCatching {
        val raw = when {
            mime.startsWith("image/") -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 384) sample *= 2
                context.contentResolver.openInputStream(uri)!!.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            }
            mime.startsWith("video/") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    if (Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
                    } else {
                        null
                    }
                } finally {
                    retriever.release()
                }
            }
            else -> null
        } ?: return null
        val bitmap = if (mime.startsWith("image/")) orientThumbnail(context, uri, raw) else raw
        try {
            ByteArrayOutputStream().use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it)
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()
    fun orientThumbnail(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching { context.contentResolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1 }.getOrDefault(1)
        val matrix = android.graphics.Matrix().apply {
            when (orientation) {
                2 -> postScale(-1f, 1f)
                3 -> postRotate(180f)
                4 -> postScale(1f, -1f)
                5 -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                6 -> postRotate(90f)
                7 -> {
                    postRotate(270f)
                    postScale(-1f, 1f)
                }
                8 -> postRotate(270f)
            }
        }
        if (matrix.isIdentity) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}
