package app.televault.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.data.BackedDeviceCopy
import app.televault.data.DeviceTrashUris
import app.televault.data.VaultStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

internal class DeviceCopyActions(val moveBacked: (Set<String>?, String) -> Unit)
private data class DeviceTrashBatch(val copies: List<BackedDeviceCopy>, val cleanupIds: Set<String>)

/** Android consent is required; no original is permanently removed or touched before verification. */
@Composable internal fun rememberDeviceCopyActions(model: DriveViewModel): DeviceCopyActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authenticate = app.televault.security.LocalSensitiveAuthentication.current
    val state by model.state.collectAsStateWithLifecycle()
    val local by model.localMedia.changes.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<List<BackedDeviceCopy>?>(null) }
    var batches by remember { mutableStateOf(emptyList<DeviceTrashBatch>()) }
    var active by remember { mutableStateOf<DeviceTrashBatch?>(null) }
    var activeUris by remember { mutableStateOf(emptyList<android.net.Uri>()) }
    var activeLibraryUris by remember { mutableStateOf(emptyList<android.net.Uri>()) }
    var moveOrigin by remember { mutableStateOf("gallery") }
    val handled = remember { mutableSetOf<String>() }
    val latestState by rememberUpdatedState(state)
    val latestLocal by rememberUpdatedState(local)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val batch = active
        if (result.resultCode == Activity.RESULT_OK && batch != null) {
            val galleryUris = activeUris - activeLibraryUris.toSet()
            if (galleryUris.isNotEmpty()) model.deviceTrashOrigin(galleryUris, "gallery")
            if (activeLibraryUris.isNotEmpty()) model.deviceTrashOrigin(activeLibraryUris, "library")
            model.finishDeviceCleanup(batch.cleanupIds)
        } else {
            model.finishDeviceCleanup((batch?.cleanupIds ?: emptySet()) + batches.flatMap { it.cleanupIds })
            batches = emptyList()
            model.report("Device cleanup canceled. Cloud backups and unapproved originals were kept.")
        }
        active = null
        activeUris = emptyList()
        activeLibraryUris = emptyList()
        model.localMedia.refresh()
    }
    fun queue(copies: List<BackedDeviceCopy>) {
        val requests = latestState.deviceCleanupRequests
        batches = copies.distinctBy { it.local.uri }.groupBy { it.local.mime.substringBefore('/') }.values.flatMap { it.chunked(500) }.map { chunk ->
            DeviceTrashBatch(chunk, requests.filter { request -> chunk.any { it.local.uri.toString() == request.uri && it.local.fingerprint == request.fingerprint } }.map { it.id }.toSet())
        }
    }
    LaunchedEffect(batches, active) {
        if (active != null || batches.isEmpty()) return@LaunchedEffect
        val next = batches.first()
        var consumed = false
        checking = true
        try {
            val verified = model.verifiedDeviceCopies(next.copies.map { it.cloudId }.toSet(), next.copies.map { it.local.fingerprint }.toSet())
            val native = if (Build.VERSION.SDK_INT >= 30) verified.filter { it.local.mime.startsWith("image/") || it.local.mime.startsWith("video/") || it.local.mime.startsWith("audio/") }.mapNotNull { copy -> DeviceTrashUris.resolve(context, copy.local)?.let { copy to it } } else emptyList()
            val nativeSources = native.map { it.first.local.uri }.toSet()
            val recoverable = verified.filter { it.local.uri !in nativeSources }
            val recovered = mutableListOf<BackedDeviceCopy>()
            for (copy in recoverable) {
                val origin = latestState.deviceCleanupRequests.firstOrNull { it.fingerprint == copy.local.fingerprint && it.uri == copy.local.uri.toString() }?.origin ?: moveOrigin
                try {
                    model.deviceFileTrash.trash(listOf(copy.local), origin)
                    recovered += copy
                    val completed = latestState.deviceCleanupRequests.filter { it.fingerprint == copy.local.fingerprint && it.uri == copy.local.uri.toString() }.map { it.id }.toSet()
                    model.finishDeviceCleanup(completed)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    model.report("This original could not be moved safely. Its encrypted cloud backup remains; check device Trash for a recoverable copy.")
                }
            }
            if (recovered.isNotEmpty()) model.localMedia.refresh()
            val resolved = native
            val acceptedIds = latestState.deviceCleanupRequests.filter { request -> resolved.any { it.first.local.fingerprint == request.fingerprint && it.first.local.uri.toString() == request.uri } }.map { it.id }.toSet()
            val completedIds = latestState.deviceCleanupRequests.filter { request -> recovered.any { it.local.fingerprint == request.fingerprint && it.local.uri.toString() == request.uri } }.map { it.id }.toSet()
            val skipped = next.cleanupIds - acceptedIds - completedIds
            if (skipped.isNotEmpty()) model.finishDeviceCleanup(skipped)
            if (resolved.size + recovered.size != next.copies.size) model.report("Changed, missing, unverified or unsupported originals were kept. Only verified supported copies can enter Android Trash.")
            batches = batches.drop(1)
            consumed = true
            if (Build.VERSION.SDK_INT >= 30 && resolved.isNotEmpty()) {
                active = DeviceTrashBatch(resolved.map { it.first }, acceptedIds)
                activeUris = resolved.map { it.second }.distinct()
                activeLibraryUris = resolved.filter { pair -> !pair.first.local.mime.startsWith("image/") && !pair.first.local.mime.startsWith("video/") || (latestState.deviceCleanupRequests.firstOrNull { it.fingerprint == pair.first.local.fingerprint && it.uri == pair.first.local.uri.toString() }?.origin ?: moveOrigin) == "library" }.map { it.second }
                launcher.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(context.contentResolver, activeUris, true).intentSender).build())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            model.finishDeviceCleanup(next.cleanupIds)
            if (!consumed) batches = batches.drop(1)
            active = null
            activeUris = emptyList()
            activeLibraryUris = emptyList()
            model.report("Could not obtain Android Trash consent. Originals were kept.")
        } finally {
            checking = false
        }
    }
    LaunchedEffect(model) {
        snapshotFlow { listOf(latestState.deviceCleanupRequests, latestState.backedDeviceFiles, latestLocal, checking, confirmation, active, batches) }.conflate().collect {
            if (checking || confirmation != null || active != null || batches.isNotEmpty() || latestState.stage != VaultStage.READY) return@collect
            val ready = latestState.deviceCleanupRequests.filter { it.id !in handled && it.fingerprint in latestState.backedDeviceFiles && latestLocal.any { item -> !item.trashed && item.fingerprint == it.fingerprint } }
            if (ready.isEmpty()) return@collect
            checking = true
            handled.addAll(ready.map { it.id })
            try {
                val verified = model.verifiedDeviceCopies(sources = ready.map { it.fingerprint }.toSet())
                val kept = ready.filter { request -> verified.none { it.local.fingerprint == request.fingerprint } }.map { it.id }.toSet()
                if (kept.isNotEmpty()) {
                    model.finishDeviceCleanup(kept)
                    model.report("Some originals changed or could not be verified. They were kept on your device.")
                }
                queue(verified)
            } catch (e: CancellationException) {
                handled.removeAll(ready.map { it.id }.toSet())
                throw e
            } catch (_: Exception) {
                handled.removeAll(ready.map { it.id }.toSet())
                model.report("Backup cleanup could not be verified. Originals were kept.")
            } finally {
                checking = false
            }
        }
    }
    confirmation?.let { copies ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(label("Free device space?", "ডিভাইসের জায়গা খালি করবেন?")) }, text = { Text(label("${copies.size} device copies match completed encrypted backups. Move only these originals to device Trash; their cloud backups remain. System media requires Android consent; other writable files get a verified encrypted recoverable copy. Unsupported originals remain.", "${copies.size}টি ডিভাইস ফাইল সম্পূর্ণ encrypted backup-এর সঙ্গে মিলে গেছে। শুধু এই original-গুলি ডিভাইসের ট্র্যাশে যাবে; cloud backup থাকবে। সিস্টেম মিডিয়ার জন্য Android অনুমতি চাইবে; অন্য লিখনযোগ্য ফাইলের যাচাইকৃত encrypted কপি রাখা হবে। অসমর্থিত original থাকবে।")) }, confirmButton = {
            TextButton({
                authenticate {
                    if (confirmation === copies && latestState.stage == VaultStage.READY) {
                        confirmation = null
                        queue(copies)
                    }
                }
            }) { Text(label("Move to device Trash", "ডিভাইসের ট্র্যাশে রাখুন")) }
        }, dismissButton = { TextButton({ confirmation = null }) { Text(label("Cancel", "বাতিল")) } })
    }
    return DeviceCopyActions { ids, origin ->
        if (!checking && confirmation == null && active == null && batches.isEmpty()) {
            moveOrigin = origin
            checking = true
            scope.launch {
                try {
                    val copies = model.verifiedDeviceCopies(ids)
                    if (copies.isEmpty()) model.report("No verified backed-up device copies are available yet. Wait for device indexing and completed backups.") else confirmation = copies
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    model.report("Could not verify device copies. Originals were kept.")
                } finally {
                    checking = false
                }
            }
        }
    }
}
