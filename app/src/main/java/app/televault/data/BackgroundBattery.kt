package app.televault.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import app.televault.domain.BatteryWorkPolicy

internal object BackgroundBattery {
    private fun reading(context: Context): Pair<Int, Boolean> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (100L * level / scale).toInt().coerceIn(0, 100) else -1
        return percent to ((battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }
    fun backup(context: Context): Boolean = reading(context).let { BatteryWorkPolicy.backup(it.first, it.second) }
    fun analysis(context: Context): Boolean = reading(context).let { BatteryWorkPolicy.analysis(it.first, it.second) }
}
