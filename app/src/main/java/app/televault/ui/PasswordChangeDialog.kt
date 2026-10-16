package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.crypto.RecoveryPhrasePolicy
import app.televault.security.LocalDeviceAuthentication
import app.televault.security.VaultPasswordManager

@Composable
internal fun PasswordChangeDialog(model: DriveViewModel, close: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val authenticate = LocalDeviceAuthentication.current
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val alive = remember { booleanArrayOf(true) }
    DisposableEffect(Unit) {
        onDispose {
            alive[0] = false
            current = ""
            next = ""
            confirmation = ""
        }
    }
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn), onDismissRequest = { if (!saving) close() }, icon = { Icon(Icons.Outlined.LockReset, null) }, title = { Text(if (reset) label("Reset on this phone", "এই ফোনে রিসেট") else label("Set / change password", "পাসওয়ার্ড সেট / বদলান")) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label("Set a strong password. Any old app-pattern wrapper will be removed from the current Drive. Keep a safe recovery copy; lost passwords cannot be recovered by OTP.", "শক্ত পাসওয়ার্ড দিন। বর্তমান Drive থেকে পুরোনো প্যাটার্ন সরানো হবে। নিরাপদ কপি রাখুন; OTP দিয়ে হারানো পাসওয়ার্ড উদ্ধার হয় না।"), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(model.passwordManagerAccount, {}, readOnly = true, label = { Text(label("Account", "অ্যাকাউন্ট")) }, modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username }, singleLine = true)
            if (!reset) VaultSecretField(current, { current = it }, label("Current password or pattern", "বর্তমান পাসওয়ার্ড বা প্যাটার্ন"), enabled = !saving, fixedPattern = if (state.patternRecoveryAvailable) null else false)
            if (reset) Text(label("Only this already-unlocked, previously set-up phone can reset using its retained vault key. A fresh fingerprint/phone PIN check is required. OTP alone cannot recover a lost key.", "আগে সেটআপ করা এই আনলক করা ফোনের সংরক্ষিত কী দিয়েই রিসেট হবে। আবার ফিঙ্গারপ্রিন্ট/ফোনের PIN লাগবে। হারানো কী শুধু OTP দিয়ে ফেরত পাওয়া যায় না।"), style = MaterialTheme.typography.bodySmall)
            VaultSecretField(next, { next = it }, label("New password", "নতুন পাসওয়ার্ড"), enabled = !saving, newPassword = true, fixedPattern = false)
            VaultSecretField(confirmation, { confirmation = it }, label("Confirm the same password", "একই পাসওয়ার্ড আবার দিন"), enabled = !saving, newPassword = true, error = confirmation.isNotEmpty() && next != confirmation, fixedPattern = false)
            PasswordCreationGuidance(next) {
                next = it
                confirmation = ""
            }
            TextButton({
                reset = !reset
                current = ""
            }, enabled = !saving) { Text(if (reset) label("Use current password instead", "বর্তমান পাসওয়ার্ড ব্যবহার করুন") else label("Forgot current password?", "বর্তমান পাসওয়ার্ড ভুলে গেছেন?")) }
            Text(label("Saving will request Password Manager permission. Other unlocked devices keep access; keep your own recovery copy too.", "সেভ করলে Password Manager-এর অনুমতি চাইবে। অন্য আনলক করা ডিভাইসে প্রবেশাধিকার থাকবে; নিজের রিকভারি কপিও রাখুন।"), style = MaterialTheme.typography.bodySmall)
            if (saving) SecureLoader()
            if (state.notice.isNotBlank()) Text(state.notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        TextButton({
            fun submit() {
                if (!alive[0]) return
                saving = true
                val toSave = next.toCharArray()
                val finished: (Boolean) -> Unit = { success ->
                    if (success) VaultPasswordManager.offerSave(context, model.passwordManagerAccountFor(toSave), toSave, model::report) else toSave.fill('\u0000')
                    if (alive[0]) {
                        saving = false
                        if (success) close()
                    }
                }
                val task = if (reset) model.resetRecoveryPhrase(next.toCharArray(), finished) else model.changeRecoveryPhrase(current.toCharArray(), next.toCharArray(), finished)
                task.invokeOnCompletion { toSave.fill('\u0000') }
            }
            if (reset) authenticate(::submit) else submit()
        }, enabled = !saving && (reset || RecoveryPhrasePolicy.accepts(current)) && app.televault.crypto.NewPasswordPolicy.accepts(next) && next == confirmation && (reset || next != current || state.patternRecoveryAvailable)) { Text(if (reset) label("Verify phone & reset", "ফোন যাচাই করে রিসেট") else label("Save new password", "নতুন পাসওয়ার্ড সেভ")) }
    }, dismissButton = { TextButton(close, enabled = !saving) { Text(label("Cancel", "বাতিল")) } })
}
