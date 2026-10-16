package app.televault.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.televault.DriveViewModel
import kotlinx.coroutines.Job

/** A fresh phrase check; disposing/cancelling the dialog invalidates its result. */
@Composable
internal fun PasswordTrashConfirmation(model: DriveViewModel, count: Int, cancel: () -> Unit, confirmed: () -> Unit, allCloud: Boolean = false) {
    val state by model.state.collectAsState()
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var queueFailed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val alive = remember { booleanArrayOf(true) }
    var verification by remember { mutableStateOf<Job?>(null) }
    val complete by rememberUpdatedState(confirmed)
    fun dismiss() {
        alive[0] = false
        verification?.cancel()
        password = ""
        cancel()
    }
    DisposableEffect(Unit) {
        onDispose {
            alive[0] = false
            verification?.cancel()
            password = ""
        }
    }
    AlertDialog(properties = androidx.compose.ui.window.DialogProperties(securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn), onDismissRequest = ::dismiss, title = { Text(if (allCloud) label("Move all cloud backups to Trash?", "সব ক্লাউড ব্যাকআপ ট্র্যাশে রাখবেন?") else label("Delete permanently?", "স্থায়ীভাবে মুছবেন?")) }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            Text(if (allCloud) label("$count cloud files will move to Trash after your main password is verified. Device originals stay. 24-hour protection will be enabled; restore anytime. Follow progress in Library → Transfers.", "মূল পাসওয়ার্ড যাচাইয়ের পর ${count}টি ক্লাউড ফাইল ট্র্যাশে যাবে। ফোনের মূল ফাইল থাকবে। ২৪ ঘণ্টার সুরক্ষা চালু হবে; যেকোনো সময় ফিরিয়ে আনুন। Library → Transfers-এ অগ্রগতি দেখুন।") else label("$count items will be permanently deleted from the selected trash. Enter your main vault password. Android may also ask permission for device files.", "নির্বাচিত ট্র্যাশের ${count}টি আইটেম স্থায়ীভাবে মুছবে। মূল ভল্ট পাসওয়ার্ড দিন। ফোনের ফাইলের জন্য Android-ও অনুমতি চাইতে পারে।"))
            Spacer(Modifier.height(16.dp))
            VaultSecretField(password, {
                password = it
                failed = false
                queueFailed = false
            }, label("Main password", "মূল পাসওয়ার্ড"), enabled = !checking, error = failed, fixedPattern = if (state.patternRecoveryAvailable) null else false)
            if (failed) Text(label("Incorrect password. Nothing was deleted.", "পাসওয়ার্ড সঠিক নয়। কিছুই মোছা হয়নি।"), color = MaterialTheme.colorScheme.error)
            if (queueFailed) Text(label("Could not queue the operation. Check available space and try again; nothing was permanently deleted.", "কাজটি কিউতে রাখা যায়নি। খালি জায়গা দেখে আবার চেষ্টা করুন; কিছুই স্থায়ীভাবে মোছা হয়নি।"), color = MaterialTheme.colorScheme.error)
            if (checking) SecureLoader()
        }
    }, confirmButton = {
        TextButton({
            checking = true
            val phrase = password.toCharArray()
            password = ""
            val result: (Boolean?) -> Unit = { verified ->
                if (alive[0]) {
                    checking = false
                    failed = verified == false
                    queueFailed = verified == null
                    if (verified == true) {
                        alive[0] = false
                        complete()
                    }
                }
            }
            verification = if (allCloud) model.trashAllCloud(phrase, result) else model.verifyRecoveryPhrase(phrase) { result(it) }
        }, enabled = password.isNotBlank() && !checking) { Text(if (allCloud) label("Verify and move to Trash", "যাচাই করে ট্র্যাশে রাখুন") else label("Delete permanently", "স্থায়ীভাবে মুছুন")) }
    }, dismissButton = { TextButton(::dismiss) { Text(label("Cancel", "বাতিল")) } })
}
