package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.TeleVaultApplication
import app.televault.domain.ForgottenVaultPolicy
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.*

/** Recovery management never asks for an unavailable old password or decrypts old media. */
@Composable internal fun ForgottenVaultStatus(photos: String, privateVault: String? = null, targetName: String = "", canRequest: Boolean = true, showPending: Boolean = true) {
    if (!ForgottenVaultPolicy.validId(photos)) return
    val context = LocalContext.current
    val service = (context.applicationContext as TeleVaultApplication).forgottenVaults
    val tickets by service.tickets.collectAsStateWithLifecycle()
    val ticket = tickets.firstOrNull { it.photos == photos && it.privateVault == privateVault && it.phase !in setOf("CANCELLED", "DONE") }
    var confirm by remember(photos, privateVault) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(photos, privateVault) { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
                message = ""
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = "Could not confirm this change with Telegram. Reconnect and retry; deletion never starts before its full waiting period."
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(photos, privateVault) {
        try {
            service.recover()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { }
    }
    LaunchedEffect(ticket?.id) {
        while (ticket != null) {
            now = System.currentTimeMillis()
            delay(30000)
        }
    }
    if (ticket != null && showPending) {
        AppSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label("Forgotten vault deletion", "ভুলে যাওয়া ভল্ট মুছে ফেলা"), style = MaterialTheme.typography.titleSmall)
                if (ticket.phase == "SCHEDULING") {
                    Text(label("Waiting to register with Telegram. The 24-hour waiting period starts only after server confirmation.", "Telegram-এ নিবন্ধনের অপেক্ষায়। সার্ভার নিশ্চিত করার পরেই ২৪ ঘণ্টার অপেক্ষা শুরু হবে।"), style = MaterialTheme.typography.bodySmall)
                } else {
                    val hours = ((ticket.dueAt - now).coerceAtLeast(0) + 3599999) / 3600000
                    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ticket.dueAt))
                    Text(label("Not before $date · about $hours hours left. Network and Android may delay completion. This vault and its app-owned Telegram copies will be permanently removed.", "$date-এর আগে নয় · আনুমানিক $hours ঘণ্টা বাকি। নেটওয়ার্ক ও Android-এর কারণে পরে শেষ হতে পারে। এই ভল্ট ও অ্যাপের রাখা Telegram কপি স্থায়ীভাবে মুছে যাবে।"), style = MaterialTheme.typography.bodySmall)
                }
                Text(label("Vault", "ভল্ট") + " · " + (privateVault ?: photos).take(8), style = MaterialTheme.typography.labelSmall)
                if (ticket.phase == "PENDING") TextButton({ work { service.cancel(ticket.id) } }, enabled = !busy) { Text(label("Cancel deletion", "মুছে ফেলা বাতিল")) }
                if (ticket.phase == "STARTED") Text(label("Deletion started. It can no longer be cancelled.", "মুছে ফেলা শুরু হয়েছে। এখন আর বাতিল করা যাবে না।"), style = MaterialTheme.typography.bodySmall)
            }
        }
    } else if (ticket == null && canRequest) {
        TextButton({ confirm = true }, enabled = !busy) {
            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label("Delete forgotten vault after 24 hours", "ভুলে যাওয়া ভল্ট ২৪ ঘণ্টা পরে মুছুন"))
        }
    }
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (confirm) {
        AlertDialog(onDismissRequest = { if (!busy) confirm = false }, title = { Text(label("Permanently delete this vault?", "এই ভল্ট স্থায়ীভাবে মুছবেন?")) }, text = {
            Text(targetName.ifBlank { (privateVault ?: photos).take(8) } + "\n\n" + label("Telegram sign-in authorizes a 24-hour waiting period. After it ends, only this vault and its app-owned Telegram files will be permanently deleted. You can cancel before deletion begins. This does not recover the forgotten password.", "Telegram লগইনের মাধ্যমে ২৪ ঘণ্টার অপেক্ষা শুরু হবে। এরপর শুধু এই ভল্ট ও অ্যাপের রাখা তার Telegram ফাইলগুলি স্থায়ীভাবে মুছবে। শুরু হওয়ার আগে বাতিল করতে পারবেন। এতে ভুলে যাওয়া পাসওয়ার্ড উদ্ধার হবে না।"))
        }, confirmButton = {
            TextButton({
                work {
                    service.request(photos, privateVault)
                    confirm = false
                }
            }, enabled = !busy) { Text(label("Schedule deletion", "মুছে ফেলার সময় দিন")) }
        }, dismissButton = { TextButton({ confirm = false }, enabled = !busy) { Text(label("Keep vault", "ভল্ট রাখুন")) } })
    }
}
