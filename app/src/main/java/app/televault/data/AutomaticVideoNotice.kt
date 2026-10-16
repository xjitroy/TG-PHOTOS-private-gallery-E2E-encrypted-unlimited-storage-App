package app.televault.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/** No filenames/thumbnails on notifications; denied/blocked notification requires in-app notice. */
internal object AutomaticVideoNotice {
    fun available(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel("tgphotos-auto-video")?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun post(context: Context): Boolean {
        if (!available(context)) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "tgphotos-auto-video"
        manager.createNotificationChannel(NotificationChannel(channel, "Automatic video backup", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        return try {
            manager.notify(
                8307,
                AppNotifications.builder(context, channel)
                    .setContentText("Automatic video backup is starting for a folder you enabled. Files are encrypted before upload.")
                    .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setLocalOnly(true).setAutoCancel(true).build()
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
