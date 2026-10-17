package app.televault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import app.televault.data.MediaMemory

/** Ephemeral orientation-aware preview. No decoded cache, plaintext file or public thumbnail. */
internal fun decodeVaultImage(context: Context, uri: Uri, maxPixels: Int = Int.MAX_VALUE): Bitmap {
    val pixels = minOf(MediaMemory.budget(context, 8, 2) / 4, maxPixels.coerceAtLeast(1))
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            var sample = 1
            while (info.size.width.toLong() * info.size.height / sample / sample > pixels) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / sample / sample > pixels) sample *= 2
        val decoded = checkNotNull(
            context.contentResolver.openInputStream(uri)!!.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inMutable = true
                    }
                )
            }
        )
        try {
            val exif = context.contentResolver.openInputStream(uri)!!.use { androidx.exifinterface.media.ExifInterface(it) }
            if (exif.rotationDegrees != 0 || exif.isFlipped) {
                val matrix = android.graphics.Matrix().apply {
                    if (exif.isFlipped) postScale(-1f, 1f)
                    postRotate(exif.rotationDegrees.toFloat())
                }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
            } else {
                decoded
            }
        } catch (error: Exception) {
            decoded.recycle()
            throw error
        }
    }
    val mutable = if (bitmap.isMutable) bitmap else checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, true)).also { bitmap.recycle() }
    try {
        (context.applicationContext as app.televault.TeleVaultApplication).privateVault.validateUri(uri)
        return mutable
    } catch (error: Exception) {
        mutable.eraseColor(0)
        mutable.recycle()
        throw error
    }
}

internal fun vaultImageSlot() = EphemeralImageSlot<Bitmap>(
    wipe = { if (!it.isRecycled && it.isMutable) it.eraseColor(0) },
    discard = { if (!it.isRecycled) it.recycle() }
)
