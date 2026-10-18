package app.televault

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep native face/OCR inference from simultaneously peaking phone memory/CPU. */
internal object OnDeviceAnalysis {
    private val gate = Mutex()
    suspend fun <T> run(block: suspend () -> T): T = gate.withLock { block() }
}
