package app.televault.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

internal fun videoActivity(context: Context): app.televault.MainActivity? = when (context) {
    is app.televault.MainActivity -> context
    is ContextWrapper -> videoActivity(context.baseContext)
    else -> null
}

/** Activity-owned surface for PiP. Dialog windows cannot serve as the system PiP surface. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoPipOverlay(player: ExoPlayer?) {
    AndroidView(factory = { context ->
        PlayerView(context).apply {
            useController = false
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }, update = { it.player = player }, modifier = Modifier.fillMaxSize().background(Color.Black), onRelease = { it.player = null })
}
