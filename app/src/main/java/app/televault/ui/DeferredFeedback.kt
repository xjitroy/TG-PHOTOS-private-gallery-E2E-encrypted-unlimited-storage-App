package app.televault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Feedback waits, never the operation or its result. Ending/replacing a request cancels its timer. */
@Composable
internal fun rememberLoadingFeedback(pending: Boolean, request: Any? = Unit): Boolean {
    var visible by remember(pending, request) { mutableStateOf(false) }
    LaunchedEffect(pending, request) {
        if (pending) {
            delay(450)
            visible = true
        }
    }
    return pending && visible
}

/** The same quiet surface spans service/session/receipt/key discovery; no password UI is implied. */
@Composable
internal fun QuietStartupGate() {
    val show = rememberLoadingFeedback(true)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        if (show) {
            Column(Modifier.safeDrawingPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(label("Opening your library…", "আপনার লাইব্রেরি খুলছে…"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
