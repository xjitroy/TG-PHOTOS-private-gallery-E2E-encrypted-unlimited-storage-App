package app.televault.ui

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.televault.DriveViewModel
import app.televault.TeleVaultApplication
import app.televault.crypto.NewPasswordPolicy
import app.televault.data.PrivateVaultItem
import app.televault.data.VaultOriginalRemoval
import kotlinx.coroutines.*

private enum class VaultAction { DOWNLOAD, REMOVE, DELETE, KEY, DELETE_ALL, MIGRATE, BIOMETRIC_ENABLE, BIOMETRIC_DISABLE }

/** Vault secrets stay in foreground memory and are excluded from saved navigation state. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PrivateVaultScreen(model: DriveViewModel, modifier: Modifier = Modifier, pendingCopies: List<Uri> = emptyList(), copiesImported: () -> Unit = {}) {
    val context = LocalContext.current
    val vault = remember { (context.applicationContext as TeleVaultApplication).privateVault }
    val unlocked by vault.unlocked.collectAsStateWithLifecycle()
    val entries by vault.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var exists by remember { mutableStateOf(vault.exists) }
    var createNew by remember { mutableStateOf(false) }
    val creating = !exists || createNew
    var replacementAcknowledged by remember(createNew) { mutableStateOf(false) }
    val vaultChoices by vault.choices.collectAsStateWithLifecycle()
    val vaultName by vault.selectedName.collectAsStateWithLifecycle()
    LaunchedEffect(vaultChoices) { exists = vault.exists }
    var creationName by remember(createNew, exists) { mutableStateOf(app.televault.domain.VaultNames.generated(System.currentTimeMillis(), java.time.ZoneId.systemDefault(), java.util.UUID.randomUUID().toString())) }
    var biometricEnabled by remember { mutableStateOf(false) }
    var biometricRequest by remember { mutableStateOf<app.televault.data.VaultBiometricKey.Request?>(null) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var recoveryMode by remember { mutableStateOf(false) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var recoverySaved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var discoveryComplete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf<PrivateVaultItem?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var category by remember { mutableStateOf("All") }
    var action by remember { mutableStateOf<VaultAction?>(null) }
    var actionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var actionPassword by remember { mutableStateOf("") }
    var zipPassword by remember { mutableStateOf("") }
    var zipConfirm by remember { mutableStateOf("") }
    var zipSaved by remember { mutableStateOf(false) }
    var refreshed by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<Uri?>(null) }
    var shownSecret by remember { mutableStateOf<String?>(null) }
    var shownSecretTitle by remember { mutableStateOf("") }
    var pendingImports by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingNames by remember { mutableStateOf<Map<Uri, String>>(emptyMap()) }
    var pendingFolder by remember { mutableStateOf<Uri?>(null) }
    var importConfirmed by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<VaultOriginalRemoval.Result?>(null) }
    var menu by remember { mutableStateOf(false) }
    var storageWarning by remember { mutableStateOf(false) }
    var legacyRestore by remember { mutableStateOf<Uri?>(null) }
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = when {
                    failure is app.televault.data.PrivateVaultImportFailure -> "Vault upload interrupted at ${failure.stage.name.lowercase().replace('_', ' ')}. Check the connection and storage, unlock if needed, then select the file again. The failed file's original is retained; already confirmed actions are kept."
                    failure.message?.contains("older Vault") == true -> "This older Vault did not retain its recovery key. Use your original offline recovery copy."
                    else -> "Action incomplete. Check the Vault password, Telegram connection, permissions and available storage. Originals are kept unless deletion was already confirmed."
                }
            } finally {
                busy = false
                exists = vault.exists
            }
        }
    }
    biometricRequest?.let { request ->
        VaultBiometricPrompt(request, verified = { cipher ->
            work {
                try {
                    vault.completeBiometric(request, cipher)
                    biometricEnabled = vault.biometricAvailable()
                    message = if (request.enrolling) "Biometric access enabled on this phone. Keep your Vault password and recovery key safe." else ""
                } finally {
                    biometricRequest = null
                }
            }
        }, cancelled = {
            biometricRequest = null
            message = "Use your Vault password. Biometric access needs an enrolled, supported biometric on this phone."
        })
        if (android.os.Build.VERSION.SDK_INT < 28) AlertDialog(onDismissRequest = { biometricRequest = null }, title = { Text(label("Touch fingerprint sensor", "ফিঙ্গারপ্রিন্ট সেন্সরে আঙুল দিন")) }, text = { Text(label("Verify with an enrolled fingerprint. Your Vault password remains required for recovery and protected file actions.", "ফোনে সেভ করা আঙুল দিয়ে যাচাই করুন। রিকভারি ও সুরক্ষিত ফাইলের কাজে Vault password লাগবে।")) }, confirmButton = { TextButton({ biometricRequest = null }) { Text(label("Use password", "পাসওয়ার্ড দিন")) } })
    }
    fun chooseAction(next: VaultAction, ids: Set<String>) {
        action = next
        actionIds = ids
        actionPassword = ""
        zipPassword = ""
        zipConfirm = ""
        destination = null
    }
    fun copySecret(secret: String) {
        app.televault.security.SensitiveClipboard.copy(context, secret)
    }
    val deleteOriginals = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingRemoval = null
        message = if (result.resultCode == android.app.Activity.RESULT_OK) "Verified originals deleted from the device. Cloud Vault copies remain protected." else "Device deletion canceled. Verified Vault copies and originals are retained."
    }
    val importMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        pendingImports = uris.distinct().take(100)
        pendingNames = emptyMap()
        importConfirmed = false
    }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingImports = uris.distinct().take(100)
        pendingNames = emptyMap()
        importConfirmed = false
    }
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> pendingFolder = uri }
    LaunchedEffect(pendingFolder, busy) {
        val uri = pendingFolder
        if (uri != null && !busy) {
            pendingFolder = null
            work {
                val files = app.televault.data.VaultFolderSelection.files(context, uri)
                pendingImports = files.map { it.first }
                pendingNames = files.toMap()
                importConfirmed = false
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) action = null else destination = uri
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> legacyRestore = uri }
    LaunchedEffect(Unit) {
        busy = true
        try {
            vault.selectAccount()
            exists = vault.exists
            if (!exists) vault.discover()
            exists = vault.exists
            biometricEnabled = if (exists) vault.biometricAvailable() else false
            discoveryComplete = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            message = "Could not check Telegram for an existing Vault. Retry before creating a new Vault."
        } finally {
            busy = false
        }
    }

    LaunchedEffect(unlocked, busy, refreshed) {
        if (!unlocked) {
            opening = null
            selected = emptySet()
            refreshed = false
        } else if (!busy && !refreshed) {
            refreshed = true
            work { vault.refreshCloud() }
        }
    }
    LaunchedEffect(action, destination, unlocked) {
        if (action == VaultAction.DOWNLOAD && destination != null && unlocked && zipPassword.isBlank()) {
            zipPassword = app.televault.data.VaultZipSupport.password()
            zipSaved = false
        }
    }
    LaunchedEffect(pendingCopies) {
        if (pendingCopies.isNotEmpty()) {
            pendingImports = pendingCopies.take(100)
            pendingNames = emptyMap()
            importConfirmed = false
            copiesImported()
        }
    }
    LaunchedEffect(unlocked, pendingImports, importConfirmed, busy) {
        if (unlocked && !busy && importConfirmed && pendingImports.isNotEmpty()) {
            val uris = pendingImports
            val names = pendingNames
            pendingNames = emptyMap()
            pendingImports = emptyList()
            importConfirmed = false
            work {
                val verified = mutableListOf<Pair<Uri, String>>()
                try {
                    for (uri in uris) verified.add(uri to vault.import(uri, names[uri]))
                } finally {
                    // Only fully committed, read-back-verified originals become eligible for removal.
                    if (vault.unlocked.value && verified.isNotEmpty()) {
                        val deviceOriginals = app.televault.data.VaultSourceCleanup.deviceOriginals(context, vault, verified)
                        pendingRemoval = VaultOriginalRemoval.prepare(context, vault, deviceOriginals)
                        message = "${verified.size} files protected in the cloud Vault. " + if (pendingRemoval?.retained != 0) {
                            "Some providers do not permit deletion; those originals were kept."
                        } else if (deviceOriginals.isEmpty()) {
                            "Cloud sources moved; no device-original deletion is needed."
                        } else {
                            "Original deletion is ready."
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(pendingRemoval, busy, unlocked) {
        val consent = pendingRemoval?.consent
        if (consent != null && !busy && unlocked) deleteOriginals.launch(IntentSenderRequest.Builder(consent.intentSender).build())
    }
    LaunchedEffect(legacyRestore, busy) {
        val uri = legacyRestore
        if (uri != null && !busy) {
            legacyRestore = null
            work {
                vault.restoreBackup(uri)
                message = "Legacy encrypted Vault restored. Use its original password or recovery key."
            }
        }
    }
    DisposableEffect(owner) {
        val window = (context as? android.app.Activity)?.window
        val previouslySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                biometricRequest = null
                vault.lock()
                opening = null
                password = ""
                confirm = ""
                actionPassword = ""
                zipPassword = ""
                zipConfirm = ""
                shownSecret = null
                recoveryKey = null
                recoverySaved = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            vault.lock()
            if (!previouslySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (message.isNotBlank()) {
            Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
            }
        }
        if (!unlocked) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ForgottenVaultManager()
                Box(Modifier.size(112.dp).background(Brush.radialGradient(listOf(colors.tertiaryContainer, colors.background)), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Lock, null, Modifier.size(52.dp), tint = colors.tertiary) }
                Text(label("Your private space", "আপনার ব্যক্তিগত জায়গা"), style = MaterialTheme.typography.headlineSmall)
                Text(label("Telegram first. A separate Vault password or private key protects your files.", "প্রথমে Telegram। আলাদা Vault password অথবা private key দিয়ে ফাইল সুরক্ষিত।"), style = MaterialTheme.typography.bodyMedium)
                if (pendingImports.isNotEmpty() || pendingFolder != null) {
                    Text(label("Your selection is kept. Unlock the Vault to continue adding it securely.", "আপনার নির্বাচন রাখা আছে। নিরাপদে যোগ করতে Vault আবার আনলক করুন।"), color = colors.primary, style = MaterialTheme.typography.bodyMedium)
                    TextButton({
                        pendingImports = emptyList()
                        pendingNames = emptyMap()
                        pendingFolder = null
                    }) { Text(label("Cancel adding files", "ফাইল যোগ করা বাতিল")) }
                }
                if (recoveryKey != null) {
                    Text(label("Save your private recovery key", "প্রাইভেট রিকভারি কী রাখুন"), style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(checkNotNull(recoveryKey), style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton({ copySecret(checkNotNull(recoveryKey)) }, enabled = !busy) { Text(label("Copy private key", "প্রাইভেট কী কপি")) }
                    Text(label("Keep it offline and private. Anyone with this key and access to your Telegram/Photos vault can unlock this Vault. Do not take screenshots.", "কীটি অফলাইনে ব্যক্তিগতভাবে রাখুন। এই কী ও আপনার Telegram/Photos vault-এর access থাকলে Vault খোলা যায়। Screenshot নেবেন না।"), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(recoverySaved, { recoverySaved = it })
                        Text(label("I saved it safely", "নিরাপদে রেখেছি"))
                    }
                    Button({
                        recoveryKey = null
                        exists = vault.exists
                        createNew = false
                        password = ""
                        confirm = ""
                    }, enabled = recoverySaved && !busy) { Text(label("Continue to unlock", "আনলক করতে যান")) }
                } else {
                    if (vaultChoices.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            vaultChoices.forEach { choice ->
                                FilterChip(selected = !creating && vault.selectedId == choice.id, onClick = {
                                    work {
                                        vault.selectVault(choice.id)
                                        exists = vault.exists
                                        createNew = false
                                        password = ""
                                        confirm = ""
                                        recoveryMode = false
                                        biometricEnabled = vault.biometricAvailable()
                                    }
                                }, enabled = !busy, label = { Text(choice.name, maxLines = 1) })
                            }
                        }
                    }
                    if (creating) OutlinedTextField(creationName, { creationName = app.televault.domain.VaultNames.clean(it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label("Vault name (optional)", "ভল্টের নাম (ঐচ্ছিক)")) })
                    if (createNew) {
                        Text(label("Creating this replacement schedules the old Vault and its app-owned Telegram files for permanent deletion after a 24-hour waiting period. You can cancel before deletion starts. Your new Vault uses independent keys.", "এই নতুন ভল্ট তৈরি করলে আগের ভল্ট ও অ্যাপের রাখা তার Telegram ফাইলগুলি ২৪ ঘণ্টার অপেক্ষার পরে স্থায়ীভাবে মুছে যাবে। শুরু হওয়ার আগে বাতিল করতে পারবেন। নতুন ভল্টের key আলাদা হবে।"), color = colors.error, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(replacementAcknowledged, { replacementAcknowledged = it })
                            Text(label("I understand the old Vault will be deleted", "বুঝেছি, পুরোনো ভল্ট মুছে যাবে"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (exists && !createNew && biometricEnabled) {
                        OutlinedButton({ work { biometricRequest = vault.prepareBiometricUnlock() } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy && discoveryComplete) {
                            Icon(Icons.Outlined.Fingerprint, null)
                            Spacer(Modifier.width(8.dp))
                            Text(label("Unlock with fingerprint", "ফিঙ্গারপ্রিন্ট দিয়ে খুলুন"))
                        }
                    }
                    VaultSecretField(password, { password = it }, if (recoveryMode) label("Private recovery key", "প্রাইভেট রিকভারি কী") else label("Vault password", "Vault password"), enabled = !busy && discoveryComplete, newPassword = creating, fixedPattern = false)
                    if (creating) {
                        VaultSecretField(confirm, { confirm = it }, label("Confirm password", "পাসওয়ার্ড নিশ্চিত করুন"), enabled = !busy, newPassword = true, fixedPattern = false)
                        Text(label("Remember this Vault password and save your recovery key. Fingerprint access is optional and cannot recover a Vault after reinstall or phone changes.", "Vault password মনে রাখুন ও recovery key নিরাপদে রাখুন। ফিঙ্গারপ্রিন্ট ঐচ্ছিক; reinstall বা ফোন বদলালে এটি দিয়ে Vault উদ্ধার হবে না।"), style = MaterialTheme.typography.bodySmall)
                        PasswordCreationGuidance(password) {
                            password = it
                            confirm = ""
                        }
                    }
                    Button({
                        val value = password.toCharArray()
                        work {
                            if (!creating) {
                                val verified = if (!recoveryMode) password else ""
                                vault.unlock(value, recoveryMode)
                                if (action != null && destination != null && verified.isNotBlank()) actionPassword = verified
                                password = ""
                            } else {
                                recoveryKey = vault.create(value, creationName, createNew)
                                if (vault.replacementDeletionPending) message = "New Vault created. The old Vault deletion could not be registered and its data is retained. Select the old Vault and schedule deletion again when storage/connection is available."
                                biometricEnabled = false
                                password = ""
                                confirm = ""
                            }
                        }
                    }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy && discoveryComplete && password.isNotBlank() && (!createNew || replacementAcknowledged) && (!creating || NewPasswordPolicy.accepts(password) && confirm == password)) {
                        Icon(Icons.Outlined.LockOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (!creating) label("Unlock Vault", "Vault আনলক") else label("Create Private Vault", "Private Vault তৈরি"))
                    }
                    if (!discoveryComplete) {
                        TextButton({
                            work {
                                vault.discover()
                                exists = vault.exists
                                discoveryComplete = true
                            }
                        }, enabled = !busy) { Text(label("Retry Vault discovery", "Vault আবার খুঁজুন")) }
                    }
                    if (exists && !createNew) {
                        TextButton({
                            recoveryMode = !recoveryMode
                            password = ""
                        }, enabled = !busy) { Text(if (recoveryMode) label("Use Vault password", "Vault password দিন") else label("Use private key", "প্রাইভেট কী দিন")) }
                    }
                    if (exists && !createNew) {
                        TextButton({
                            createNew = true
                            recoveryMode = false
                            password = ""
                            confirm = ""
                        }, enabled = !busy) { Text(label("Forgot password? Create a new Vault", "পাসওয়ার্ড ভুলে গেছেন? নতুন ভল্ট তৈরি করুন")) }
                        TextButton({ work { vault.discover() } }, enabled = !busy) { Text(label("Find other Vaults", "অন্য ভল্টগুলি খুঁজুন")) }
                    }
                    if (createNew) {
                        TextButton({
                            createNew = false
                            password = ""
                            confirm = ""
                        }, enabled = !busy) { Text(label("Back to existing Vault", "আগের ভল্টে ফিরুন")) }
                    }
                    if (!exists) TextButton({ restore.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy) { Text(label("Restore an older device Vault", "পুরোনো device Vault রিস্টোর")) }
                    if (exists && !creating) ForgottenVaultStatus(model.selectedVaultId, vault.selectedId, vaultChoices.firstOrNull { it.id == vault.selectedId }?.name.orEmpty(), showPending = false)
                }
                Spacer(Modifier.height(16.dp))
            }
        } else {
            Surface(color = colors.tertiaryContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.EnhancedEncryption, null, tint = colors.onTertiaryContainer)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(vaultName.ifBlank { label("Private Vault", "প্রাইভেট ভল্ট") }, style = MaterialTheme.typography.titleMedium, color = colors.onTertiaryContainer, maxLines = 2)
                        Text("${entries.size} " + label("protected files", "সুরক্ষিত ফাইল"), style = MaterialTheme.typography.bodySmall, color = colors.onTertiaryContainer)
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, label("Vault settings", "Vault সেটিংস"), tint = colors.onTertiaryContainer) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text(label("Keep Telegram backup files", "Telegram-এর ব্যাকআপ ফাইল রাখুন")) }, {
                                menu = false
                                storageWarning = true
                            }, leadingIcon = { Icon(Icons.Outlined.WarningAmber, null) })
                            DropdownMenuItem({ Text(label("Add folder", "ফোল্ডার যোগ করুন")) }, {
                                menu = false
                                importFolder.launch(null)
                            }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) })
                            if (entries.any { it.cloud == null }) {
                                DropdownMenuItem({ Text(label("Protect legacy files in cloud", "পুরোনো Vault ফাইল cloud-এ সুরক্ষিত করুন")) }, {
                                    menu = false
                                    chooseAction(VaultAction.MIGRATE, emptySet())
                                }, leadingIcon = { Icon(Icons.Outlined.CloudUpload, null) })
                            }
                            DropdownMenuItem({ Text(label("Refresh cloud Vault", "Cloud Vault রিফ্রেশ")) }, {
                                menu = false
                                work { vault.refreshCloud() }
                            }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) })
                            DropdownMenuItem({ Text(if (biometricEnabled) label("Disable fingerprint access", "ফিঙ্গারপ্রিন্ট বন্ধ করুন") else label("Enable fingerprint access", "ফিঙ্গারপ্রিন্ট চালু করুন")) }, {
                                menu = false
                                chooseAction(if (biometricEnabled) VaultAction.BIOMETRIC_DISABLE else VaultAction.BIOMETRIC_ENABLE, emptySet())
                            }, leadingIcon = { Icon(Icons.Outlined.Fingerprint, null) })
                            DropdownMenuItem({ Text(label("View private key", "প্রাইভেট কী দেখুন")) }, {
                                menu = false
                                chooseAction(VaultAction.KEY, emptySet())
                            }, leadingIcon = { Icon(Icons.Outlined.Key, null) })
                            DropdownMenuItem({ Text(label("Delete entire Vault", "সম্পূর্ণ Vault মুছুন")) }, {
                                menu = false
                                chooseAction(VaultAction.DELETE_ALL, emptySet())
                            }, leadingIcon = { Icon(Icons.Outlined.DeleteForever, null, tint = colors.error) })
                            DropdownMenuItem({ Text(label("Lock Vault", "Vault লক")) }, {
                                menu = false
                                vault.lock()
                            }, leadingIcon = { Icon(Icons.Outlined.Lock, null) })
                        }
                    }
                }
            }
            VaultTransferStatus()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton({ importMedia.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, Modifier.weight(1f), enabled = !busy) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null)
                    Spacer(Modifier.width(6.dp))
                    Text(label("Photos & videos", "ছবি ও ভিডিও"))
                }
                OutlinedButton({ importFiles.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Spacer(Modifier.width(6.dp))
                    Text(label("Files", "ফাইল"))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Photos", "Videos", "Audio", "Files").forEach { name -> FilterChip(category == name, { category = name }, label = { Text(name) }) }
            }
            if (selected.isNotEmpty()) {
                Surface(color = colors.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ selected = emptySet() }) { Icon(Icons.Outlined.Close, label("Clear selection", "নির্বাচন বাতিল")) }
                        Text("${selected.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton({ chooseAction(VaultAction.REMOVE, selected) }) { Icon(Icons.Outlined.DriveFileMove, label("Export & remove", "ZIP নিয়ে Vault থেকে সরান")) }
                        IconButton({ chooseAction(VaultAction.DOWNLOAD, selected) }) { Icon(Icons.Outlined.Download, label("Download encrypted ZIP", "এনক্রিপ্টেড ZIP ডাউনলোড")) }
                        IconButton({ chooseAction(VaultAction.DELETE, selected) }) { Icon(Icons.Outlined.DeleteForever, label("Permanently delete", "স্থায়ীভাবে মুছুন"), tint = colors.error) }
                    }
                }
            }
            val filtered = entries.filter {
                when (category) {
                    "Photos" -> it.mime.startsWith("image/")
                    "Videos" -> it.mime.startsWith("video/")
                    "Audio" -> it.mime.startsWith("audio/")
                    "Files" -> !it.mime.startsWith("image/") && !it.mime.startsWith("video/") && !it.mime.startsWith("audio/")
                    else -> true
                }
            }
            LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), modifier = Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(label("Add photos, videos or files to your protected space. Originals are removed only after the encrypted cloud copy is verified.", "সুরক্ষিত জায়গায় ছবি, ভিডিও বা ফাইল যোগ করুন। Encrypted cloud copy যাচাই হওয়ার পরেই মূল ফাইল মুছবে।"), Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodyMedium) }
                items(filtered, key = { it.id }) { item ->
                    var itemMenu by remember { mutableStateOf(false) }
                    Surface(
                        Modifier.fillMaxWidth().combinedClickable(onClick = {
                            if (selected.isNotEmpty()) {
                                selected = if (item.id in selected) selected - item.id else selected + item.id
                            } else if (item.mime.startsWith("image/") || item.mime.startsWith("video/") || item.mime.startsWith("audio/")) {
                                opening = item
                            } else {
                                chooseAction(VaultAction.DOWNLOAD, setOf(item.id))
                            }
                        }, onLongClick = { selected = selected + item.id }),
                        shape = RoundedCornerShape(20.dp),
                        color = if (item.id in selected) colors.primaryContainer else colors.surfaceContainerLow
                    ) {
                        Column {
                            Box(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
                                VaultGalleryArtwork(item, remember(item.id) { vault.uri(item.id) })
                                if (item.id in selected) Icon(Icons.Outlined.CheckCircle, label("Selected", "নির্বাচিত"), Modifier.align(Alignment.TopStart).padding(10.dp), tint = colors.primary)
                                Box(Modifier.align(Alignment.TopEnd)) {
                                    IconButton({ itemMenu = true }) { Icon(Icons.Outlined.MoreVert, label("File actions", "ফাইলের অপশন")) }
                                    DropdownMenu(itemMenu, { itemMenu = false }) {
                                        DropdownMenuItem({ Text(label("Download encrypted ZIP", "এনক্রিপ্টেড ZIP ডাউনলোড")) }, {
                                            itemMenu = false
                                            chooseAction(VaultAction.DOWNLOAD, setOf(item.id))
                                        })
                                        DropdownMenuItem({ Text(label("Export & remove", "ZIP নিয়ে Vault থেকে সরান")) }, {
                                            itemMenu = false
                                            chooseAction(VaultAction.REMOVE, setOf(item.id))
                                        })
                                        DropdownMenuItem({ Text(label("Permanently delete", "স্থায়ীভাবে মুছুন")) }, {
                                            itemMenu = false
                                            chooseAction(VaultAction.DELETE, setOf(item.id))
                                        })
                                    }
                                }
                            }
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(sizeLabel(item.size), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = colors.primary)
                                    Text(if (item.cloud == null) label("Encrypted", "এনক্রিপ্টেড") else label("Double encrypted", "ডবল এনক্রিপ্টেড"), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (pendingImports.isNotEmpty() && !importConfirmed && unlocked) AlertDialog(onDismissRequest = { pendingImports = emptyList() }, title = { Text(label("Move into Private Vault?", "Private Vault-এ সরাবেন?")) }, text = { Text(label("These files will be encrypted twice and uploaded to your Telegram Vault. After complete server read-back verification, app-owned cloud sources are permanently removed from Photos and Telegram. Device originals are permanently deleted only where Android permits. A canceled or unsupported device deletion keeps that original.", "ফাইল দুইবার encrypt হয়ে আপনার Telegram Vault-এ যাবে। Server copy সম্পূর্ণ যাচাই হওয়ার পরে অ্যাপের ক্লাউড source Photos ও Telegram থেকে স্থায়ীভাবে সরবে। Android অনুমতি দিলেই ডিভাইসের মূল ফাইল মুছবে। ডিভাইসের delete বাতিল করলে বা permission না থাকলে সেই মূল ফাইল থাকবে।")) }, confirmButton = { TextButton({ importConfirmed = true }) { Text(label("Protect & remove originals", "সুরক্ষিত করে মূল ফাইল সরান")) } }, dismissButton = { TextButton({ pendingImports = emptyList() }) { Text(label("Cancel", "বাতিল")) } })
    val currentAction = action
    if (currentAction != null && unlocked && !busy) {
        AlertDialog(onDismissRequest = {
            action = null
            actionPassword = ""
            zipPassword = ""
        }, title = {
            Text(
                when (currentAction) {
                    VaultAction.DOWNLOAD -> label("Download encrypted ZIP", "এনক্রিপ্টেড ZIP ডাউনলোড")
                    VaultAction.REMOVE -> label("Export & remove", "ZIP নিয়ে Vault থেকে সরান")
                    VaultAction.DELETE -> label("Permanently delete", "স্থায়ীভাবে মুছুন")
                    VaultAction.KEY -> label("View private key", "প্রাইভেট কী দেখুন")
                    VaultAction.MIGRATE -> label("Protect older Vault files", "পুরোনো Vault ফাইল সুরক্ষিত করুন")
                    VaultAction.DELETE_ALL -> label("Delete entire Vault", "সম্পূর্ণ Vault মুছুন")
                    VaultAction.BIOMETRIC_ENABLE -> label("Enable fingerprint access", "ফিঙ্গারপ্রিন্ট চালু করুন")
                    VaultAction.BIOMETRIC_DISABLE -> label("Disable fingerprint access", "ফিঙ্গারপ্রিন্ট বন্ধ করুন")
                }
            )
        }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (currentAction in setOf(VaultAction.DOWNLOAD, VaultAction.REMOVE) && destination == null) {
                    Text(label("Choose the ZIP destination, then authorize with your Vault password.", "ZIP-এর গন্তব্য বেছে নিয়ে Vault password দিয়ে অনুমোদন করুন।"))
                } else {
                    if (currentAction == VaultAction.DELETE_ALL) Text(label("This permanently deletes every Vault file and its app-owned Telegram copies. There is no Trash. Enter the private recovery key to confirm.", "সব Vault ফাইল ও অ্যাপের Telegram কপি স্থায়ীভাবে মুছবে। Trash নেই। নিশ্চিত করতে প্রাইভেট রিকভারি কী দিন।"))
                    if (currentAction == VaultAction.DELETE) Text(label("Selected files and their app-owned Telegram copies will be deleted permanently. There is no Trash.", "নির্বাচিত ফাইল ও অ্যাপের Telegram কপি স্থায়ীভাবে মুছবে। Trash নেই।"))
                    VaultSecretField(actionPassword, { actionPassword = it }, if (currentAction == VaultAction.DELETE_ALL) label("Private recovery key", "প্রাইভেট রিকভারি কী") else label("Vault password", "Vault password"), fixedPattern = false)
                    if (currentAction == VaultAction.REMOVE) {
                        Text(label("Choose the password for this ZIP. The verified ZIP is saved before anything is removed from Vault.", "এই ZIP-এর password বাছুন। ZIP সেভ ও যাচাই হওয়ার পরেই Vault থেকে সরাবে।"), style = MaterialTheme.typography.bodySmall)
                        VaultSecretField(zipPassword, { zipPassword = it }, label("ZIP password", "ZIP password"), newPassword = true, fixedPattern = false)
                        VaultSecretField(zipConfirm, { zipConfirm = it }, label("Confirm ZIP password", "ZIP password নিশ্চিত করুন"), newPassword = true, fixedPattern = false)
                        PasswordCreationGuidance(zipPassword) {
                            zipPassword = it
                            zipConfirm = ""
                        }
                    }
                    if (currentAction == VaultAction.DOWNLOAD) {
                        Text(label("This new random password opens this ZIP only. Copy it before saving. Files remain in Vault.", "এই নতুন random password শুধু এই ZIP খুলবে। সেভ করার আগে কপি করুন। ফাইল Vault-এ থাকবে।"), style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(zipPassword, style = MaterialTheme.typography.bodyMedium) }
                        OutlinedButton({ copySecret(zipPassword) }, enabled = zipPassword.isNotBlank()) { Text(label("Copy ZIP password", "ZIP password কপি")) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(zipSaved, { zipSaved = it })
                            Text(label("I saved this password", "Password নিরাপদে রেখেছি"))
                        }
                    }
                }
            }
        }, confirmButton = {
            val needsDestination = currentAction in setOf(VaultAction.DOWNLOAD, VaultAction.REMOVE) && destination == null
            TextButton({
                if (needsDestination) {
                    export.launch("TG-Photos-Private-${System.currentTimeMillis()}.zip")
                } else {
                    val ids = actionIds
                    val output = destination
                    val secret = actionPassword.toCharArray()
                    val generated = zipPassword
                    action = null
                    actionPassword = ""
                    zipPassword = ""
                    zipConfirm = ""
                    work {
                        when (currentAction) {
                            VaultAction.MIGRATE -> {
                                vault.migrateLegacy(secret)
                                message = "Legacy files verified in the double-encrypted cloud Vault. Old encrypted device copies removed."
                            }
                            VaultAction.BIOMETRIC_ENABLE -> {
                                biometricRequest = vault.prepareBiometricEnrollment(secret)
                            }
                            VaultAction.BIOMETRIC_DISABLE -> {
                                vault.disableBiometric(secret)
                                biometricEnabled = false
                                message = "Fingerprint access disabled on this phone."
                            }
                            VaultAction.KEY -> {
                                shownSecret = vault.revealPrivateKey(secret)
                                shownSecretTitle = "Private recovery key"
                            }
                            VaultAction.DELETE_ALL -> {
                                vault.deleteEntireVault(secret)
                                message = "Entire Vault and app-owned Telegram copies deleted."
                            }
                            VaultAction.DELETE -> {
                                vault.deleteSelected(ids, secret)
                                selected = emptySet()
                                message = "Selected Vault files and app-owned Telegram copies deleted."
                            }
                            else -> {
                                vault.exportEncrypted(ids, checkNotNull(output), generated.toCharArray(), secret, currentAction == VaultAction.REMOVE)
                                shownSecret = generated
                                shownSecretTitle = "ZIP password"
                                selected = emptySet()
                                message = if (currentAction == VaultAction.REMOVE) "Verified encrypted ZIP saved; files removed from Vault and Telegram." else "Verified encrypted ZIP saved. Vault files retained."
                            }
                        }
                    }
                }
            }, enabled = needsDestination || actionPassword.isNotBlank() && (currentAction != VaultAction.DOWNLOAD || zipSaved && zipPassword.isNotBlank()) && (currentAction != VaultAction.REMOVE || NewPasswordPolicy.accepts(zipPassword) && zipPassword == zipConfirm)) { Text(if (needsDestination) label("Choose destination", "গন্তব্য বাছুন") else label("Confirm", "নিশ্চিত করুন")) }
        }, dismissButton = {
            TextButton({
                action = null
                actionPassword = ""
                zipPassword = ""
                zipConfirm = ""
            }) { Text(label("Cancel", "বাতিল")) }
        })
    }
    if (storageWarning) AlertDialog(onDismissRequest = { storageWarning = false }, title = { Text(label("Telegram storage", "Telegram স্টোরেজ")) }, text = { Box(Modifier.verticalScroll(rememberScrollState())) { TelegramStorageWarning() } }, confirmButton = { TextButton({ storageWarning = false }) { Text(label("I understand", "বুঝেছি")) } })
    if (shownSecret != null && unlocked) {
        AlertDialog(onDismissRequest = { shownSecret = null }, title = { Text(shownSecretTitle) }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectionContainer { Text(checkNotNull(shownSecret), style = MaterialTheme.typography.bodyLarge) }
                Text(label("Keep this secret privately. Do not take screenshots. Copied text is marked sensitive. After one minute, the app clears its unchanged copy when Android permits clipboard access, including after leaving this screen. If the app stops, clear the clipboard yourself.", "গোপনে নিরাপদে রাখুন। Screenshot নেবেন না। কপি sensitive হিসেবে চিহ্নিত। এক মিনিট পরে Android clipboard access দিলেই অ্যাপের অপরিবর্তিত কপি মুছবে, এই স্ক্রিন ছেড়ে গেলেও। অ্যাপ বন্ধ হয়ে গেলে নিজে clipboard মুছুন।"), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton({ copySecret(checkNotNull(shownSecret)) }) { Text(label("Copy", "কপি")) } }, dismissButton = { TextButton({ shownSecret = null }) { Text(label("Done", "শেষ")) } })
    }
    if (unlocked && opening != null) {
        val item = checkNotNull(opening)
        PrivateVaultPreview(item, vault.uri(item.id), model, exportCopy = {
            opening = null
            chooseAction(VaultAction.DOWNLOAD, setOf(item.id))
        }, close = { opening = null })
    }
}

@Composable
private fun VaultTransferStatus() {
    val context = LocalContext.current
    val vault = (context.applicationContext as TeleVaultApplication).privateVault
    val progress by vault.progress.collectAsStateWithLifecycle()
    val operation by vault.operation.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.CenterStart) {
        progress?.let { (done, total) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(progress = { if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                val caption = when (operation) {
                    "encrypt-upload" -> label("Encrypting files", "ফাইল এনক্রিপ্ট হচ্ছে")
                    "verify-upload" -> label("Verifying server copy", "সার্ভারের কপি যাচাই")
                    "commit-upload" -> label("Saving verified Vault entry", "যাচাই করা Vault এন্ট্রি সংরক্ষণ")
                    "zip" -> label("Encrypted ZIP", "এনক্রিপ্টেড ZIP")
                    "verify-zip" -> label("Verifying ZIP", "ZIP যাচাই")
                    else -> label("Encrypted upload", "এনক্রিপ্টেড আপলোড")
                }
                Text("  " + (if (total > 0) "${(done.toDouble() * 100 / total).toInt().coerceIn(0, 100)}% · " else "") + caption, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
