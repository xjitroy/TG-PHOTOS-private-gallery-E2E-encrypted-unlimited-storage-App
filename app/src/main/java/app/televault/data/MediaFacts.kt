package app.televault.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import app.televault.domain.CaptureTime
import app.televault.domain.TimelineZoom
import org.json.JSONObject

/** Embedded capture facts only: upload-phone identity is never substituted for camera identity. */
data class MediaFacts(
    val width: Int = 0,
    val height: Int = 0,
    val cameraMake: String = "",
    val cameraModel: String = "",
    val capturedAt: Long = 0,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val durationMs: Long = 0
) {
    val aspect: String get() = TimelineZoom.aspect(width, height)
    val camera: String get() = if (cameraModel.startsWith(cameraMake, true)) cameraModel else listOf(cameraMake, cameraModel).filter { it.isNotBlank() }.joinToString(" ")
    fun json(): JSONObject = JSONObject().put("width", width).put("height", height).put("make", cameraMake).put("model", cameraModel).put("captured", capturedAt).put("duration", durationMs).apply {
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
    }
    companion object {
        fun from(json: JSONObject?): MediaFacts {
            if (json == null) return MediaFacts()
            val lat = json.optDouble("latitude", Double.NaN).takeIf { it.isFinite() && it in -90.0..90.0 }
            val lon = json.optDouble("longitude", Double.NaN).takeIf { it.isFinite() && it in -180.0..180.0 }
            return MediaFacts(json.optInt("width").coerceIn(0, 100000), json.optInt("height").coerceIn(0, 100000), json.optString("make").take(120), json.optString("model").take(120), json.optLong("captured").coerceAtLeast(0), if (lon != null) lat else null, if (lat != null) lon else null, json.optLong("duration").coerceAtLeast(0))
        }
        fun read(context: Context, uri: Uri, mime: String): MediaFacts = runCatching {
            val effectiveMime = app.televault.domain.MediaCategories.videoMime(uri.lastPathSegment, mime)
            if (effectiveMime.startsWith("image/")) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val metadataUri = if (Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY && ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) MediaStore.setRequireOriginal(uri) else uri
                val exif = runCatching { context.contentResolver.openInputStream(metadataUri)?.use { runCatching { ExifInterface(it) }.getOrNull() } }.getOrNull()
                val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) ?: 1
                val rotated = orientation in 5..8
                val gps = exif?.latLong
                val capture = exif?.let { CaptureTime.exif(it.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), it.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL), it.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL), java.time.ZoneId.systemDefault()) } ?: 0
                MediaFacts(if (rotated) bounds.outHeight else bounds.outWidth, if (rotated) bounds.outWidth else bounds.outHeight, exif?.getAttribute(ExifInterface.TAG_MAKE).orEmpty().take(120), exif?.getAttribute(ExifInterface.TAG_MODEL).orEmpty().take(120), capture, gps?.getOrNull(0), gps?.getOrNull(1))
            } else if (effectiveMime.startsWith("video/") || effectiveMime.startsWith("audio/")) {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(context, uri)
                    val w = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotated = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() in setOf(90, 270)
                    val location = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION).orEmpty().take(120)
                    val gps = Regex("^([+-][0-9.]+)([+-][0-9.]+)/?$").matchEntire(location)
                    MediaFacts(if (rotated) h else w, if (rotated) w else h, capturedAt = CaptureTime.video(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)), latitude = gps?.groupValues?.get(1)?.toDoubleOrNull(), longitude = gps?.groupValues?.get(2)?.toDoubleOrNull(), durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0)
                } finally {
                    reader.release()
                }
            } else {
                MediaFacts()
            }
        }.getOrDefault(MediaFacts()).let { from(it.json()) }
    }
}
