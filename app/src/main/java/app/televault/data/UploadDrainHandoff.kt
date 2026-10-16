package app.televault.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Enqueue and final inspection share one gate. A new request either appears in the final
 * inspection or sees an unlocked runner; cancellation cannot leave admission permanently locked. */
internal suspend fun finishUploadDrain(edits: Mutex, runner: Mutex, inspect: suspend () -> Unit) {
    withContext(NonCancellable) {
        edits.withLock {
            try {
                inspect()
            } finally {
                runner.unlock()
            }
        }
    }
}
