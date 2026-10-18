package app.televault.data

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Waiting work is cancellable. A native call keeps its permit until it actually returns. */
internal class PreviewWorkGate(permits: Int = 2) {
    private val gate = Semaphore(permits)
    suspend fun <T> run(block: suspend () -> T): T = gate.withPermit { block() }
    companion object {
        val thumbnails = PreviewWorkGate()
        val originals = PreviewWorkGate()
    }
}
