package app.televault.ui

import app.televault.domain.PeopleMediaFilter
import app.televault.people.PeopleState

/** Everyone removes all person-only constraints; media/category filters remain independent. */
internal data class SearchMediaFilter(val type: String = "All", val personId: String? = null, val alone: Boolean = false, val companion: String? = null, val categories: MediaFilterOptions = MediaFilterOptions()) {
    fun matches(file: MediaEntry, people: PeopleState, favorites: Set<String>): Boolean {
        val typeMatch = when (type) {
            "Photos" -> file.mime.startsWith("image/")
            "Videos" -> file.mime.startsWith("video/")
            "Audio", "Music" -> file.mime.startsWith("audio/")
            "Favorites" -> file.cloud?.favorite == true || file.id in favorites
            "Files" -> !file.mime.startsWith("image/") && !file.mime.startsWith("video/") && !file.mime.startsWith("audio/")
            else -> true
        }
        if (!typeMatch || !categories.allows(file)) return false
        if (personId == null) return true
        if (alone && !file.mime.startsWith("image/")) return false
        val group = people.people.find { it.id == personId } ?: return false
        return PeopleMediaFilter.matches(file.id, group.members, companion?.let { id -> people.people.find { it.id == id }?.members }.orEmpty(), companion != null, alone, people.faceCounts[file.id] ?: -1)
    }
}
