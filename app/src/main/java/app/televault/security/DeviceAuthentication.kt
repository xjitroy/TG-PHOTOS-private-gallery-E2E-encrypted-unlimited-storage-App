package app.televault.security

import androidx.compose.runtime.staticCompositionLocalOf

/** Provided by the activity; critical actions request a fresh platform challenge. */
val LocalDeviceAuthentication = staticCompositionLocalOf<((() -> Unit) -> Unit)> { error("Device authentication host missing") }
val LocalDeviceLock = staticCompositionLocalOf<(() -> Unit)> { error("Device lock host missing") }

/** Sensitive file actions use a new system challenge and never fall back to the app password. */
val LocalSensitiveAuthentication = staticCompositionLocalOf<((() -> Unit) -> Unit)> { error("Sensitive authentication host missing") }
