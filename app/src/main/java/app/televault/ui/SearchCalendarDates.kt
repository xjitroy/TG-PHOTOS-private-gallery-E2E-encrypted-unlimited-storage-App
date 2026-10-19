package app.televault.ui

import app.televault.domain.UniversalMediaSearch
import java.time.ZoneOffset

/** Native picker bounds do not restrict typed search dates; invalid drafts open it unselected. */
internal object SearchCalendarDates {
    fun utcMillis(text: String): Long? = runCatching {
        UniversalMediaSearch.date(text)?.takeIf { it.year in 1900..2100 }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    }.getOrNull()
}
