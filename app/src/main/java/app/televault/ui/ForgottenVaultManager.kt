package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.TeleVaultApplication
import kotlinx.coroutines.CancellationException

/** Pending old vaults stay manageable after the replacement becomes active. */
@Composable internal fun ForgottenVaultManager() {
    val app = LocalContext.current.applicationContext as TeleVaultApplication
    val tickets by app.forgottenVaults.tickets.collectAsStateWithLifecycle()
    var account by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        try {
            account = app.telegram.accountId()
            app.forgottenVaults.recover()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { }
    }
    val pending = tickets.filter { it.account == account && it.phase in setOf("SCHEDULING", "PENDING", "STARTED") }
    var expanded by remember { mutableStateOf(false) }
    if (pending.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pending.size > 1) TextButton({ expanded = !expanded }) { Text(label("Scheduled vault deletions", "নির্ধারিত ভল্ট মুছে ফেলা") + " (${pending.size})" + if (expanded) " −" else " +") }
            pending.take(if (expanded) pending.size else 1).forEach { ticket -> key(ticket.id) { ForgottenVaultStatus(ticket.photos, ticket.privateVault, canRequest = false) } }
        }
    }
}
