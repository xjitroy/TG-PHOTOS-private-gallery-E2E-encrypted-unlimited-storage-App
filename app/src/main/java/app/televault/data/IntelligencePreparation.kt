package app.televault.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Each independent engine gets a bounded attempt. A stalled People restore must not starve OCR. */
internal suspend fun prepareIntelligence(
    enabled: List<Boolean>,
    timeoutMillis: Long = 60_000,
    prepare: List<suspend () -> Boolean>,
    ready: (Int) -> Unit = {}
): List<Boolean> = coroutineScope {
    require(enabled.size == prepare.size)
    enabled.indices.map { index ->
        async {
            if (!enabled[index]) return@async false
            val available = try {
                withTimeoutOrNull(timeoutMillis) { prepare[index]() } == true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
            if (available) ready(index)
            available
        }
    }.map { it.await() }
}
