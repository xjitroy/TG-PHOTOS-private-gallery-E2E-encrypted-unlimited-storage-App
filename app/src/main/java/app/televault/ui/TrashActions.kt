package app.televault.ui

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.televault.DriveViewModel
import app.televault.data.DeviceTrashUris
import app.televault.data.LocalMedia
import app.televault.data.VaultStage
import app.televault.domain.TrashChoice
import app.televault.domain.TrashChoice.Scope
import app.televault.security.LocalSensitiveAuthentication
import kotlinx.coroutines.*

private data class TrashPlan(val epoch: Long, val device: List<LocalMedia>, val cloud: Set<String>, val linked: Set<String>, val origin: String, val deviceIndexComplete: Boolean)
private data class TrashRun(val plan: TrashPlan, val target: Scope, val remaining: List<List<Pair<LocalMedia, Uri>>>, val completed: Int, val awaiting: List<Pair<LocalMedia, Uri>> = emptyList())
internal class TrashActions(val request: (List<MediaEntry>, String, Scope?) -> Unit)

/** A live, freshly authenticated operation; cancellation never starts a later Cloud mutation. */
@Composable
internal fun rememberTrashActions(model: DriveViewModel): TrashActions {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    val authenticate = LocalSensitiveAuthentication.current
    var plan by remember { mutableStateOf<TrashPlan?>(null) }
    var target by remember { mutableStateOf(Scope.EVERYWHERE) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var run by remember { mutableStateOf<TrashRun?>(null) }
    var nonce by remember { mutableLongStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(model) {
        onDispose {
            alive = false
            nonce++
        }
    }
    fun valid(value: TrashPlan) = alive && model.playbackEpoch == value.epoch && model.state.value.stage == VaultStage.READY && model.state.value.indexLoaded && !model.state.value.indexNeedsRepair
    fun available(value: TrashPlan, choice: Scope) = TrashChoice.enabled(choice, value.device.size, value.cloud.size) && (choice != Scope.EVERYWHERE || value.deviceIndexComplete)
    fun stop(message: String) {
        run = null
        busy = false
        model.localMedia.refresh()
        model.report(message)
    }
    suspend fun finish(value: TrashRun) {
        check(valid(value.plan))
        check(TrashChoice.cloudAfterDevice(value.target, if (value.target == Scope.CLOUD) 0 else value.plan.device.size, value.completed) || value.target == Scope.DEVICE)
        if (value.target != Scope.DEVICE && value.plan.cloud.isNotEmpty()) model.trashConfirmed(value.plan.cloud, value.plan.origin, value.plan.epoch)
        stop("Selected copies moved to the chosen Trash. Restore them from Device or Cloud Trash.")
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val current = run
        if (current != null && current.awaiting.isNotEmpty()) {
            coroutine.launch {
                try {
                    check(valid(current.plan))
                    if (result.resultCode != Activity.RESULT_OK) {
                        stop("Device Trash was canceled. Cloud copies were kept; any earlier device copies remain recoverable in Trash.")
                        return@launch
                    }
                    val completed = withContext(Dispatchers.IO) { current.awaiting.filter { DeviceTrashUris.isTrashed(context, it.second) } }
                    check(valid(current.plan))
                    model.localMedia.confirmedTrash(completed.flatMap { listOf(it.first.uri, it.second) }.toSet())
                    if (completed.isNotEmpty()) model.deviceTrashOriginConfirmed(completed.map { it.second }, current.plan.origin, current.plan.epoch)
                    check(valid(current.plan))
                    if (completed.size != current.awaiting.size) {
                        stop("Android did not confirm every device copy in Trash. Cloud copies were kept. Check Device Trash before retrying.")
                    } else {
                        run = current.copy(completed = current.completed + completed.size, awaiting = emptyList())
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    stop("Trash could not finish safely. Cloud copies not already moved were kept; check both Trash views.")
                }
            }
        }
    }
    LaunchedEffect(run) {
        val current = run ?: return@LaunchedEffect
        if (current.awaiting.isNotEmpty()) return@LaunchedEffect
        try {
            check(valid(current.plan))
            if (current.remaining.isEmpty()) {
                finish(current)
            } else {
                if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("Native Trash requires Android 11")
                val batch = current.remaining.first()
                check(batch.all { DeviceTrashUris.unchanged(context, it.first) })
                val next = current.copy(remaining = current.remaining.drop(1), awaiting = batch)
                run = next
                launcher.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(context.contentResolver, batch.map { it.second }, true).intentSender).build())
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            stop("Trash could not finish safely. Unapproved device copies and remaining Cloud copies were kept.")
        }
    }
    suspend fun execute(value: TrashPlan, choice: Scope) {
        check(valid(value))
        val activeCloud = withContext(Dispatchers.Default) { model.state.value.files.filter { it.trashedAt == 0L }.map { it.id }.toSet() }
        check(activeCloud.containsAll(value.cloud))
        if (choice == Scope.CLOUD) {
            finish(TrashRun(value, choice, emptyList(), 0))
            return
        }
        // Re-read fingerprints and full hashes after authentication, before changing any store.
        val fingerprints = withContext(Dispatchers.Default) { model.localMedia.changes.value.filter { !it.trashed }.map { it.fingerprint }.toSet() }
        check(withContext(Dispatchers.IO) { value.device.all { it.fingerprint in fingerprints && DeviceTrashUris.unchanged(context, it) } })
        if (value.linked.isNotEmpty()) {
            val verified = model.verifiedDeviceCopies(value.cloud, value.linked).map { it.local.fingerprint }.toSet()
            check(verified.containsAll(value.linked))
        }
        val native = mutableListOf<Pair<LocalMedia, Uri>>()
        val private = mutableListOf<LocalMedia>()
        for (item in value.device) {
            val uri = if (Build.VERSION.SDK_INT >= 30 && item.mime.substringBefore('/') in setOf("image", "video", "audio")) DeviceTrashUris.resolve(context, item) else null
            if (uri != null) native += item to uri else private += item
        }
        var completed = 0
        for (item in private) {
            check(valid(value))
            check(DeviceTrashUris.unchanged(context, item))
            model.trashDeviceConfirmed(item, value.origin, value.epoch)
            completed++
        }
        check(valid(value))
        run = TrashRun(value, choice, native.groupBy { it.first.mime.substringBefore('/') }.values.flatMap { it.chunked(500) }, completed)
    }
    plan?.let { value ->
        val canSelect = available(value, target)
        AlertDialog(onDismissRequest = {
            if (!busy) {
                plan = null
                nonce++
            }
        }, title = { Text(if (confirm) label("Move to Trash?", "ট্র্যাশে সরাবেন?") else label("Delete selected copies", "নির্বাচিত কপি সরান")) }, text = {
            Column {
                if (!confirm) {
                    TrashChoice.options(value.cloud.size).forEach { choice ->
                        val enabled = available(value, choice)
                        Row(Modifier.fillMaxWidth().selectable(target == choice, enabled = enabled, role = Role.RadioButton) { target = choice }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(target == choice, null, enabled = enabled)
                            Text(
                                when (choice) {
                                    Scope.DEVICE -> label("Delete from device", "ডিভাইস থেকে সরান")
                                    Scope.CLOUD -> label("Delete from cloud", "ক্লাউড থেকে সরান")
                                    Scope.EVERYWHERE -> label("Delete from everywhere", "সব জায়গা থেকে সরান")
                                },
                                Modifier.padding(start = 8.dp),
                                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!value.deviceIndexComplete) Text(label("Device indexing is still running. For Everywhere, cancel and retry after it finishes.", "ডিভাইসের তালিকা এখনও তৈরি হচ্ছে। সব জায়গা থেকে সরাতে তালিকা সম্পূর্ণ হলে আবার চেষ্টা করুন।"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(label("Device: ${value.device.size} · Cloud: ${value.cloud.size}. Only verified corresponding copies are included.", "ডিভাইস: ${value.device.size} · ক্লাউড: ${value.cloud.size}। শুধু যাচাইকৃত সংশ্লিষ্ট কপি অন্তর্ভুক্ত।"), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        when (target) {
                            Scope.DEVICE -> label("Move ${value.device.size} device copies to Device Trash? Cloud backups remain.", "${value.device.size}টি ডিভাইস কপি Device Trash-এ যাবে। ক্লাউড ব্যাকআপ থাকবে।")
                            Scope.CLOUD -> label("Move ${value.cloud.size} cloud copies to Cloud Trash? Device originals remain.", "${value.cloud.size}টি ক্লাউড কপি Cloud Trash-এ যাবে। ডিভাইসের original থাকবে।")
                            Scope.EVERYWHERE -> label("Move ${value.device.size} device and ${value.cloud.size} cloud copies to their respective Trash? Cloud changes start only after every planned device copy reaches Trash.", "${value.device.size}টি ডিভাইস ও ${value.cloud.size}টি ক্লাউড কপি আলাদা ট্র্যাশে যাবে। পরিকল্পিত সব ডিভাইস কপি ট্র্যাশে যাওয়ার পর ক্লাউডের কাজ শুরু হবে।")
                        }
                    )
                    Text(label("Nothing is permanently deleted. Continue with fingerprint or your phone's screen lock; Android may also ask for file consent.", "কিছুই স্থায়ীভাবে মুছবে না। ফিঙ্গারপ্রিন্ট বা ফোনের স্ক্রিন লক দিয়ে এগোন; Android ফাইল সরানোর অনুমতিও চাইতে পারে।"), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = {
            TextButton(enabled = !busy && canSelect, onClick = {
                if (!confirm) {
                    confirm = true
                } else {
                    val request = ++nonce
                    val choice = target
                    authenticate {
                        if (alive && request == nonce && plan === value && valid(value)) {
                            plan = null
                            busy = true
                            coroutine.launch {
                                try {
                                    execute(value, choice)
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    stop("Trash could not finish safely. Changed or unapproved originals and remaining Cloud copies were kept.")
                                }
                            }
                        }
                    }
                }
            }) { Text(if (confirm) label("Confirm and verify", "নিশ্চিত করে যাচাই করুন") else label("Continue", "চালিয়ে যান")) }
        }, dismissButton = {
            TextButton({
                plan = null
                nonce++
            }, enabled = !busy) { Text(label("Cancel", "বাতিল")) }
        })
    }
    return TrashActions { entries, origin, initial ->
        if (!busy && plan == null && run == null && entries.isNotEmpty()) {
            busy = true
            val epoch = model.playbackEpoch
            coroutine.launch {
                try {
                    val selectedLocal = entries.mapNotNull { it.local }.filter { !it.trashed && it.uri.scheme != "tgphotos-device-trash" }.distinctBy { it.uri }
                    val snapshot = model.state.value
                    check(snapshot.stage == VaultStage.READY && snapshot.indexLoaded && !snapshot.indexNeedsRepair)
                    val explicitCloud = entries.mapNotNull { it.cloud?.takeIf { file -> file.trashedAt == 0L }?.id }.toSet()
                    val verifiedSelected = if (selectedLocal.isEmpty()) emptyList() else model.verifiedDeviceCopies(sources = selectedLocal.map { it.fingerprint }.toSet())
                    val cloud = explicitCloud + verifiedSelected.map { it.cloudId }
                    val candidates = withContext(Dispatchers.Default) {
                        val files = snapshot.files.filter { it.id in cloud }
                        val uris = files.map { it.sourceUri }.filter { it.isNotBlank() }.toSet()
                        val tokens = files.map { it.sourceToken }.filter { it.isNotBlank() }.toSet() + snapshot.backedDeviceFiles.filterValues { it in cloud }.keys
                        val alreadyVerified = verifiedSelected.map { it.local.fingerprint }.toSet()
                        model.localMedia.changes.value.filter { item -> !item.trashed && item.fingerprint !in alreadyVerified && (item.uri.toString() in uris || item.fingerprint in tokens) }
                    }
                    val verified = verifiedSelected + if (candidates.isEmpty()) emptyList() else model.verifiedDeviceCopies(cloud, candidates.map { it.fingerprint }.toSet())
                    val device = withContext(Dispatchers.Default) { (selectedLocal + verified.map { it.local }).distinctBy { it.uri } }
                    val value = TrashPlan(epoch, device, cloud, verified.map { it.local.fingerprint }.toSet(), origin, model.localMedia.indexReady.value && !model.localMedia.scanningDocuments.value)
                    check(valid(value))
                    plan = value
                    target = initial ?: if (value.deviceIndexComplete) {
                        Scope.EVERYWHERE
                    } else if (device.isNotEmpty()) {
                        Scope.DEVICE
                    } else {
                        Scope.CLOUD
                    }
                    confirm = false
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    model.report("Could not prepare Trash safely. Files were kept; wait for the index or retry after unlocking.")
                } finally {
                    busy = false
                }
            }
        }
    }
}
