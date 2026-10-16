package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import kotlinx.coroutines.delay

/** Inform once per fresh catalog recovery; progress remains in the fixed header slot. */
@Composable internal fun CloudRestoreNotice(model: DriveViewModel) {
    val restore by model.cloudRestore.collectAsStateWithLifecycle()
    var shownScope by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(restore?.scope, restore?.firstRestore) {
        val status = restore
        if (status?.firstRestore == true && shownScope != status.scope) {
            shownScope = status.scope
            visible = true
        }
    }
    LaunchedEffect(shownScope) {
        if (shownScope.isNotBlank()) {
            delay(9000)
            visible = false
        }
    }
    if (visible) {
        Popup(Alignment.BottomCenter) {
            Snackbar(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), action = {
                TextButton({ visible = false }) { Text(label("Got it", "ঠিক আছে")) }
            }) {
                Text(label("Your files, photos, videos and thumbnails are returning. A large library may take 10–15 minutes or longer, depending on your connection. You can keep browsing; restoration continues in the background.", "আপনার ফাইল, ছবি, ভিডিও ও থাম্বনেইল ফিরে আসছে। বড় লাইব্রেরিতে সংযোগ অনুযায়ী ১০–১৫ মিনিট বা বেশি লাগতে পারে। ছবি দেখা চালিয়ে যান; পেছনে কাজ চলবে।"))
            }
        }
    }
}
