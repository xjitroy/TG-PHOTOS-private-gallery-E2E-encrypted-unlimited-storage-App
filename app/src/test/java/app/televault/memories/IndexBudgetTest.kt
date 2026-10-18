package app.televault.memories

import org.junit.Assert.*
import org.junit.Test

class IndexBudgetTest {
    @Test fun rejectedRowAndReplacementLeavePreviousRowsAndTheirBudgetIntact() {
        val budget = IndexBudget(100)
        assertTrue(budget.replace("a", 60))
        assertTrue(budget.replace("b", 40))
        assertFalse(budget.replace("c", 1))
        assertFalse(budget.replace("a", 61))
        assertEquals(100L, budget.used)
        budget.remove("a")
        assertEquals(40L, budget.used)
        assertTrue(budget.replace("c", 60))
    }

    @Test fun largeLibraryNeverAdmitsMoreEncodedBytesThanTheCatalogCanPersist() {
        val budget = IndexBudget(8L * 1024 * 1024 - 4096)
        repeat(50000) { budget.replace("photo:$it", 500) }
        assertEquals((8L * 1024 * 1024 - 4096) / 500 * 500, budget.used)
        assertFalse(budget.replace("another", 500))
        budget.clear()
        assertEquals(0L, budget.used)
        assertTrue(budget.replace("another", 500))
    }

    @Test fun replacingWithASmallerRowReleasesCapacityWithoutRemovingItsIdentity() {
        val budget = IndexBudget(100)
        budget.replace("a", 100)
        budget.replace("a", 20)
        assertEquals(20L, budget.used)
        assertTrue(budget.replace("b", 80))
        budget.remove("missing")
        assertEquals(100L, budget.used)
    }
}
