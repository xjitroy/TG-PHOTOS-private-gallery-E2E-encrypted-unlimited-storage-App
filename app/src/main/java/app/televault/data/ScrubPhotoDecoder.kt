package app.televault.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import app.televault.domain.ScrubPreviewQuality
import kotlinx.coroutines.*

/** One bounded, sampled bitmap. Never constructs a player/view or writes plaintext to disk. */
internal object ScrubPhotoDecoder {
    private val serial = PreviewWorkGate(1)
    suspend fun decode(context: Context, uri: Uri, cache: DecodedPhotoCache, key: String, epoch: Long, width: Int, height: Int, adjacent: Boolean, owner: PreviewRequestOwner) = withContext(Dispatchers.IO) {
        owner.ensureOpen()
        serial.run {
            PreviewWorkGate.originals.run {
                withContext(NonCancellable) native@{
                    owner.ensureOpen()
                    val reads = owner.register(MediaReadSession.open(context, uri))
                    var bitmap: Bitmap? = null
                    try {
                        val orientation = ParcelFileDescriptor.AutoCloseInputStream(reads.descriptor(context)).use { runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1) }
                        owner.ensureOpen()
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        reads.descriptor(context).use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds) }
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@native
                        val rotated = orientation in 5..8
                        val target = ScrubPreviewQuality.fit(bounds.outWidth, bounds.outHeight, if (rotated) height else width, if (rotated) width else height, cache.scrubPixelBudget(), adjacent)
                        if (cache.sufficient(key, target.width(), target.height())) return@native
                        owner.ensureOpen()
                        bitmap = reads.descriptor(context).use {
                            BitmapFactory.decodeFileDescriptor(
                                it.fileDescriptor,
                                null,
                                BitmapFactory.Options().apply {
                                    inSampleSize = ScrubPreviewQuality.sample(bounds.outWidth, bounds.outHeight, target)
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                }
                            )
                        }
                        owner.ensureOpen()
                        val raw = bitmap ?: return@native
                        if (raw.width > target.width() || raw.height > target.height()) {
                            bitmap = Bitmap.createScaledBitmap(raw, target.width(), target.height(), true)
                            if (bitmap !== raw) raw.recycle()
                        }
                        owner.publish {
                            val decoded = checkNotNull(bitmap)
                            bitmap = null
                            PreviewOutput(decoded) { it.recycle() }.use { output ->
                                output.publish { cache.adopt(key, it, bounds.outWidth, bounds.outHeight, orientation, epoch) }
                            }
                        }
                    } finally {
                        bitmap?.recycle()
                        reads.close()
                    }
                }
            }
        }
    }
}
