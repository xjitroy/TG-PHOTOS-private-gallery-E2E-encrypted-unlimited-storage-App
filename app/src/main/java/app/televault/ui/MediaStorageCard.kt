package app.televault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel

@Composable
internal fun MediaStorageCard(model: DriveViewModel) {
    val usage by model.mediaStorage.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refreshMediaStorage() }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label("Smart temporary storage", "স্মার্ট অস্থায়ী স্টোরেজ"), style = MaterialTheme.typography.titleLarge)
            Text(label("Used ${sizeLabel(usage.temporary + usage.native)} · Limit ${sizeLabel(usage.limit)}", "ব্যবহৃত ${sizeLabel(usage.temporary + usage.native)} · সীমা ${sizeLabel(usage.limit)}"), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { ((usage.temporary + usage.native).toFloat() / usage.limit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(512L to "512 MB", 1024L to "1 GB", 3072L to "3 GB", 4096L to "4 GB", 8192L to "8 GB").forEach { (mb, title) ->
                    FilterChip(usage.limit == mb * 1024 * 1024, { model.mediaCacheQuota(mb * 1024 * 1024) }, label = { Text(title) })
                }
            }
            Text(label("Device files open directly, without another full copy. Viewed photos start with 24 hours; repeat views can extend this up to 7 days. Videos start with 24 hours, or 48 hours after repeat viewing. Short videos can warm fully within the storage budget. Long videos buffer about a minute ahead; old cached playback regions outside a 30-minute window are reclaimed. Space limits and Data Saver always apply.", "ডিভাইসের ফাইল সরাসরি খুলবে, আরেকটি পূর্ণ কপি রাখবে না। দেখা ছবি প্রথমে ২৪ ঘণ্টা; বারবার দেখলে সর্বোচ্চ ৭ দিন থাকবে। ভিডিও প্রথমে ২৪ ঘণ্টা, বারবার দেখলে ৪৮ ঘণ্টা। ছোট ভিডিও সীমার মধ্যে পুরো লোড হতে পারে। বড় ভিডিও প্রায় এক মিনিট সামনে বাফার হবে; প্লেব্যাকের ৩০ মিনিটের বাইরের পুরোনো ক্যাশ সরবে। জায়গার সীমা ও Data Saver প্রযোজ্য।"), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            listOf(
                label("Temporary viewed media", "দেখা মিডিয়ার অস্থায়ী কপি") to usage.temporary,
                label("Telegram local transport files", "Telegram-এর লোকাল ট্রান্সফার ফাইল") to usage.native,
                label("Explicit offline downloads", "ইচ্ছা করে রাখা অফলাইন কপি") to usage.offline,
                label("Transfers, exports & share staging", "ট্রান্সফার, এক্সপোর্ট ও শেয়ারের অস্থায়ী ফাইল") to usage.staging,
                label("Encrypted catalogs & Telegram database", "এনক্রিপ্টেড তালিকা ও Telegram ডেটাবেস") to usage.catalog,
                label("Encrypted People & text indexes", "এনক্রিপ্টেড People ও লেখার ইনডেক্স") to usage.derived,
                label("Offline OCR language models", "অফলাইন OCR ভাষার মডেল") to usage.models,
                label("Other private app files", "অ্যাপের অন্য ব্যক্তিগত ফাইল") to usage.other
            ).forEach { (name, bytes) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(sizeLabel(bytes), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(label("The limit is a ceiling, not reserved space or Android's total app data. Offline pins, thumbnails/catalogs and active transfers are separate. Cleanup runs periodically when Android allows it and on use; low storage can reduce retention. Original device/cloud files are preserved.", "সীমা মানে সর্বোচ্চ ব্যবহার; আগে থেকে নেওয়া জায়গা বা Android-এর মোট app data নয়। অফলাইন কপি, থাম্বনেইল/তালিকা ও চলমান ট্রান্সফার আলাদা। Android অনুমতি দিলে নিয়মিত এবং ব্যবহারের সময় পরিষ্কার হবে; জায়গা কম হলে আরও আগে সরবে। আসল ডিভাইস/ক্লাউড ফাইল থাকবে।"), style = MaterialTheme.typography.bodySmall)
            TextButton({ model.refreshMediaStorage() }) { Text(label("Refresh storage usage", "স্টোরেজের হিসাব আপডেট করুন")) }
            TextButton({ confirmClear = true }) { Text(label("Clear temporary viewed media", "দেখা মিডিয়ার অস্থায়ী কপি মুছুন")) }
        }
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(label("Clear temporary media?", "অস্থায়ী মিডিয়া মুছবেন?")) }, text = {
            Text(label("Original device/cloud files, offline downloads, keys and active uploads stay. Temporary playback data is removed; close playback first. Telegram transport cleanup waits for active transfers to finish.", "আসল ডিভাইস/ক্লাউড ফাইল, অফলাইন কপি, কী ও চলমান আপলোড থাকবে। অস্থায়ী প্লেব্যাক ডেটা সরবে—আগে প্লেব্যাক বন্ধ করুন। চলমান ট্রান্সফার শেষ হলে Telegram লোকাল ফাইল পরিষ্কার হবে।"), Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
        }, confirmButton = {
            TextButton({
                confirmClear = false
                model.clearMediaCache()
            }) { Text(label("Clear", "মুছুন")) }
        }, dismissButton = { TextButton({ confirmClear = false }) { Text(label("Cancel", "বাতিল")) } })
    }
}
