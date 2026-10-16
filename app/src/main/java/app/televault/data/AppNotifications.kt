package app.televault.data

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.televault.R

internal object AppNotifications {
    private var logo: Bitmap? = null

    @Synchronized fun builder(context: Context, channel: String): Notification.Builder {
        val icon = logo ?: runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, R.drawable.tg_photos_logo, options)
            var sample = 1
            while (options.outWidth / sample > 128 || options.outHeight / sample > 128) sample *= 2
            val decoded = BitmapFactory.decodeResource(context.resources, R.drawable.tg_photos_logo, BitmapFactory.Options().apply { inSampleSize = sample })
            Bitmap.createScaledBitmap(decoded, 64, 64, true).also {
                if (it !== decoded) decoded.recycle()
                logo = it
            }
        }.getOrNull()
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification_tg).setContentTitle("TG Photos")
        if (icon != null) builder.setLargeIcon(icon)
        return builder
    }
}
