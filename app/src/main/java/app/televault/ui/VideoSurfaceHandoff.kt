package app.televault.ui

/** A newly created normal surface is already attached; only a real PiP return needs rebinding. */
internal class VideoSurfaceHandoff {
    private var wasInPip = false
    fun update(pip: Boolean, owned: Boolean): Boolean {
        val returning = wasInPip && !pip && owned
        wasInPip = pip
        return returning
    }
}
