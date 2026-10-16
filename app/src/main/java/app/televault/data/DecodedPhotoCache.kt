package app.televault.data

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import app.televault.domain.PreviewSize

/** Screen-fit pixels in bounded RAM only. Never writes decrypted previews to disk. */
class DecodedPhotoCache(private val capacity: Int = 24 * 1024 * 1024) {
    data class Frame(val bitmap: Bitmap, val width: Int, val height: Int, val orientation: Int, val created: Long = SystemClock.elapsedRealtime())
    private var generation = 0L
    private val frames = object : LruCache<String, Frame>(capacity) {
        override fun sizeOf(key: String, value: Frame) = value.bitmap.allocationByteCount
    }

    @Synchronized fun epoch() = generation

    @Synchronized fun get(key: String): Frame? {
        val frame = frames.get(key) ?: return null
        if (SystemClock.elapsedRealtime() - frame.created >= 86400000L) {
            frames.remove(key)
            return null
        }
        return frame
    }

    /** Called on IO after a successful authenticated full-image decode, never on UI. */
    fun remember(key: String, bitmap: Bitmap, width: Int, height: Int, orientation: Int, epoch: Long) {
        val target = PreviewSize.fit(bitmap.width, bitmap.height)
        val copy = try {
            if (target.width() == bitmap.width && target.height() == bitmap.height) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                Bitmap.createScaledBitmap(bitmap, target.width(), target.height(), true)
            }
        } catch (_: RuntimeException) {
            return
        } catch (_: OutOfMemoryError) {
            return
        } ?: return
        if (!put(key, Frame(copy, width, height, orientation), epoch)) copy.recycle()
    }

    @Synchronized private fun put(key: String, frame: Frame, epoch: Long): Boolean {
        if (epoch != generation || frame.bitmap.allocationByteCount > 8 * 1024 * 1024) return false
        frames.put(key, frame)
        return true
    }

    @Synchronized fun clear() {
        generation++
        frames.evictAll()
    }
}
