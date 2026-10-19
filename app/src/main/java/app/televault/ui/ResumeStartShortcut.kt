package app.televault.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class BrowseStartTarget(val ready: () -> Boolean, val positioned: () -> Boolean, val scrolling: () -> Boolean, val start: suspend () -> Unit)

/** One foreground offer, bound to the exact collection and scroll owner. Old timers cannot revive it. */
internal class ResumeStartWindow {
    data class Offer(val token: Long, val address: Any, val target: BrowseStartTarget, val expiresAt: Long)
    var offer by mutableStateOf<Offer?>(null)
        private set
    private var sequence = 0L
    fun open(address: Any, target: BrowseStartTarget, now: Long): Offer = Offer(++sequence, address, target, now + 10_000).also { offer = it }
    fun clear(token: Long? = offer?.token) {
        if (offer?.token == token) offer = null
    }
    fun current(address: Any, target: BrowseStartTarget?, now: Long): Offer? = offer?.takeIf { it.address == address && it.target === target && now < it.expiresAt && now >= it.expiresAt - 10_000 }
}

internal class BrowseStartHost {
    private val targets = mutableStateListOf<BrowseStartTarget>()
    val active: BrowseStartTarget? get() = targets.lastOrNull()
    val window = ResumeStartWindow()
    fun add(target: BrowseStartTarget) {
        targets.add(target)
    }
    fun remove(target: BrowseStartTarget) {
        targets.remove(target)
    }
}
internal val LocalBrowseStartHost = staticCompositionLocalOf<BrowseStartHost?> { null }

@Composable
internal fun RegisterBrowseStartTarget(ready: () -> Boolean, positioned: () -> Boolean, scrolling: () -> Boolean, start: suspend () -> Unit) {
    val host = LocalBrowseStartHost.current ?: return
    val latestReady by rememberUpdatedState(ready)
    val latestPositioned by rememberUpdatedState(positioned)
    val latestScrolling by rememberUpdatedState(scrolling)
    val latestStart by rememberUpdatedState(start)
    val target = remember(host) { BrowseStartTarget({ latestReady() }, { latestPositioned() }, { latestScrolling() }, { latestStart() }) }
    DisposableEffect(host, target) {
        host.add(target)
        onDispose { host.remove(target) }
    }
}

@Composable
internal fun ResumeStartOffers(host: BrowseStartHost, enabled: Boolean, address: Any, eligible: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestAddress by rememberUpdatedState(address)
    val latestEligible by rememberUpdatedState(eligible)
    LaunchedEffect(host, lifecycle, enabled) {
        host.window.clear()
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val origin = latestAddress
            try {
                // Allow list registration/layout before deciding; no delay is added to navigation.
                withFrameNanos { }
                val result = withTimeoutOrNull(15_000) {
                    snapshotFlow {
                        if (latestAddress != origin || !latestEligible) {
                            true to null
                        } else {
                            val target = host.active
                            (target != null && target.ready() && !target.scrolling()) to target
                        }
                    }.first { it.first }
                }
                val target = result?.second
                if (latestAddress == origin && latestEligible && target?.positioned() == true) {
                    val offer = host.window.open(origin, target, SystemClock.elapsedRealtime())
                    delay(10_000)
                    host.window.clear(offer.token)
                }
            } finally {
                host.window.clear()
            }
        }
    }
    LaunchedEffect(host, address, eligible, host.active) {
        val offer = host.window.offer
        if (offer != null && (!enabled || !eligible || offer.address != address || offer.target !== host.active)) host.window.clear(offer.token)
    }
}

@Composable
internal fun ResumeStartButton(address: Any, eligible: Boolean, beforeStart: () -> Unit, modifier: Modifier = Modifier) {
    val host = LocalBrowseStartHost.current ?: return
    val scope = rememberCoroutineScope()
    val offer = host.window.current(address, host.active, SystemClock.elapsedRealtime())
    AnimatedVisibility(eligible && offer != null, modifier, enter = fadeIn(tween(140)), exit = fadeOut(tween(100))) {
        val title = label("Back to section start", "এই সেকশনের শুরুতে ফিরুন")
        AppSurface(onClick = {
            val current = host.window.current(address, host.active, SystemClock.elapsedRealtime()) ?: return@AppSurface
            host.window.clear(current.token)
            beforeStart()
            scope.launch { current.target.start() }
        }, modifier = Modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(Icons.Outlined.VerticalAlignTop, title, Modifier.size(22.dp)) }
        }
    }
}

internal suspend fun LazyListState.softScrollToStart() {
    if (layoutInfo.totalItemsCount == 0) return
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    if (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > viewport) scrollToItem(0, (viewport * .55f).toInt())
    animateScrollToItem(0)
}

internal suspend fun LazyGridState.softScrollToStart() {
    if (layoutInfo.totalItemsCount == 0) return
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(1)
    if (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > viewport) scrollToItem(0, (viewport * .55f).toInt())
    animateScrollToItem(0)
}
