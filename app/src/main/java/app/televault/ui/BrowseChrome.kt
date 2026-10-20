package app.televault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.dp
import app.televault.domain.PhotoChromePolicy

/** The introduction stays in the list; only compact controls are overlaid on reverse scroll. */
internal class BrowseChromeState(private val atTop: () -> Boolean, private val isIntroductionCleared: () -> Boolean, private val density: Float) {
    private val policy = PhotoChromePolicy()
    var mode by mutableStateOf(PhotoChromePolicy.Mode.EXPANDED)
        private set
    val introductionCleared: Boolean get() = isIntroductionCleared()
    val compactVisible: Boolean get() = mode == PhotoChromePolicy.Mode.COMPACT && introductionCleared
    fun reset() {
        policy.reset()
        mode = policy.mode()
    }
    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) mode = policy.scroll((consumed.y + available.y) / density, atTop())
            return Offset.Zero
        }
    }
}

@Composable
internal fun rememberFileChrome(scroll: LazyListState, vararg keys: Any?): BrowseChromeState {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val chrome = remember(scroll, density, *keys) { BrowseChromeState({ !scroll.canScrollBackward }, { scroll.firstVisibleItemIndex > 0 }, density) }
    LaunchedEffect(chrome) {
        snapshotFlow { !scroll.canScrollBackward }.collect { if (it) chrome.reset() }
    }
    return chrome
}

@Composable
internal fun BrowseCompactBar(show: Boolean, modifier: Modifier = Modifier, clearOfIntroduction: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(show, modifier.statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp), enter = fadeIn(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { -it / 8 }, exit = fadeOut(androidx.compose.animation.core.tween(140))) {
        if (clearOfIntroduction) {
            AppSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp) {
                Column(content = content)
            }
        }
    }
}

@Composable
internal fun rememberGridChrome(scroll: androidx.compose.foundation.lazy.grid.LazyGridState, vararg keys: Any?): BrowseChromeState {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val chrome = remember(scroll, density, *keys) { BrowseChromeState({ !scroll.canScrollBackward }, { scroll.firstVisibleItemIndex > 0 }, density) }
    LaunchedEffect(chrome) {
        snapshotFlow { !scroll.canScrollBackward }.collect { if (it) chrome.reset() }
    }
    return chrome
}
