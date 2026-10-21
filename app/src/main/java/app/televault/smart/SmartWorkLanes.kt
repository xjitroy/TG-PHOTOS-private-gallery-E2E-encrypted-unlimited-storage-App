package app.televault.smart

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A slow/cancelled public-weight download cannot occupy the working inference lane. */
internal class SmartWorkLanes {
    private val download = Mutex()
    private val inference = Mutex()
    suspend fun <T> download(block: suspend () -> T): T = download.withLock { block() }
    suspend fun idle(block: suspend () -> Unit): Boolean {
        if (!inference.tryLock()) return false
        try {
            block()
        } finally {
            inference.unlock()
        }
        return true
    }
    suspend fun <T> runtime(block: suspend () -> T): T = inference.withLock { block() }
}
