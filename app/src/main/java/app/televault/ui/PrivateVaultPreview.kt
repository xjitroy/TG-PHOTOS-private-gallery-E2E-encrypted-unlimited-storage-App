package app.televault.ui

import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.televault.DriveViewModel
import app.televault.data.PrivateVaultItem
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PrivateVaultPreview(item: PrivateVaultItem, uri: Uri, model: DriveViewModel, exportCopy: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val zoom = remember(item.id, uri) { MediaZoomState() }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (window != null && item.mime.startsWith("video/")) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose {}
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(close) { Icon(Icons.Outlined.ArrowBack, label("Close", "বন্ধ")) }
                Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Icon(Icons.Outlined.Lock, null, Modifier.padding(12.dp))
            }
            when {
                item.mime.startsWith("image/") -> {
                    var bitmap by remember(item.id, uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                    val pixels = remember(item.id, uri) { vaultImageSlot() }
                    var failed by remember(item.id) { mutableStateOf(false) }
                    LaunchedEffect(item.id, uri) {
                        try {
                            withContext(Dispatchers.IO) { pixels.offer(decodeVaultImage(context, uri)) }
                            bitmap = pixels.publish()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed = true
                        }
                    }
                    DisposableEffect(item.id, uri) {
                        onDispose {
                            pixels.retire()
                            bitmap = null
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).clipToBounds().mediaZoomInput(zoom), contentAlignment = Alignment.Center) {
                        bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize().mediaZoomLayer(zoom), contentScale = ContentScale.Fit) }
                        if (bitmap == null && !failed) VaultGalleryArtwork(item, uri, ContentScale.Fit)
                        if (bitmap != null) Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { VaultMediaVerification(loading = false, verified = true, doubleEncrypted = item.cloud != null) }
                        if (failed) Text(label("Cannot verify or decode this image safely", "এই ছবি নিরাপদে যাচাই বা ডিকোড করা যায়নি"), color = Color.White)
                    }
                }
                item.mime.startsWith("video/") || item.mime.startsWith("audio/") -> {
                    val player = remember(item.id) {
                        ExoPlayer.Builder(context)
                            .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
                            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(5000, 20000, 750, 1500).setTargetBufferBytes(app.televault.data.MediaMemory.budget(context, 32, 8)).setPrioritizeTimeOverSizeThresholds(false).build())
                            .build().apply {
                                setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
                                setHandleAudioBecomingNoisy(true)
                                setMediaItem(MediaItem.fromUri(uri))
                                prepare()
                                playWhenReady = true
                            }
                    }
                    var buffering by remember(item.id) { mutableStateOf(true) }
                    var authenticated by remember(item.id) { mutableStateOf(false) }
                    var failed by remember(item.id) { mutableStateOf(false) }
                    var duration by remember(item.id) { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
                    var showBuffering by remember(item.id) { mutableStateOf(false) }
                    LaunchedEffect(buffering, duration) {
                        showBuffering = false
                        if (buffering && app.televault.domain.MediaLoadingPolicy.mayShowIndicator(item.mime, duration)) {
                            delay(app.televault.domain.MediaLoadingPolicy.VIDEO_INDICATOR_DELAY)
                            showBuffering = true
                        }
                    }
                    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                    DisposableEffect(player, owner) {
                        val listener = object : androidx.media3.common.Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                buffering = state == androidx.media3.common.Player.STATE_BUFFERING || state == androidx.media3.common.Player.STATE_IDLE
                                duration = player.duration.coerceAtLeast(0)
                                if (state == androidx.media3.common.Player.STATE_READY) authenticated = true
                            }
                            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                                duration = player.duration.coerceAtLeast(0)
                            }
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                failed = true
                                buffering = false
                            }
                        }
                        player.addListener(listener)
                        buffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING || player.playbackState == androidx.media3.common.Player.STATE_IDLE
                        authenticated = player.playbackState == androidx.media3.common.Player.STATE_READY
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                                player.pause()
                                player.clearMediaItems()
                            }
                        }
                        owner.lifecycle.addObserver(observer)
                        onDispose {
                            owner.lifecycle.removeObserver(observer)
                            player.removeListener(listener)
                            player.release()
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                        AndroidView(factory = {
                            PlayerView(it).apply {
                                this.player = player
                                useController = true
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            }
                        }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
                        if (showBuffering && !failed) VaultMediaVerification(loading = true, verified = authenticated, doubleEncrypted = item.cloud != null)
                        if (authenticated && !buffering && !failed) Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { VaultMediaVerification(loading = false, verified = true, doubleEncrypted = item.cloud != null) }
                        if (failed) {
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Outlined.ErrorOutline, null)
                                    Text(label("Playback or integrity check failed. No unverified media is shown.", "প্লেব্যাক বা যাচাই ব্যর্থ হয়েছে। অযাচাই করা মিডিয়া দেখানো হবে না।"), style = MaterialTheme.typography.bodyMedium)
                                    TextButton({
                                        failed = false
                                        authenticated = false
                                        buffering = true
                                        player.prepare()
                                    }) { Text(label("Retry", "আবার চেষ্টা করুন")) }
                                }
                            }
                        }
                    }
                }
                else -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(exportCopy) { Text(label("Download encrypted ZIP", "এনক্রিপ্টেড ZIP ডাউনলোড")) }
                }
            }
        }
    }
}

/** Reports record authentication and encryption layers, never an invented security score. */
@Composable
private fun VaultMediaVerification(loading: Boolean, verified: Boolean, doubleEncrypted: Boolean) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(if (loading) 38.dp else 22.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
                    Icon(if (verified) Icons.Outlined.VerifiedUser else Icons.Outlined.Shield, null, Modifier.size(20.dp), tint = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column {
                    Text(if (doubleEncrypted) label("Double encrypted", "ডবল এনক্রিপ্টেড") else label("Encrypted", "এনক্রিপ্টেড"), style = MaterialTheme.typography.labelMedium)
                    Text(if (loading) label("Loading · checking encrypted chunks", "লোড হচ্ছে · এনক্রিপ্টেড অংশ যাচাই") else label("Chunks authenticated · no disk cache", "অংশ যাচাই হয়েছে · ডিস্ক ক্যাশ নেই"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
