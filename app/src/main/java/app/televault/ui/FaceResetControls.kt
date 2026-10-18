package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FaceResetControls(model: DriveViewModel) {
    var confirming by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val resetFailure = label("Reset could not finish. Please retry.", "রিসেট সম্পূর্ণ হয়নি। আবার চেষ্টা করুন।")
    val people by model.people.state.collectAsStateWithLifecycle()
    TextButton(enabled = people.enabled || people.people.isNotEmpty(), onClick = {
        confirming = true
        keep = true
        error = ""
    }) { Text(label("Reset face detection", "ফেস ডিটেকশন রিসেট")) }
    if (confirming) {
        AlertDialog(onDismissRequest = { if (!saving) confirming = false }, title = { Text(label("Reset face detection?", "ফেস ডিটেকশন রিসেট করবেন?")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label("Recheck photos with the installed model. Original photos, videos and your text index are kept.", "ইনস্টল করা মডেলে ছবি আবার পরীক্ষা হবে। মূল ছবি, ভিডিও ও লেখার ইনডেক্স থাকবে।"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(keep, { keep = it }, enabled = !saving)
                    Text(label("Keep names, manual tags and review decisions", "নাম, ম্যানুয়াল ট্যাগ ও রিভিউ রাখুন"), Modifier.weight(1f))
                }
                if (!keep) Text(label("All People groups, names, face tags and This is me will be removed. Encrypted deletion markers sync to Telegram; other devices must sync to apply them.", "সব People গ্রুপ, নাম, ফেস ট্যাগ ও নিজের ফেসের পছন্দ মুছবে। এনক্রিপ্টেড মুছে ফেলার নির্দেশ Telegram-এ সিঙ্ক হবে।"), color = MaterialTheme.colorScheme.error)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try {
                        model.people.resetRecognition(keep)
                        model.requestIntelligence()
                        confirming = false
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        error = resetFailure
                    } finally {
                        saving = false
                    }
                }
            }) { Text(if (saving) label("Resetting…", "রিসেট হচ্ছে…") else label("Reset", "রিসেট")) }
        }, dismissButton = { TextButton(enabled = !saving, onClick = { confirming = false }) { Text(label("Cancel", "বাতিল")) } })
    }
}
