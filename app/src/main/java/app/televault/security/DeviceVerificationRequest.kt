package app.televault.security

/** Main-thread request ownership: interruption revokes callbacks without granting access. */
internal class DeviceVerificationRequest {
    var generation = 0L
        private set
    var pending: (() -> Unit)? = null
        private set
    var credentialGeneration: Long? = null
        private set
    val credentialInFlight get() = credentialGeneration != null
    fun begin(action: () -> Unit): Long? {
        if (pending != null || credentialInFlight) return null
        generation++
        pending = action
        return generation
    }
    fun credential(token: Long): Boolean {
        if (token != generation || pending == null) return false
        credentialGeneration = token
        return true
    }
    fun take(token: Long): (() -> Unit)? {
        if (token != generation) return null
        val action = pending
        invalidate()
        return action
    }
    fun invalidate() {
        generation++
        pending = null
        credentialGeneration = null
    }
    fun background(): Boolean {
        if (pending == null || credentialInFlight) return false
        invalidate()
        return true
    }
    fun resumedWithoutCredentialResult(): Boolean {
        if (!credentialInFlight) return false
        invalidate()
        return true
    }
}
