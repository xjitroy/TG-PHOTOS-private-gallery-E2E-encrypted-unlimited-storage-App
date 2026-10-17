package app.televault.ocr
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
internal fun decodeOcrPhoto(context: Context, uri: Uri): Bitmap? {
    check(uri.scheme == "content" || uri.scheme == "file")
    check("private-vault" !in uri.pathSegments)
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val ratio = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(maxOf(1, (info.size.width * ratio).toInt()), maxOf(1, (info.size.height * ratio).toInt()))
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0)return null
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600)sample *= 2
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
