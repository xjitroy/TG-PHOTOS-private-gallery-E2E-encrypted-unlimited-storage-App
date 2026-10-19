package app.televault.ui
import org.junit.Assert.*
import org.junit.Test

class BrowseFocusMotionTest {
    @Test fun nearbyTravelIsContinuousAndSoft() {
        val travel = BrowseFocusMotion.travel(300f, 1000f)
        assertEquals(0f, travel.jump, 0f)
        assertEquals(300f, travel.glide, 0f)
        assertTrue(travel.durationMs in 220..300)
    }

    @Test fun distantMosaicTravelIsBoundedInBothDirections() {
        for (delta in listOf(-30000f, -1000f, 1000f, 30000f)) {
            val travel = BrowseFocusMotion.travel(delta, 1000f)
            assertTrue(kotlin.math.abs(travel.glide) <= 800f)
            assertEquals(delta, travel.jump + travel.glide, .01f)
            assertTrue(travel.durationMs <= 300)
        }
    }

    @Test fun invalidLayoutCannotProduceAnAnimationOrNanScroll() {
        for ((delta, viewport) in listOf(Float.NaN to 1000f, 300f to 0f, Float.POSITIVE_INFINITY to 100f)) {
            assertEquals(BrowseFocusMotion.Travel(0f, 0f, 0), BrowseFocusMotion.travel(delta, viewport))
        }
    }
}
