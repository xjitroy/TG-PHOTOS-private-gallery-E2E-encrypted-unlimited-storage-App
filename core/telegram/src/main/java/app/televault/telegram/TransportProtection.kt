package app.televault.telegram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-owned protection policy; production checks again immediately before every native request. */
interface TransportProtection {
    val paused: StateFlow<Boolean>
    fun blocked(): Boolean
    fun requireAllowed() {
        check(!blocked()) { "Device protection paused cloud and vault access" }
    }
}
object OpenTransportProtection : TransportProtection {
    override val paused: StateFlow<Boolean> = MutableStateFlow(false)
    override fun blocked() = false
}
