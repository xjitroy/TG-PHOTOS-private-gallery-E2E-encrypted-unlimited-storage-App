package app.televault.ui

/** A renderer may retain a published image after its Compose cell leaves the viewport. */
internal class EphemeralImageSlot<T : Any>(private val wipe: (T) -> Unit, private val discard: (T) -> Unit) {
    private var image: T? = null
    private var published = false
    private var retired = false

    @Synchronized
    fun offer(value: T) {
        if (retired) {
            wipe(value)
            discard(value)
        } else {
            check(image == null)
            image = value
        }
    }

    @Synchronized
    fun publish(): T? {
        if (retired) return null
        published = image != null
        return image
    }

    @Synchronized
    fun retire() {
        if (retired) return
        retired = true
        image?.let {
            wipe(it)
            // Never recycle memory still referenced by Android's RenderThread/display list.
            // The wiped allocation is reclaimed when the renderer releases its last reference.
            if (!published) discard(it)
        }
        image = null
    }
}
