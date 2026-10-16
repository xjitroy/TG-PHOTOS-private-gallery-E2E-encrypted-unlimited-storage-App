package app.televault.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens the service code chat without putting a phone number or OTP in any URI. */
internal fun openTelegramCodeChat(context: Context): Boolean {
    for (app in listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")) {
        val launch = context.packageManager.getLaunchIntentForPackage(app) ?: continue
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://openmessage?user_id=777000")).setPackage(app))
            return true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(launch)
                return true
            } catch (_: ActivityNotFoundException) { /* Try next known client. */ }
        }
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.telegram.messenger")))
        return true
    } catch (_: ActivityNotFoundException) {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=org.telegram.messenger")))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
