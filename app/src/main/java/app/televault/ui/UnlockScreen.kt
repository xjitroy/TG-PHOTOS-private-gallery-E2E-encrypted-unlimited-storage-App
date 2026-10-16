package app.televault.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.televault.R

/** App-owned presentation behind Android's protected biometric/device-credential prompt. */
@Composable
internal fun UnlockScreen(unlock: () -> Unit, busy: Boolean = false, notice: String = "", recovery: (() -> Unit)? = null, back: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(if (entered) 1f else .9f, tween(240), label = "Unlock entrance")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.background, colors.primaryContainer.copy(alpha = .28f), colors.background)))) {
        back?.let { IconButton(it, Modifier.safeDrawingPadding().padding(12.dp).align(Alignment.TopStart)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, label("Back", "ফিরুন")) } }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Spacer(Modifier.height(24.dp))
                Image(
                    painterResource(R.drawable.tg_photos_logo),
                    label("TG Photos logo", "TG Photos লোগো"),
                    Modifier.size(72.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                )
                Spacer(Modifier.height(20.dp))
                Text("TG Photos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Private Gallery & Encrypted | Unlimited Storage", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(18.dp))
                Surface(
                    shape = CircleShape,
                    color = colors.primaryContainer,
                    modifier = Modifier.size(64.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Fingerprint, null, Modifier.size(36.dp), tint = colors.onPrimaryContainer) }
                }
                Spacer(Modifier.height(24.dp))
                Text(label("Your memories.\nJust for you.", "আপনার স্মৃতি।\nশুধুই আপনার।"), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Text(label("Verify with your fingerprint, face or phone PIN to open your private gallery.", "প্রাইভেট গ্যালারি খুলতে ফিঙ্গারপ্রিন্ট, ফেস আনলক অথবা ফোনের PIN ব্যবহার করুন।"), color = colors.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(30.dp))
                if (notice.isNotBlank()) Text(notice, Modifier.padding(top = 16.dp), textAlign = TextAlign.Center, color = colors.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(label("Protected on your device", "আপনার ডিভাইসেই সুরক্ষিত"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(28.dp))
            }
            Button(unlock, Modifier.fillMaxWidth().height(60.dp), enabled = !busy, shape = RoundedCornerShape(24.dp)) {
                if (busy) SecureLoader(Modifier.size(22.dp)) else Icon(Icons.Outlined.Fingerprint, null)
                Spacer(Modifier.width(10.dp))
                Text(if (busy) label("Waiting for verification…", "যাচাইয়ের অপেক্ষায়…") else label("Unlock gallery", "গ্যালারি আনলক করুন"), style = MaterialTheme.typography.titleMedium)
            }
            recovery?.let { TextButton(it, Modifier.padding(top = 10.dp)) { Text(label("Use main / recovery password", "মূল / রিকভারি পাসওয়ার্ড দিন")) } }
        }
    }
}
