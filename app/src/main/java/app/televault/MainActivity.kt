package app.televault

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.televault.domain.AuthProtocol
import app.televault.domain.LockGrace
import app.televault.security.LocalDeviceAuthentication
import app.televault.security.LocalDeviceLock
import app.televault.ui.PhotosScreen
import app.televault.ui.TeleVaultRoot
import app.televault.ui.TeleVaultTheme
import app.televault.ui.UnlockScreen
import app.televault.ui.VaultForm
import app.televault.ui.label
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguage.context(base))
    }

    // Notification navigation waits behind the normal authentication and Drive-readiness gates.
    private var exportRequestToken by mutableStateOf(0)
    private var exportRequestSequence = 0
    private var incomingMedia by mutableStateOf<IncomingMedia?>(null)
    private fun parseIncoming(intent: Intent) = try {
        IncomingMedia.read(intent, packageName)
    } catch (_: Exception) {
        null
    }
    private fun acceptExportNavigation(request: Intent?) {
        if (request?.getBooleanExtra("openCloudExports", false) != true) return
        request.removeExtra("openCloudExports")
        exportRequestSequence = if (exportRequestSequence == Int.MAX_VALUE) 1 else exportRequestSequence + 1
        exportRequestToken = exportRequestSequence
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptExportNavigation(intent)
        parseIncoming(intent)?.let { incomingMedia = it }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("exportRequestSequence", exportRequestSequence)
        outState.putInt("exportRequestToken", exportRequestToken)
        super.onSaveInstanceState(outState)
    }
    internal val videoPipVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var pipPlayer by mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null)
    fun supportsVideoPiP(): Boolean = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    internal fun enterVideoPiP(player: androidx.media3.exoplayer.ExoPlayer): Boolean {
        val drive = vaultModel ?: return false
        if (!supportsVideoPiP() || !authenticated.value || drive.state.value.stage != app.televault.data.VaultStage.READY || isFinishing || isDestroyed) return false
        val epoch = drive.playbackEpoch
        pipPlayer = player
        videoPipVisible.value = true
        // Compose first transfers the surface from the Dialog into this Activity.
        window.decorView.postOnAnimation {
            window.decorView.postOnAnimation {
                if (pipPlayer !== player || !authenticated.value || drive.playbackEpoch != epoch || drive.state.value.stage != app.televault.data.VaultStage.READY) {
                    revokePiP()
                    return@postOnAnimation
                }
                val size = player.videoSize
                val ratio = if (size.width > 0 && size.height > 0) (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(1f / 2.39f, 2.39f) else 16f / 9f
                val params = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational((ratio * 1000).toInt(), 1000))
                if (Build.VERSION.SDK_INT >= 31) params.setAutoEnterEnabled(false).setSeamlessResizeEnabled(true)
                if (runCatching { enterPictureInPictureMode(params.build()) }.getOrDefault(false).not()) {
                    pipPlayer = null
                    videoPipVisible.value = false
                }
            }
        }
        return true
    }
    internal fun detachVideoPiP(player: androidx.media3.exoplayer.ExoPlayer) {
        if (pipPlayer === player) revokePiP()
    }
    private fun revokePiP() {
        pipPlayer?.pause()
        pipPlayer?.clearVideoSurface()
        pipPlayer = null
        // A revoked session renders black until the system dismisses the PiP window.
        videoPipVisible.value = isInPictureInPictureMode
    }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        videoPipVisible.value = inPictureInPictureMode
        if (!inPictureInPictureMode) pipPlayer = null
    }
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                revokePiP()
                (application as TeleVaultApplication).lockExistingPrivateVault()
            }
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Reject tapjacking, including partially obscured touches; app screenshots stay available.
        if (event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return false
        return super.dispatchTouchEvent(event)
    }
    private val passwordRequested = mutableStateOf(false)
    private val failures = mutableStateOf(0)
    private var appPasswordAvailable = false
    private var deviceGateRequired = false
    private var authenticationGeneration = 0L
    private fun openPassword() {
        if (!appPasswordAvailable) return
        appLock.revoke()
        authenticationGeneration++
        signal?.cancel()
        pending = null
        promptRunning.value = false
        authenticated.value = false
        vaultModel?.lock()
        passwordRequested.value = true
    }
    private val authenticated = mutableStateOf(false)
    private val promptRunning = mutableStateOf(false)
    private var pending: (() -> Unit)? = null
    private var credentialInFlight = false
    private var backgroundAt = -1L
    private var graceMinutes = 2
    private val appLock by lazy { (application as TeleVaultApplication).appLockStore }
    private val grantChecked = mutableStateOf(false)
    private var authenticatedDay = ""
    private fun currentLockDay() = java.time.LocalDate.now().toString() + "@" + java.time.ZoneId.systemDefault().id
    private var vaultModel: DriveViewModel? = null
    private val lockHandler = Handler(Looper.getMainLooper())
    private val expireLock = Runnable { expireAuthentication() }
    private fun markAuthenticated() {
        authenticated.value = true
        authenticatedDay = currentLockDay()
        grantChecked.value = true
        val scope = vaultModel?.guidanceScope ?: return
        val mode = graceMinutes
        val token = appLock.generation()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { appLock.save(scope, mode, token) } }
    }
    private fun expireAuthentication() {
        appLock.revoke()
        grantChecked.value = false
        revokePiP()
        authenticationGeneration++
        signal?.cancel()
        pending = null
        promptRunning.value = false
        passwordRequested.value = false
        authenticated.value = false
        vaultModel?.lock()
    }
    private var credentialGeneration: Long? = null
    private var signal: CancellationSignal? = null
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val completedGeneration = credentialGeneration
        credentialGeneration = null
        credentialInFlight = false
        if (completedGeneration == null || completedGeneration != authenticationGeneration || pending == null) {
            Handler(Looper.getMainLooper()).post {
                if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && deviceGateRequired && !authenticated.value && !passwordRequested.value && !promptRunning.value) authenticate {}
            }
            return@registerForActivityResult
        }
        promptRunning.value = false
        val complete = pending
        pending = null
        if (result.resultCode == RESULT_OK) {
            failures.value = 0
            passwordRequested.value = false
            markAuthenticated()
            complete?.invoke()
        }
    }
    private fun authenticate(sensitive: Boolean = false, complete: () -> Unit) {
        if (pending != null || credentialInFlight) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            return
        }
        val requestGeneration = ++authenticationGeneration
        pending = complete
        promptRunning.value = true
        fun pin() {
            val intent = keyguard.createConfirmDeviceCredentialIntent("TG Photos", if (sensitive) "Verify this file action" else "Unlock your private gallery")
            if (intent != null) {
                credentialInFlight = true
                credentialGeneration = requestGeneration
                credential.launch(intent)
            } else {
                pending = null
                promptRunning.value = false
            }
        }
        if (Build.VERSION.SDK_INT < 28) {
            pin()
            return
        }
        val executor = java.util.concurrent.Executor { Handler(Looper.getMainLooper()).post(it) }
        val builder = BiometricPrompt.Builder(this).setTitle("TG Photos").setSubtitle(if (sensitive) "Verify this file action" else "Verify to open your private gallery")
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else if (Build.VERSION.SDK_INT >= 29) {
            builder.setDeviceCredentialAllowed(true)
        } else {
            builder.setNegativeButton("Use phone PIN", executor) { _, _ -> if (requestGeneration == authenticationGeneration) pin() }
        }
        signal = CancellationSignal()
        builder.build().authenticate(
            signal!!,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationFailed() {
                    if (requestGeneration != authenticationGeneration) return
                    failures.value++
                    if (!sensitive && failures.value >= 3 && appPasswordAvailable) openPassword()
                }
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (requestGeneration != authenticationGeneration) return
                    val action = pending
                    pending = null
                    promptRunning.value = false
                    failures.value = 0
                    passwordRequested.value = false
                    markAuthenticated()
                    action?.invoke()
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (requestGeneration != authenticationGeneration) return
                    if (code in setOf(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT) && (sensitive || appPasswordAvailable)) {
                        if (sensitive) pin() else openPassword()
                    } else if (code in setOf(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE)) {
                        pin()
                    } else if (!credentialInFlight) {
                        pending = null
                        promptRunning.value = false
                    }
                }
            }
        )
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { (application as TeleVaultApplication).telegram.reconnect() } }
        lockHandler.removeCallbacks(expireLock)
        if (LockGrace.expired(backgroundAt, SystemClock.elapsedRealtime(), graceMinutes)) expireAuthentication()
        if (graceMinutes == LockGrace.DAILY && authenticated.value && authenticatedDay != currentLockDay()) expireAuthentication()
        backgroundAt = -1L
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !videoPipVisible.value) {
            backgroundAt = SystemClock.elapsedRealtime()
            lockHandler.removeCallbacks(expireLock)
            if (authenticated.value && graceMinutes !in setOf(LockGrace.OFF, LockGrace.DAILY)) {
                val scope = vaultModel?.guidanceScope
                val mode = graceMinutes
                val token = appLock.generation()
                val wall = System.currentTimeMillis()
                val elapsed = backgroundAt
                if (scope != null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { appLock.save(scope, mode, token, wall, elapsed) } }
            }
            if (graceMinutes != LockGrace.OFF) {
                val delay = if (graceMinutes == LockGrace.DAILY) {
                    val now = java.time.ZonedDateTime.now()
                    java.time.Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis().coerceAtLeast(1)
                } else {
                    LockGrace.durationMillis(graceMinutes)
                }
                lockHandler.postDelayed(expireLock, delay)
            }
        }
    }
    override fun onDestroy() {
        authenticationGeneration++
        pending = null
        signal?.cancel()
        lockHandler.removeCallbacks(expireLock)
        if (!isChangingConfigurations) {
            vaultModel?.lock()
            (application as TeleVaultApplication).lockExistingPrivateVault()
        }
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportRequestSequence = savedInstanceState?.getInt("exportRequestSequence", 0) ?: 0
        exportRequestToken = savedInstanceState?.getInt("exportRequestToken", 0) ?: 0
        // Saved state owns an already handled/pending request after recreation. The system
        // can restore the original launch Intent, whose notification extra must not replay.
        if (savedInstanceState == null) acceptExportNavigation(intent) else intent?.removeExtra("openCloudExports")
        if (savedInstanceState == null) incomingMedia = intent?.let(::parseIncoming)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        setContent {
            val pipVisible by videoPipVisible.collectAsStateWithLifecycle()
            val preferences = remember { PreferencesRepository(applicationContext) }
            val dynamicColor by preferences.dynamicColor.collectAsStateWithLifecycle(initialValue = true)
            val appearanceMode by preferences.appearanceMode.collectAsStateWithLifecycle(initialValue = AppearanceMode.SYSTEM)
            val interfaceStyle by preferences.interfaceStyle.collectAsStateWithLifecycle(initialValue = InterfaceStyle.GLASS)
            val configuredLock by preferences.lockGraceMinutes.collectAsStateWithLifecycle(initialValue = null)
            val lockGraceMinutes = configuredLock ?: 2
            LaunchedEffect(lockGraceMinutes) { graceMinutes = lockGraceMinutes }
            var startupAttempt by remember { mutableStateOf(0) }
            var startupFailed by remember(startupAttempt) { mutableStateOf(false) }
            val services by produceState<TeleVaultApplication.Services?>(null, startupAttempt) {
                try {
                    value = (application as TeleVaultApplication).prepareServices()
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    startupFailed = true
                }
            }
            val ready = services
            if (ready == null) {
                TeleVaultTheme(dynamicColor, appearanceMode, interfaceStyle) {
                    app.televault.ui.StartupScreen(startupFailed) { startupAttempt++ }
                }
                return@setContent
            }
            val model: AuthViewModel = viewModel(factory = AuthViewModel.Factory(ready.telegram))
            val auth by model.authorization.collectAsStateWithLifecycle()
            val phoneCountries by model.phoneCountries.collectAsStateWithLifecycle()
            val drive: DriveViewModel = viewModel(factory = DriveViewModel.Factory(ready.drive))
            vaultModel = drive
            val driveState by drive.state.collectAsStateWithLifecycle()
            val protection = (application as TeleVaultApplication).protection
            val protectionReason by protection.reason.collectAsStateWithLifecycle()
            LaunchedEffect(protectionReason) { if (protectionReason != app.televault.security.DeviceProtection.Reason.NONE) expireAuthentication() }
            var discoveredSession by remember(auth.stage) { mutableStateOf(false) }
            deviceGateRequired = protectionReason == app.televault.security.DeviceProtection.Reason.NONE && discoveredSession && auth.stage == AuthProtocol.State.READY && driveState.quickUnlockAvailable && driveState.stage in setOf(app.televault.data.VaultStage.LOCKED, app.televault.data.VaultStage.READY)
            appPasswordAvailable = protectionReason == app.televault.security.DeviceProtection.Reason.NONE && auth.stage == AuthProtocol.State.READY && driveState.stage in setOf(app.televault.data.VaultStage.LOCKED, app.televault.data.VaultStage.READY)
            val grantScope = if (discoveredSession) drive.guidanceScope else ""
            LaunchedEffect(discoveredSession, deviceGateRequired, authenticated.value, configuredLock, grantScope, passwordRequested.value) {
                if (authenticated.value) return@LaunchedEffect
                grantChecked.value = false
                if (!deviceGateRequired || configuredLock == null || passwordRequested.value) return@LaunchedEffect
                val generation = authenticationGeneration
                val permitted = appLock.permits(grantScope, lockGraceMinutes)
                if (generation == authenticationGeneration && grantScope == drive.guidanceScope && !protection.blocked()) {
                    if (permitted) {
                        authenticatedDay = currentLockDay()
                        authenticated.value = true
                    }
                    grantChecked.value = true
                }
            }
            LaunchedEffect(authenticated.value, driveState.stage, auth.stage, discoveredSession) {
                if (discoveredSession && authenticated.value && auth.stage == AuthProtocol.State.READY && driveState.stage == app.televault.data.VaultStage.LOCKED) drive.quickUnlock()
            }
            LaunchedEffect(auth.stage, protectionReason) {
                if (auth.stage == AuthProtocol.State.READY && !protection.blocked()) {
                    drive.discover().join()
                    discoveredSession = true
                } else if (auth.stage in setOf(AuthProtocol.State.LOGGING_OUT, AuthProtocol.State.CLOSED)) {
                    expireAuthentication()
                }
            }
            TeleVaultTheme(dynamicColor = dynamicColor, appearanceMode = appearanceMode, interfaceStyle = interfaceStyle) {
                CompositionLocalProvider(
                    LocalDeviceAuthentication provides { action -> authenticate(complete = action) },
                    app.televault.security.LocalSensitiveAuthentication provides { action -> authenticate(sensitive = true, complete = action) },
                    LocalDeviceLock provides {
                        expireAuthentication()
                    }
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (protectionReason == app.televault.security.DeviceProtection.Reason.NONE) app.televault.ui.TelegramStorageFirstOpen(preferences)
                        if (protectionReason != app.televault.security.DeviceProtection.Reason.NONE) {
                            app.televault.ui.ProtectionScreen(protectionReason)
                        } else if (!deviceGateRequired && !authenticated.value) {
                            // Initial login/discovery/recovery never depends on this phone's biometric key.
                            TeleVaultRoot(auth, model::submit, dynamicColor, preferences::setDynamicColor, phoneCountries, model::preparePhoneCountries) {
                                if (!discoveredSession) {
                                    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        app.televault.ui.SecureLoader()
                                        Text(label("Finding your encrypted Drive…", "আপনার এনক্রিপ্টেড Drive খোঁজা হচ্ছে…"))
                                    }
                                } else {
                                    val setupGeneration = authenticationGeneration
                                    VaultForm(driveState, drive) { verified ->
                                        if (verified && setupGeneration == authenticationGeneration) {
                                            failures.value = 0
                                            markAuthenticated()
                                        }
                                    }
                                }
                            }
                        } else if (authenticated.value) {
                            TeleVaultRoot(auth, model::submit, dynamicColor, preferences::setDynamicColor, phoneCountries, model::preparePhoneCountries) {
                                PhotosScreen(drive, dynamicColor, preferences::setDynamicColor, appearanceMode, preferences::setAppearanceMode, interfaceStyle, preferences::setInterfaceStyle, lockGraceMinutes, { minutes ->
                                    val token = appLock.generation()
                                    appLock.save(drive.guidanceScope, LockGrace.minutes(minutes), token)
                                    preferences.setLockGraceMinutes(minutes)
                                    graceMinutes = LockGrace.minutes(minutes)
                                    authenticatedDay = currentLockDay()
                                }, logout = { model.submit(AuthProtocol.Action.LOGOUT) }, exportRequestToken = exportRequestToken, onExportRequestHandled = { handled ->
                                    if (exportRequestToken == handled) exportRequestToken = 0
                                }, incomingMedia = incomingMedia, onIncomingHandled = { incomingMedia = null })
                            }
                        } else {
                            // Do not compose photos or viewers behind a translucent system prompt.
                            BackHandler { if (passwordRequested.value) expireAuthentication() else moveTaskToBack(true) }
                            LaunchedEffect(deviceGateRequired, grantChecked.value) { if (deviceGateRequired && grantChecked.value && !passwordRequested.value) authenticate {} }
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                if (passwordRequested.value) {
                                    val passwordGeneration = authenticationGeneration
                                    Column(Modifier.fillMaxSize()) {
                                        TextButton({ expireAuthentication() }, Modifier.safeDrawingPadding()) { Text(label("Back to fingerprint / phone PIN", "ফিঙ্গারপ্রিন্ট / ফোনের PIN-এ ফিরুন")) }
                                        Box(Modifier.weight(1f)) {
                                            VaultForm(driveState, drive) { verified ->
                                                if (verified && passwordRequested.value && passwordGeneration == authenticationGeneration) {
                                                    failures.value = 0
                                                    passwordRequested.value = false
                                                    markAuthenticated()
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    UnlockScreen(unlock = { authenticate {} }, busy = promptRunning.value || !grantChecked.value, notice = if (failures.value >= 3) label("Biometric verification failed. Try your phone PIN or available vault-password option.", "বায়োমেট্রিক যাচাই হয়নি। ফোনের PIN বা ভল্ট পাসওয়ার্ডের অপশন ব্যবহার করুন।") else "", recovery = if (appPasswordAvailable && grantChecked.value) ({ openPassword() }) else null, back = { moveTaskToBack(true) })
                                }
                            }
                        }
                        if (pipVisible) app.televault.ui.VideoPipOverlay(pipPlayer)
                    }
                }
            }
        }
    }
}
