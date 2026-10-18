package app.televault.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/** Only allocation failure retries. Cancellation and ordinary errors preserve their semantics. */
internal fun <T> Flow<T>.recoveringMemory(delayMillis: Long = 1500, release: () -> Unit): Flow<T> = retryWhen { error, _ ->
    if (error is OutOfMemoryError) {
        release()
        delay(delayMillis)
        true
    } else {
        false
    }
}
