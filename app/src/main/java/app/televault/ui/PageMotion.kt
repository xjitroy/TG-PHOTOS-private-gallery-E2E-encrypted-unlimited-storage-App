package app.televault.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** A short entrance tied to navigation/source changes, not every recomposition. */
@Composable
internal fun Modifier.pageEntrance(vararg address: Any?, animateInitial: Boolean = true): Modifier {
    var firstPage by remember { mutableStateOf(true) }
    val progress = remember(*address) { Animatable(if (firstPage && !animateInitial) 1f else 0f) }
    LaunchedEffect(progress) {
        firstPage = false
        progress.animateTo(1f, tween(150))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12.dp.toPx()
    }
}
