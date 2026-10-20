package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel

@Composable
internal fun OcrEnableDialog(model: DriveViewModel, close: () -> Unit, enabled: () -> Unit = {}) {
    AlertDialog(onDismissRequest = close, icon = { Icon(Icons.Outlined.TextFields, null) }, title = { Text(label("Read text on this phone", "ফোনেই লেখা পড়ুন")) }, text = { Text(label("Bengali, Hindi, English, Marathi, Tamil, Telugu, Arabic and Spanish. A one-time 28.5 MB language download is needed. OCR runs on this phone; detected text is stored encrypted for search. Automatic analysis has no app battery or Wi-Fi pause. Ordinary per-photo recovery is end-to-end encrypted; detected documents stay on this phone. Long-press a photo to copy its text. Recognition can make mistakes. Private Vault is excluded.", "বাংলা, হিন্দি, ইংরেজি, মারাঠি, তামিল, তেলুগু, আরবি ও স্প্যানিশ। একবার ২৮.৫ MB ভাষার মডেল ডাউনলোড হবে। লেখা এই ফোনেই পড়া হবে এবং সার্চের জন্য এনক্রিপ্টেড থাকবে। অটো বিশ্লেষণে অ্যাপের ব্যাটারি বা Wi-Fi বাধা নেই। সাধারণ ছবির ফল end-to-end এনক্রিপ্টেডভাবে রিকভার হবে; শনাক্ত হওয়া নথির ফল ফোনেই থাকবে। ছবিতে লং-প্রেস করে লেখা কপি করুন। শনাক্তকরণে ভুল হতে পারে। Private Vault বাদ থাকবে।")) }, confirmButton = {
        TextButton({
            close()
            model.peopleAction {
                model.ocr.enable(true)
                enabled()
            }
        }) { Text(label("Enable & download", "চালু ও ডাউনলোড")) }
    }, dismissButton = { TextButton(close) { Text(label("Not now", "এখন নয়")) } })
}

@Composable
internal fun TextSearchControls(model: DriveViewModel) {
    val text by model.ocr.state.collectAsStateWithLifecycle()
    val pack by model.ocr.models.state.collectAsStateWithLifecycle()
    var consent by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    AppSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TextFields, null, Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(label("Search text in photos", "ছবির লেখা খুঁজুন"), style = MaterialTheme.typography.labelLarge)
                    Text(if (pack.busy) "${pack.name} · ${(100 * pack.downloaded / pack.total.coerceAtLeast(1)).coerceIn(0,100)}%" else label("8 languages · ${text.indexed} photos indexed", "৮টি ভাষা · ${text.indexed}টি ছবি পড়া হয়েছে"), style = MaterialTheme.typography.labelSmall)
                }
                Switch(text.enabled, {
                    if (it) {
                        consent = true
                    } else {
                        model.ocr.models.cancel()
                        model.peopleAction { model.ocr.enable(false) }
                    }
                })
            }
            TextButton({ expanded = !expanded }) { Text(label(if (expanded) "Hide options" else "Text search options", if (expanded) "অপশন লুকান" else "টেক্সট সার্চ অপশন")) }
            if (text.enabled && expanded) {
                Row {
                    TextButton({
                        model.ocr.models.install()
                        model.indexText(model.state.value.files, model.localMedia.changes.value)
                    }) { Text(label("Retry scan", "আবার স্ক্যান")) }
                    TextButton({ clearing = true }) { Text(label("Clear text index", "লেখার ইনডেক্স মুছুন")) }
                }
            }
            if (expanded && pack.notice.isNotBlank()) Text(pack.notice, style = MaterialTheme.typography.bodySmall)
            if (expanded && text.notice.isNotBlank()) Text(text.notice, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (consent) OcrEnableDialog(model, { consent = false })
    if (clearing) {
        AlertDialog(onDismissRequest = { clearing = false }, title = { Text(label("Clear detected text?", "শনাক্ত লেখা মুছবেন?")) }, text = { Text(label("Only this vault's encrypted text index is removed and automatic OCR is paused. Original photos, People and downloaded language models remain.", "শুধু এই ভল্টের এনক্রিপ্টেড লেখার ইনডেক্স মুছবে এবং অটো OCR থামবে। মূল ছবি, People ও ভাষার মডেল থাকবে।")) }, confirmButton = {
            TextButton({
                clearing = false
                model.peopleAction { model.ocr.clear() }
            }) { Text(label("Clear", "মুছুন")) }
        }, dismissButton = { TextButton({ clearing = false }) { Text(label("Cancel", "বাতিল")) } })
    }
}
