package app.televault.ui

/** Real reading time, independent of animation speed; each visible card consumes one advance. */
internal class FeatureTourGate {
    private var page = -1
    private var entered: Long? = null
    fun show(page: Int, now: Long) {
        this.page = page
        entered = now
    }
    fun pause() {
        entered = null
    }
    fun ready(page: Int, now: Long): Boolean {
        val start = entered ?: return false
        return this.page == page && now >= start && now - start >= 1000
    }
    fun take(page: Int, now: Long): Boolean {
        if (!ready(page, now)) return false
        entered = null
        return true
    }
}
