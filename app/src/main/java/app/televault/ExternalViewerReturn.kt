package app.televault

/** Return through Android's task stack only for the currently owned external VIEW request. */
internal class ExternalViewerReturn(restored: String? = null) {
    var requestId: String? = restored
        private set
    fun accept(id: String?) {
        requestId = id
    }
    fun close(id: String): Boolean {
        if (requestId != id) return false
        requestId = null
        return true
    }
}
