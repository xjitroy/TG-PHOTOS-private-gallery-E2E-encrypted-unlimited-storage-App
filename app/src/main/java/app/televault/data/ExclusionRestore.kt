package app.televault.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Authentication/checkpoint failures propagate. Only a completely enumerated restore may commit. */
internal suspend fun <T> restoreExclusionRecords(records: List<T>, known: (T) -> Boolean, apply: suspend (T) -> Unit, commit: suspend () -> Unit, progress: (Int, Int) -> Unit) {
    var done = records.count(known)
    progress(done, records.size)
    for (record in records) {
        currentCoroutineContext().ensureActive()
        if (known(record)) continue
        apply(record)
        progress(++done, records.size)
    }
    currentCoroutineContext().ensureActive()
    commit()
}
