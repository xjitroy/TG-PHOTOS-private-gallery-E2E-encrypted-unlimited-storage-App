package app.televault.ui

import org.junit.Assert.*
import org.junit.Test

class BrowseFocusGeometryTest {
    @Test fun tallMosaicAndListItemsUseTheirActualCentre() {
        assertEquals(300f, BrowseFocusGeometry.delta(600f, 1000f, 100f, 900f)!!, .01f)
        assertEquals(-350f, BrowseFocusGeometry.delta(100f, 200f, 100f, 900f)!!, .01f)
    }

    @Test fun alreadyCentredRequiresNoMotion() {
        assertEquals(0f, BrowseFocusGeometry.delta(200f, 800f, 0f, 1000f)!!, .01f)
    }

    @Test fun invalidAndDisposedLayoutsCannotProduceInvalidScroll() {
        assertNull(BrowseFocusGeometry.delta(Float.NaN, 200f, 0f, 1000f))
        assertNull(BrowseFocusGeometry.delta(0f, 0f, 0f, 1000f))
        assertNull(BrowseFocusGeometry.delta(0f, 100f, 0f, 0f))
    }
}
