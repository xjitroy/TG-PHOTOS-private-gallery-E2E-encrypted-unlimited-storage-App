package app.televault.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** A short entrance tied to navigation/source changes, not every recomposition. */
@Composable
internal fun Modifier.pageEntrance(vararg address: Any?): Modifier {
    val progress = remember(*address) { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, tween(150)) }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12.dp.toPx()
    }
}
