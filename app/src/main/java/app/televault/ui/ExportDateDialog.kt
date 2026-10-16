package app.televault.ui

import android.app.DatePickerDialog
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.televault.domain.ExportDateRange
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Two readable full-width fields; a native single-date picker never nests a wide range calendar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportDateDialog(from: Long, until: Long, zoneId: String, dismiss: () -> Unit, apply: (Long, Long) -> Unit) {
    val context = LocalContext.current
    val today = remember(zoneId) { LocalDate.now(ZoneId.of(zoneId)).toEpochDay() }
    val initial = remember(from, until, zoneId) { ExportDateRange.initial(from, until, zoneId, today) }
    var start by rememberSaveable { mutableLongStateOf(initial[0]) }
    var end by rememberSaveable { mutableLongStateOf(initial[1]) }
    var picking by rememberSaveable { mutableIntStateOf(0) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fromLabel = label("Start date", "শুরুর তারিখ")
    val untilLabel = label("End date", "শেষ তারিখ")
    fun preset(days: LongArray) {
        start = days[0]
        end = days[1]
    }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 12.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(label("Choose dates", "তারিখ বাছুন")) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7 to label("Last 7 days", "শেষ ৭ দিন"), 30 to label("Last 30 days", "শেষ ৩০ দিন")).forEach { (count, title) ->
                        val days = ExportDateRange.lastDays(today, count)
                        FilterChip(start == days[0] && end == days[1], { preset(days) }, { Text(title) })
                    }
                    val month = ExportDateRange.thisMonth(today)
                    FilterChip(start == month[0] && end == month[1], { preset(month) }, { Text(label("This month", "এই মাস")) })
                }
                ExportDateField(fromLabel, start) { picking = 1 }
                ExportDateField(untilLabel, end) { picking = 2 }
                Text("${end - start + 1} " + label("days · Both dates included", "দিন · দুই তারিখই অন্তর্ভুক্ত"), style = MaterialTheme.typography.bodyMedium)
                Text(label("Dates use ", "তারিখের টাইমজোন: ") + zoneId, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton({
                val window = ExportDateRange.window(start, end, zoneId)
                apply(window[0], window[1])
            }, enabled = start <= end) { Text(label("Apply", "প্রয়োগ")) }
        },
        dismissButton = { TextButton(dismiss) { Text(label("Cancel", "বাতিল")) } }
    )
    if (picking != 0) {
        val title = if (picking == 1) fromLabel else untilLabel
        DisposableEffect(picking, dark, zoneId) {
            val target = picking
            val selected = LocalDate.ofEpochDay(if (target == 1) start else end)
            val theme = if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
            val dialog = DatePickerDialog(context, theme, { _, year, month, day ->
                val chosen = LocalDate.of(year, month + 1, day).toEpochDay()
                if (target == 1) {
                    start = chosen
                    end = maxOf(end, chosen)
                } else {
                    end = chosen
                }
                picking = 0
            }, selected.year, selected.monthValue - 1, selected.dayOfMonth)
            // Android's native picker uses phone-calendar days. Convert only the final selection in the saved export timezone.
            val deviceZone = ZoneId.systemDefault()
            val minimum = if (target == 2) start else ExportDateRange.MIN_DAY
            dialog.datePicker.minDate = LocalDate.ofEpochDay(minimum).atStartOfDay(deviceZone).toInstant().toEpochMilli()
            dialog.datePicker.maxDate = LocalDate.ofEpochDay(ExportDateRange.MAX_DAY).atStartOfDay(deviceZone).toInstant().toEpochMilli()
            dialog.setTitle(title)
            val parentFlags = videoActivity(context)?.window?.attributes?.flags ?: 0
            if (parentFlags and WindowManager.LayoutParams.FLAG_SECURE != 0) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.setOnDismissListener { picking = 0 }
            dialog.show()
            onDispose {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
            }
        }
    }
}

@Composable
private fun ExportDateField(title: String, day: Long, choose: () -> Unit) {
    OutlinedCard(onClick = choose, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(LocalDate.ofEpochDay(day)), style = MaterialTheme.typography.titleMedium)
            }
            Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
