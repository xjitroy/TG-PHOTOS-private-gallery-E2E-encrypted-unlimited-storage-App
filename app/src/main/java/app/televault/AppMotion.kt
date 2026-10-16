package app.televault

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale
import app.televault.domain.MotionPolicy
import kotlinx.coroutines.*

/** One process-wide animation clock. Playback, grace periods and security timers are never scaled. */
class AppMotion(context: Context) : MotionDurationScale {
    private val app = context.applicationContext

    @Volatile var mode = 0
        private set

    @Volatile private var systemScale = readSystemScale()
    val userMultiplier: Float get() = MotionPolicy.multiplier(mode)
    override val scaleFactor: Float get() = MotionPolicy.scale(mode, systemScale)
    private fun readSystemScale(): Float = runCatching { Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
    init {
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        systemScale = readSystemScale()
                    }
                }
            )
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { PreferencesRepository(app).animationMode.collect { mode = it } }
    }
}
