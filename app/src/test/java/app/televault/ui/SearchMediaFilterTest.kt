package app.televault.ui

import app.televault.domain.MediaCategories
import app.televault.people.PeopleState
import app.televault.people.PersonCard
import org.junit.Assert.*
import org.junit.Test

class SearchMediaFilterTest {
    private fun item(id: String, mime: String) = MediaEntry(id, "$id.dat", mime, 10, 1)
    private val people = PeopleState(people = listOf(PersonCard("one", "JIT", byteArrayOf(), setOf("p", "both")), PersonCard("two", "Friend", byteArrayOf(), setOf("both"))), faceCounts = mapOf("p" to 1, "both" to 2))

    @Test fun everyoneIgnoresStaleAloneAndCompanionAndIncludesDocuments() {
        assertTrue(SearchMediaFilter(alone = true, companion = "two").matches(item("doc", "application/pdf"), people, emptySet()))
        assertTrue(SearchMediaFilter().matches(item("unprocessed", "image/jpeg"), people, emptySet()))
    }

    @Test fun personAndTogetherOnlyReturnActualMembers() {
        assertTrue(SearchMediaFilter(personId = "one", companion = "two").matches(item("both", "image/jpeg"), people, emptySet()))
        assertFalse(SearchMediaFilter(personId = "one", companion = "two").matches(item("p", "image/jpeg"), people, emptySet()))
        assertFalse(SearchMediaFilter(personId = "missing").matches(item("p", "image/jpeg"), people, emptySet()))
    }

    @Test fun aloneExcludesMultipleUnknownAndNonPhoto() {
        val selection = SearchMediaFilter(personId = "one", alone = true)
        assertTrue(selection.matches(item("p", "image/jpeg"), people, emptySet()))
        assertFalse(selection.matches(item("both", "image/jpeg"), people, emptySet()))
        assertFalse(selection.matches(item("p", "video/mp4"), people, emptySet()))
    }

    @Test fun typeFavoritesAndCategoryStayIndependent() {
        assertFalse(SearchMediaFilter(type = "Files").matches(item("p", "image/jpeg"), people, emptySet()))
        assertTrue(SearchMediaFilter(type = "Files").matches(item("doc", "application/pdf"), people, emptySet()))
        assertTrue(SearchMediaFilter(type = "Favorites").matches(item("p", "image/jpeg"), people, setOf("p")))
        assertFalse(SearchMediaFilter(categories = MediaFilterOptions(category = MediaCategories.Category.MOVIES)).matches(item("p", "image/jpeg"), people, emptySet()))
    }
}
