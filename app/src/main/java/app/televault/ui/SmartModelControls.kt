package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun SmartModelControls(model: DriveViewModel) {
    val state by model.smartModels.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val choices = model.smartModels.specs

    @Composable fun name(id: String) = choices.find { it.id == id }?.name ?: label("Preparing", "প্রস্তুতি চলছে")
    AppSurface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(label("On-device AI", "ফোনের AI"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(state.enabled, { enabled -> scope.launch(Dispatchers.IO) { model.smartModels.enable(enabled) } })
            }
            Text(name(state.active.ifBlank { state.desired }), style = MaterialTheme.typography.bodyMedium)
            Text(label("Private search and image understanding. Face recognition and exact text reading use their own small engines. Only the selected large model downloads.", "ব্যক্তিগত সার্চ ও ছবি বোঝার AI। মুখ চেনা ও হুবহু লেখা পড়ার ছোট ইঞ্জিনগুলোও থাকবে। শুধু নির্বাচিত বড় মডেলটি ডাউনলোড হবে।"), style = MaterialTheme.typography.bodySmall)
            if (state.busy) {
                if (state.phase == "downloading") {
                    LinearProgressIndicator(progress = { (state.downloaded.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text("${label("Download", "ডাউনলোড")}: ${(100 * state.downloaded / state.total.coerceAtLeast(1)).coerceIn(0,100)}% · ${state.downloaded / 1000000} / ${state.total / 1000000} MB", style = MaterialTheme.typography.labelSmall)
                if (state.phase == "initializing" || state.phase == "verifying") Text(label("Download is complete; installation is still running. Runtime testing has no measurable percentage.", "ডাউনলোড শেষ; ইনস্টল এখনো চলছে। মডেল চালানোর পরীক্ষার শতাংশ মাপা যায় না।"), style = MaterialTheme.typography.labelMedium)
            }
            if (state.failure.isNotBlank()) Text("${label("Technical code", "সমস্যার কোড")}: ${state.failure}", style = MaterialTheme.typography.labelSmall)
            if (state.notice.isNotBlank())Text(state.notice, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton({ expanded = !expanded }) { Text(label(if (expanded)"Hide models" else "Choose model", if (expanded)"মডেল লুকান" else "মডেল বাছুন")) }
                if (state.busy) {
                    TextButton({ model.smartModels.cancel() }) { Text(label("Pause", "থামান")) }
                } else {
                    TextButton({ model.smartModels.retry() }) { Text(label("Retry / resume", "আবার চেষ্টা")) }
                }
            }
            if (expanded) {
                Text(label("The current model stays until the replacement downloads, verifies and runs successfully. Then the previous large model is deleted. Face/OCR packs stay. Extra working space is needed while switching.", "নতুন মডেল সম্পূর্ণ ডাউনলোড, যাচাই ও চালু হলে তবেই পুরোনো বড় মডেল মুছবে। ফেস/OCR প্যাক থাকবে। বদলের সময় অতিরিক্ত খালি জায়গা প্রয়োজন।"), style = MaterialTheme.typography.bodySmall)
                TextButton({ scope.launch(Dispatchers.IO) { model.smartModels.select("auto") } }, enabled = !state.busy) { Text((if (state.choice == "auto")"✓ " else "") + label("Automatic · suited to this phone", "অটোমেটিক · ফোন অনুযায়ী")) }
                choices.forEach { spec ->
                    TextButton({ scope.launch(Dispatchers.IO) { model.smartModels.select(spec.id) } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text((if (state.choice == spec.id)"✓ " else "") + "${spec.name} · ${(spec.bytes + 500000) / 1000000} MB", modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(label("Auto: up to 3 GB → Small; 4–6 GB → Balanced; 8–12 GB → EmbeddingGemma 2 full; over 12 GB → Qwen. Available memory can trigger a small-model fallback. ARM64 required. Model accuracy varies.", "অটো: ৩ GB পর্যন্ত Small; ৪–৬ GB Balanced; ৮–১২ GB পূর্ণ EmbeddingGemma 2; ১২ GB-এর বেশি Qwen। খালি RAM কমলে ছোট মডেলে ফিরতে পারে। ARM64 প্রয়োজন। শনাক্তকরণে ভুল হতে পারে।"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
