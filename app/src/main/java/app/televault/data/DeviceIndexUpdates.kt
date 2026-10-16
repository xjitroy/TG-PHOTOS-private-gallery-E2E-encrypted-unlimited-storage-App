package app.televault.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate

/** A burst requests one subsequent scan; it never cancels an in-flight result. */
internal suspend fun collectDeviceIndexUpdates(events: Flow<Unit>, scan: suspend () -> Unit) {
    events.conflate().collect {
        scan()
        delay(350)
    }
}
