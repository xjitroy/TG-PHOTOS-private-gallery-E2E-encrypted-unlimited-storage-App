package app.televault.ui

/** OEMs may send a cancelled/error callback after success. Exactly one terminal result wins. */
internal class BiometricCallbackGate {
    private val active = java.util.concurrent.atomic.AtomicBoolean(true)
    fun terminal(): Boolean = active.compareAndSet(true, false)
    fun dispose() {
        active.set(false)
    }
}
