package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class VideoVolumeBoostTest {
    private class Effect : VideoBoostEffect {
        val gains = mutableListOf<Int>()
        var released = 0
        override fun gain(millibels: Int) {
            gains += millibels
        }
        override fun release() {
            released++
        }
    }

    @Test fun defaultPlaybackNeverCreatesAnEffectOrChangesTheAudioPipeline() {
        var creates = 0
        val boost = VideoVolumeBoost {
            creates++
            Effect()
        }
        boost.bind(12)
        assertEquals(100, boost.set(100))
        boost.close()
        assertEquals(0, creates)
    }

    @Test fun twoHundredPercentUsesAtMostSixDecibelsAndResetsPerFile() {
        val effect = Effect()
        val boost = VideoVolumeBoost { effect }
        boost.bind(12)
        assertEquals(200, boost.set(900))
        assertEquals(listOf(602), effect.gains)
        boost.reset()
        assertEquals(100, boost.boostPercent)
        assertEquals(1, effect.released)
    }

    @Test fun effectCreationFailureLeavesNormalPlaybackUsable() {
        val boost = VideoVolumeBoost { throw IllegalStateException("Synthetic unsupported effect") }
        boost.bind(12)
        assertEquals(100, boost.set(200))
        boost.reset()
        assertEquals(100, boost.boostPercent)
    }

    @Test fun aFailedEffectAndItsReleaseCannotEscapeIntoThePlayer() {
        val boost = VideoVolumeBoost {
            object : VideoBoostEffect {
                override fun gain(millibels: Int): Unit = throw UnsupportedOperationException()
                override fun release(): Unit = throw IllegalStateException()
            }
        }
        boost.bind(12)
        assertEquals(100, boost.set(200))
        boost.close()
        boost.close()
    }

    @Test fun audioSessionChangesReleaseOnlyTheOldEffectAndInvalidIdsNeverAllocate() {
        val effects = mutableListOf<Effect>()
        val boost = VideoVolumeBoost { Effect().also(effects::add) }
        assertEquals(100, boost.set(150))
        assertTrue(effects.isEmpty())
        boost.bind(1)
        assertEquals(150, boost.boostPercent)
        boost.bind(2)
        assertEquals(1, effects[0].released)
        assertEquals(0, effects[1].released)
        boost.close()
        assertEquals(1, effects[1].released)
        assertEquals(100, boost.boostPercent)
    }
}
