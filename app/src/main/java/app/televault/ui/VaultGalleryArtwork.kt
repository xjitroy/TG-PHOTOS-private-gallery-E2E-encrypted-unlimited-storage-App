package app.televault.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.televault.data.PrivateVaultItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val vaultArtworkReads = Semaphore(2)

/** Visible-cell RAM only, wiped on disposal. Never writes a Vault thumbnail to disk. */
@Composable
internal fun VaultGalleryArtwork(item: PrivateVaultItem, uri: Uri, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val pixels = remember(item.id, uri) { vaultImageSlot() }
    var image by remember(item.id, uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, uri) {
        if (item.mime.startsWith("image/")) {
            var delivered = false
            try {
                withContext(Dispatchers.IO) {
                    vaultArtworkReads.withPermit {
                        val bitmap = decodeVaultImage(context, uri, 160 * 160)
                        pixels.offer(bitmap)
                        currentCoroutineContext().ensureActive()
                    }
                }
                image = pixels.publish()
                delivered = true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                image = null
            } finally {
                if (!delivered) {
                    pixels.retire()
                }
            }
        }
    }
    DisposableEffect(item.id, uri) {
        onDispose {
            image = null
            pixels.retire()
        }
    }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(mediaAccentContainer(item.mime), colors.surfaceContainerHigh))), contentAlignment = Alignment.Center) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = contentScale) }
            ?: Icon(if (item.mime.startsWith("video/")) Icons.Outlined.PlayCircle else mediaTypeIcon(item.mime), null, Modifier.size(46.dp), tint = mediaAccent(item.mime))
    }
}
