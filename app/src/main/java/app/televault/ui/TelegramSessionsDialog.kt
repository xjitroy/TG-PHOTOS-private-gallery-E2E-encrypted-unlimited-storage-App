package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.televault.DriveViewModel
import app.televault.security.LocalDeviceAuthentication
import app.televault.telegram.TelegramSessionInfo

/** Account-wide logout is explicit, optional and freshly verified; never automatic on login. */
@Composable
internal fun TelegramSessionsDialog(model: DriveViewModel, close: () -> Unit) {
    val authenticate = LocalDeviceAuthentication.current
    var sessions by remember { mutableStateOf<List<TelegramSessionInfo>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var terminating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) {
        val task = model.activeTelegramSessions { result ->
            if (alive[0]) {
                sessions = result
                loading = false
                failed = result == null
            }
        }
        onDispose {
            alive[0] = false
            task.cancel()
        }
    }
    AlertDialog(onDismissRequest = { if (!terminating) close() }, title = { Text(label("Telegram devices", "Telegram ডিভাইস")) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label("Closing other sessions logs out Telegram mobile, Desktop, Web and other clients across your account. This TG Photos session stays signed in. A new session may be temporarily restricted by Telegram.", "অন্য সেশন বন্ধ করলে আপনার অ্যাকাউন্টের Telegram মোবাইল, Desktop, Web ও অন্য অ্যাপ লগআউট হবে। এই TG Photos সেশন থাকবে। নতুন সেশন হলে Telegram সাময়িক বাধা দিতে পারে।"))
            if (loading) SecureLoader()
            if (failed) Text(label("Could not load active sessions. Check connection and try again.", "সেশন দেখা যায়নি। সংযোগ দেখে আবার চেষ্টা করুন।"), color = MaterialTheme.colorScheme.error)
            sessions.orEmpty().forEach { session ->
                Text(session.application + " · " + session.device + " · " + session.platform + if (session.current) label(" (This session)", " (এই সেশন)") else "", style = MaterialTheme.typography.bodySmall)
            }
            if (confirm) Text(label("Confirm: keep only this app's Telegram session? You will have to sign in again on your other devices.", "নিশ্চিত করুন: শুধু এই অ্যাপের Telegram সেশন রাখবেন? অন্য ডিভাইসে আবার লগইন করতে হবে।"), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton({
            if (!confirm) {
                confirm = true
            } else {
                authenticate {
                    if (alive[0]) {
                        loading = true
                        terminating = true
                        model.terminateOtherTelegramSessions { success ->
                            if (alive[0]) {
                                loading = false
                                terminating = false
                                if (success) close() else failed = true
                            }
                        }
                    }
                }
            }
        }, enabled = !loading && sessions?.any { !it.current } == true) { Text(if (confirm) label("Verify & log out others", "যাচাই করে অন্যগুলো লগআউট") else label("Log out other sessions", "অন্য সেশন লগআউট")) }
    }, dismissButton = { TextButton(close, enabled = !terminating) { Text(label("Cancel", "বাতিল")) } })
}
