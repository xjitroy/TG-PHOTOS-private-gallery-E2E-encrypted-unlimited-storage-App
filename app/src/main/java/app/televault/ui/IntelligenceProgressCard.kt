package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.IntelligenceRecoveryScheduling
import app.televault.data.MediaIntelligenceScheduling

@Composable
internal fun IntelligenceProgressCard(model: DriveViewModel) {
    val analysis by MediaIntelligenceScheduling.progress.collectAsStateWithLifecycle()
    val status by MediaIntelligenceScheduling.status.collectAsStateWithLifecycle()
    val privacy by model.privacyRestoreProgress.collectAsStateWithLifecycle()
    val recovery by IntelligenceRecoveryScheduling.progress.collectAsStateWithLifecycle()
    val facePack by model.people.models.state.collectAsStateWithLifecycle()
    val textPack by model.ocr.models.state.collectAsStateWithLifecycle()
    val scenePack by model.scenes.models.state.collectAsStateWithLifecycle()
    AppSurface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label("AI progress", "AI কাজের অগ্রগতি"), style = MaterialTheme.typography.titleMedium)
            AiProgressLine(label("Privacy verification", "প্রাইভেসি যাচাই"), privacy.percent, "${privacy.done} / ${privacy.total}")
            if (privacy.phase == "listing") Text(label("Checking encrypted exclusions in cloud…", "ক্লাউডের এনক্রিপ্টেড বাদ দেওয়ার তালিকা দেখা হচ্ছে…"), style = MaterialTheme.typography.bodySmall)
            if (privacy.failure.isNotBlank()) Text("${label("Retry code", "পুনরায় চেষ্টার কোড")}: ${privacy.failure}", style = MaterialTheme.typography.bodySmall)
            if (facePack.busy) AiProgressLine(label("Face model download", "ফেস মডেল ডাউনলোড"), (100 * facePack.downloaded / facePack.total.coerceAtLeast(1)).toInt().coerceIn(0, 100))
            if (textPack.busy) AiProgressLine(label("Text model download", "লেখার মডেল ডাউনলোড"), (100 * textPack.downloaded / textPack.total.coerceAtLeast(1)).toInt().coerceIn(0, 100))
            if (scenePack.busy) AiProgressLine(label("Subject model download", "বিষয়ের মডেল ডাউনলোড"), (100 * scenePack.downloaded / scenePack.total.coerceAtLeast(1)).toInt().coerceIn(0, 100))
            AiProgressLine(label("All photo analysis", "সব ছবির বিশ্লেষণ"), analysis.percent)
            for ((engine, title) in listOf("text" to label("Text", "লেখা"), "face" to label("Faces", "মুখ"), "scene" to label("Subjects", "বিষয়"), "smart" to label("Smart search", "স্মার্ট সার্চ"))) {
                if (engine in analysis.enabled) AiProgressLine(title, analysis.percent(engine), "${analysis.completed[engine] ?: 0} / ${analysis.total}")
            }
            if (recovery.phase != "waiting") AiProgressLine(label("Encrypted result backup", "এনক্রিপ্টেড ফলের ব্যাকআপ"), recovery.percent, "${recovery.done} / ${recovery.total}")
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            Text(label("Counts saved results, including reused results. Unreadable or waiting items remain pending. Model installation and cloud recovery are separate from photo analysis.", "সেভ হওয়া ও আগের ফল পুনর্ব্যবহারের হিসাব। পড়া যায়নি বা অপেক্ষায় থাকা ছবি বাকি থাকবে। মডেল ইনস্টল ও ক্লাউড রিকভারি ছবির বিশ্লেষণ থেকে আলাদা।"), style = MaterialTheme.typography.bodySmall)
            TextButton({ model.requestIntelligence() }) { Text(label("Retry analysis", "বিশ্লেষণ আবার চালান")) }
        }
    }
}

@Composable
private fun AiProgressLine(title: String, percent: Int?, detail: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$title · ${percent?.let { "$it%" } ?: label("Waiting", "অপেক্ষা")}${if (detail.isBlank()) "" else " · $detail"}", style = MaterialTheme.typography.labelMedium)
        if (percent != null) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
    }
}
