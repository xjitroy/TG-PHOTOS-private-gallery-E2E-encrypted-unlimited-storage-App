package app.televault.ui

import android.media.audiofx.LoudnessEnhancer
import app.televault.domain.VideoVolume
import kotlin.math.log10
import kotlin.math.roundToInt

internal interface VideoBoostEffect {
    fun gain(millibels: Int)
    fun release()
}

/** Optional per-audio-session native effect. Its absence can never stop the normal decoder. */
internal class VideoVolumeBoost(
    private val create: (Int) -> VideoBoostEffect = { id ->
        val effect = LoudnessEnhancer(id)
        object : VideoBoostEffect {
            override fun gain(millibels: Int) {
                effect.setTargetGain(millibels)
                effect.enabled = true
            }
            override fun release() {
                effect.release()
            }
        }
    }
) {
    private var session = 0
    private var effect: VideoBoostEffect? = null
    private var requested = 100
    var boostPercent: Int = 100
        private set
    fun bind(id: Int) {
        if (id == session) return
        clearEffect()
        session = id
        apply()
    }
    fun set(percent: Int): Int {
        requested = VideoVolume.percent(percent).coerceAtLeast(100)
        apply()
        return boostPercent
    }
    private fun apply() {
        if (requested == 100 || session <= 0) {
            clearEffect()
            return
        }
        try {
            val active = effect ?: create(session).also { effect = it }
            active.gain((2000.0 * log10(requested / 100.0)).roundToInt().coerceIn(0, 602))
            boostPercent = requested
        } catch (_: Exception) {
            clearEffect()
        } catch (_: LinkageError) {
            clearEffect()
        }
    }
    private fun clearEffect() {
        val old = effect
        effect = null
        boostPercent = 100
        try {
            old?.release()
        } catch (_: Exception) { } catch (_: LinkageError) { }
    }
    fun reset() {
        requested = 100
        clearEffect()
    }
    fun close() {
        reset()
        session = 0
    }
}
