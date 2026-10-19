package app.televault.ui

import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class FeatureCard(val icon: ImageVector, val title: String, val body: String)

/** One feature at a time, before the separate Telegram-file safety acknowledgement. */
@Composable
internal fun FeatureIntroduction(finish: suspend () -> Unit) {
    val cards = listOf(
        FeatureCard(Icons.Outlined.Cloud, label("Your gallery. Your cloud.", "আপনার গ্যালারি। আপনার ক্লাউড।"), label("Photos, Albums, Files and a separate Private Vault in one app. TG Photos adds no cloud storage quota: backups use your Telegram Saved Messages. Telegram rules, service availability and network limits still apply.", "এক অ্যাপে Photos, Albums, Files এবং আলাদা Private Vault। TG Photos ক্লাউড স্টোরেজের কোটা বসায় না: ব্যাকআপ আপনার Telegram Saved Messages-এ থাকে। Telegram-এর নিয়ম, পরিষেবার প্রাপ্যতা ও নেটওয়ার্কের সীমা প্রযোজ্য।")),
        FeatureCard(Icons.Outlined.Lock, label("Encrypted before upload", "আপলোডের আগেই এনক্রিপ্টেড"), label("Files are encrypted on your phone before being backed up. Keep your vault password and recovery keys safe. Telegram login and your encrypted gallery credentials have different roles.", "ব্যাকআপের আগে ফাইল ফোনেই এনক্রিপ্ট হয়। ভল্টের পাসওয়ার্ড ও রিকভারি কী নিরাপদে রাখুন। Telegram লগইন ও এনক্রিপ্টেড গ্যালারির পরিচয়পত্রের কাজ আলাদা।")),
        FeatureCard(Icons.Outlined.Folder, label("A separate Private Vault", "আলাদা Private Vault"), label("Private Vault has its own mandatory lock and an additional encryption layer. It locks when you leave or background it. Its contents are excluded from face, text and subject analysis.", "Private Vault-এর নিজস্ব বাধ্যতামূলক লক ও বাড়তি এনক্রিপশন স্তর আছে। বেরোলে বা ব্যাকগ্রাউন্ডে গেলে লক হবে। এর ফাইলে face, text ও subject analysis হয় না।")),
        FeatureCard(Icons.Outlined.CloudUpload, label("Backup on your terms", "আপনার পছন্দমতো ব্যাকআপ"), label("After setup, choose whether to enable automatic Camera backup and add other folders. Limited data starts at 250 MB/day, photos first, then Camera videos up to 2 minutes on mobile data. Full backup requires your unlimited-internet choice. Manual Upload and downloads have no daily app data cap.", "সেটআপের পরে Camera অটো ব্যাকআপ চালু করবেন কি না বাছুন, অন্য ফোল্ডারও যোগ করতে পারবেন। সীমিত ডেটায় ডিফল্ট দৈনিক ২৫০ MB, আগে ছবি, পরে মোবাইল ডেটায় ২ মিনিট পর্যন্ত ক্যামেরা ভিডিও। Full backup-এর জন্য আনলিমিটেড ইন্টারনেটের পছন্দ নিশ্চিত করবেন। Manual Upload ও ডাউনলোডে অ্যাপের দৈনিক ডেটা সীমা নেই।")),
        FeatureCard(Icons.Outlined.TextFields, label("Find and copy photo text", "ছবির লেখা খুঁজুন ও কপি করুন"), label("On-device text recognition makes supported photo text searchable and copyable. Open a photo to use Text, or hold still to select words. Required verified model packs download automatically; processing stays on this phone.", "অন-ডিভাইস text recognition দিয়ে সমর্থিত ছবির লেখা সার্চ ও কপি করা যায়। ছবি খুলে Text ব্যবহার করুন, বা স্থির ধরে শব্দ বাছুন। প্রয়োজনীয় যাচাই করা মডেল প্যাক অটোমেটিক ডাউনলোড হবে; প্রসেসিং এই ফোনেই হবে।")),
        FeatureCard(Icons.Outlined.Face, label("People, recognised on this phone", "এই ফোনেই People চিনবে"), label("Name people and review suggested matches. Face processing runs on-device; confirmed references use your encrypted backup. Reviews need matching confirmation, and mistakes can still happen. Private Vault stays excluded.", "মানুষের নাম দিন এবং সম্ভাব্য মিল রিভিউ করুন। ফেস প্রসেসিং ফোনেই হয়; নিশ্চিত করা রেফারেন্স এনক্রিপ্টেড ব্যাকআপে যায়। রিভিউতে মিলিয়ে নিশ্চিত করতে হয়; ভুল হতেই পারে। Private Vault বাদ থাকে।")),
        FeatureCard(Icons.Outlined.Search, label("One search. More ways to remember.", "এক সার্চে আরও সহজে খুঁজুন"), label("Combine names, dates, photo text, people, file types and on-device subject suggestions. Memories bring selected moments together. Fully viewed stories dim subtly; new story contents appear fresh again.", "নাম, তারিখ, ছবির লেখা, People, ফাইলের ধরন ও ফোনে তৈরি subject suggestion একসঙ্গে সার্চ করুন। Memories-এ বাছাই মুহূর্ত একসঙ্গে আসে। পুরো দেখা স্টোরি হালকা dim হবে; নতুন ছবি এলে আবার নতুন দেখাবে।")),
        FeatureCard(Icons.Outlined.PlayCircle, label("Shorts and wide-screen video", "Shorts ও বড় পর্দার ভিডিও"), label("Swipe through Shorts or open normal videos and movies with playback controls, speed, subtitles and supported audio tracks. Tap to reveal hidden controls. A thumbnail strip keeps nearby files within reach.", "Shorts সোয়াইপ করে দেখুন, বা playback control, speed, subtitle ও সমর্থিত audio track দিয়ে ভিডিও ও মুভি চালান। লুকানো কন্ট্রোল দেখাতে ট্যাপ করুন। থাম্বনেইল বার দিয়ে কাছের ফাইল দ্রুত পাবেন।")),
        FeatureCard(Icons.Outlined.Description, label("Your files, ready to use", "আপনার ফাইল, সহজে ব্যবহার করুন"), label("Browse supported photos, videos, audio, PDFs, text and other files. Preview an audio cut before saving a new copy. Use Android Open with or Share to view or manually back up selected files; unsupported formats keep their external-app option.", "সমর্থিত ফটো, ভিডিও, অডিও, PDF, text ও অন্য ফাইল দেখুন। অডিও কাটার আগে বাছাই অংশ শুনে নতুন কপি সেভ করুন। Android-এর Open with বা Share দিয়ে ফাইল দেখুন বা ম্যানুয়ালি ব্যাকআপ করুন; অসমর্থিত ফরম্যাট অন্য অ্যাপে খোলার অপশন থাকবে।")),
        FeatureCard(Icons.Outlined.TouchApp, label("Make it yours", "নিজের মতো ব্যবহার করুন"), label("Choose Glass or Material You, Light, Dark or System. Settings control app locking, automatic backup, return to your last folder and the viewed-item highlight. Helpful hand guides appear for supported repeated mistakes, and can be switched off. Next, learn which Telegram files you must keep.", "Glass বা Material You, Light, Dark বা System বাছুন। Settings থেকে app lock, অটো ব্যাকআপ, শেষ ফোল্ডারে খোলা ও দেখা ফাইলের highlight নিয়ন্ত্রণ করুন। নির্দিষ্ট ভুল বারবার হলে সহায়ক হাত দেখাবে; সেটিও বন্ধ করা যায়। এরপর জানুন Telegram-এর কোন ফাইলগুলি রাখতে হবে।"))
    )
    var page by rememberSaveable { mutableIntStateOf(0) }
    page = page.coerceIn(cards.indices)
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val gate = remember { FeatureTourGate() }
    val scope = rememberCoroutineScope()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(page, lifecycle, attempt) {
        ready = false
        gate.pause()
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            gate.show(page, SystemClock.elapsedRealtime())
            delay(1000)
            ready = gate.ready(page, SystemClock.elapsedRealtime())
        }
    }
    val height = (LocalConfiguration.current.screenHeightDp * .88f).dp
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AppSurface(Modifier.fillMaxWidth(.92f).widthIn(max = 560.dp).heightIn(max = height), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("TG Photos · ${page + 1}/${cards.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { (page + 1f) / cards.size }, modifier = Modifier.fillMaxWidth().height(4.dp))
                AnimatedContent(page, Modifier.weight(1f, fill = false), transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally { it / 12 }).togetherWith(fadeOut(tween(110))) }, label = "Feature card") { index ->
                    val card = cards[index]
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        AppSurface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(card.icon, null, Modifier.padding(18.dp).size(42.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        Text(card.title, style = MaterialTheme.typography.headlineSmall)
                        Text(card.body, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (saveError) Text(label("Could not save. Please try Continue again.", "সেভ হয়নি। আবার Continue করুন।"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (page > 0) {
                        TextButton({
                            ready = false
                            if (page > 0) page--
                        }, enabled = !saving) { Text(label("Back", "পিছনে")) }
                    }
                    Button({
                        if (!gate.take(page, SystemClock.elapsedRealtime())) return@Button
                        ready = false
                        if (page < cards.lastIndex) {
                            page++
                        } else {
                            saving = true
                            scope.launch {
                                try {
                                    finish()
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    saveError = true
                                    attempt++
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }, Modifier.weight(1f).heightIn(min = 52.dp), enabled = ready && !saving) {
                        Text(
                            if (saving) {
                                label("Saving…", "সেভ হচ্ছে…")
                            } else if (page == cards.lastIndex) {
                                label("Continue", "চালিয়ে যান")
                            } else {
                                label("Next", "পরবর্তী")
                            }
                        )
                    }
                }
                if (page == 0 && ready) {
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        AnimatedGuideHand(Modifier.size(22.dp))
                        Text(label("Tap Next to explore", "পরবর্তী ট্যাপ করে দেখুন"), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
