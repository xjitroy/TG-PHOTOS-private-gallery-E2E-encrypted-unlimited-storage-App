package app.televault.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal class ExternalFallbackGate {
    private var offered = false
    fun claim(unsupported: Boolean, resumed: Boolean): Boolean {
        if (!unsupported || !resumed || offered) return false
        offered = true
        return true
    }
}

/** A codec failure may offer the system chooser once; retries/recomposition never form a loop. */
@Composable internal fun UnsupportedFormatFallback(identity: Any, unsupported: Boolean, openWith: () -> Unit) {
    val gate = remember(identity) { ExternalFallbackGate() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val latest by rememberUpdatedState(openWith)
    LaunchedEffect(identity, unsupported, state) {
        if (gate.claim(unsupported, state.isAtLeast(Lifecycle.State.RESUMED))) latest()
    }
}
