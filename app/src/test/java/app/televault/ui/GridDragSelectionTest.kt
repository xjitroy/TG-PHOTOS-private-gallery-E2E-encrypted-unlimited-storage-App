package app.televault.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class GridDragSelectionTest {
    @Test fun mosaicHitsEachActualTileInsteadOfSelectingAnEntireBlock() {
        val drag = GridDragSelection()
        drag.viewport = Rect(0f, 100f, 400f, 600f)
        drag.tiles["small"] = GridDragSelection.Tile(Any(), Rect(0f, 100f, 98f, 198f))
        drag.tiles["hero"] = GridDragSelection.Tile(Any(), Rect(100f, 100f, 400f, 400f))
        assertEquals("small", drag.hit(Offset(50f, 150f)))
        assertEquals("hero", drag.hit(Offset(200f, 350f)))
        assertNull(drag.hit(Offset(50f, 250f)))
    }

    @Test fun edgeScrollingTargetsVisibleTilesAndIgnoresOffscreenPrefetch() {
        val drag = GridDragSelection()
        drag.viewport = Rect(0f, 100f, 400f, 600f)
        drag.tiles["visible"] = GridDragSelection.Tile(Any(), Rect(0f, 500f, 100f, 600f))
        drag.tiles["prefetch"] = GridDragSelection.Tile(Any(), Rect(0f, 620f, 100f, 720f))
        assertEquals("visible", drag.nearest(Offset(50f, 610f)))
        assertEquals("visible", drag.nearest(Offset(50f, 650f)))
        assertNull(drag.hit(Offset(50f, 650f)))
    }

    @Test fun guttersFollowNearestRowBeforeCrossingToAnotherRow() {
        val drag = GridDragSelection()
        drag.viewport = Rect(0f, 0f, 400f, 500f)
        drag.tiles["current-row"] = GridDragSelection.Tile(Any(), Rect(200f, 0f, 300f, 100f))
        drag.tiles["next-row"] = GridDragSelection.Tile(Any(), Rect(0f, 102f, 100f, 202f))
        assertEquals("current-row", drag.nearest(Offset(99f, 98f)))
    }
}
