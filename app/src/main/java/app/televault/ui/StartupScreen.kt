package app.televault.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.televault.RuntimeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun StartupScreen(failed: Boolean, retry: () -> Unit) {
    val context = LocalContext.current
    var slow by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(8000)
        slow = true
    }
    val report by produceState("", slow, failed) {
        if (slow || failed) value = withContext(Dispatchers.IO) { RuntimeDiagnostics.report(context.applicationContext) }
    }
    AppSurface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (failed) "TG Photos could not start" else "Opening TG Photos", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            if (!failed) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                if (failed) {
                    "Check available storage, then retry. Your library, sign-in and passwords have not been reset."
                } else if (slow) {
                    "Opening is taking longer than usual. Keep your app data; you can copy diagnostics below."
                } else {
                    "Preparing your encrypted library…"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (failed) TextButton(retry) { Text("Retry") }
            if (report.isNotBlank()) {
                TextButton({
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TG Photos startup diagnostics", report))
                    copied = true
                }) { Text(if (copied) "Diagnostics copied" else "Copy startup diagnostics") }
            }
        }
    }
}
