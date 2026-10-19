package app.televault.data

internal data class FirstOpenProgress(val featuresSeen: Boolean, val warningSeen: Boolean) {
    companion object {
        // Existing users who acknowledged the storage warning are not forced through a new tour.
        fun resolve(features: Boolean?, warning: Boolean) = FirstOpenProgress(features ?: warning, warning)
    }
}
