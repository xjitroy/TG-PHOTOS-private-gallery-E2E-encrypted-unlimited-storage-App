package app.televault.ocr
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
internal fun decodeOcrPhoto(context: Context, uri: Uri): Bitmap? {
    val lowMemory = context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice
    val maxEdge = if (lowMemory) 1600 else 2400
    val maxPixels = if (lowMemory) 2000000L else 4000000L
    check(uri.scheme == "content" || uri.scheme == "file")
    check("private-vault" !in uri.pathSegments)
    if (Build.VERSION.SDK_INT >= 28) {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val size = app.televault.domain.AnalysisImageSize.fit(info.size.width, info.size.height, maxEdge, maxPixels)
            decoder.setTargetSize(size.width(), size.height())
        }
        if (decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) return decoded
        return try {
            checkNotNull(decoded.copy(Bitmap.Config.ARGB_8888, true))
        } finally {
            if (decoded.isMutable) decoded.eraseColor(0)
            decoded.recycle()
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0)return null
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge || (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > maxPixels)sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(
            it,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    } ?: return null
    val oriented = app.televault.data.MediaTools.orientThumbnail(context, uri, decoded)
    if (oriented.isMutable)return oriented
    return oriented.copy(Bitmap.Config.ARGB_8888, true).also { oriented.recycle() }
}
