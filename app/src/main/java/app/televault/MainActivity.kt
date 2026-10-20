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
import androidx.lifecycle.withResumed
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
    private var externalReturn = ExternalViewerReturn()
    private fun acceptIncoming(request: Intent) {
        val media = parseIncoming(request)
        if (media != null) {
            externalReturn.accept(media.id.takeUnless { media.backup })
            incomingMedia = media
        } else if (request.action == Intent.ACTION_MAIN) {
            externalReturn.accept(null)
        }
    }
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
        acceptIncoming(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("externalViewerReturn", externalReturn.requestId)
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
    private val verification = app.televault.security.DeviceVerificationRequest()
    private fun cancelVerification() {
        // Revoke the generation before cancelling: some OEMs deliver cancellation synchronously.
        verification.invalidate()
        signal?.cancel()
        signal = null
        promptRunning.value = false
    }
    private fun retryVerification(phonePin: Boolean = false) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        cancelVerification()
        authenticate(preferCredential = phonePin) {}
    }
    private fun openPassword() {
        if (!appPasswordAvailable) return
        appLock.revoke()
        cancelVerification()
        authenticated.value = false
        vaultModel?.lock()
        passwordRequested.value = true
    }
    private val authenticated = mutableStateOf(false)
    private val promptRunning = mutableStateOf(false)
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
        cancelVerification()
        passwordRequested.value = false
        authenticated.value = false
        vaultModel?.lock()
    }
    private var signal: CancellationSignal? = null
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val token = verification.credentialGeneration ?: return@registerForActivityResult
        val complete = verification.take(token) ?: return@registerForActivityResult
        promptRunning.value = false
        signal = null
        if (result.resultCode == RESULT_OK) {
            failures.value = 0
            passwordRequested.value = false
            markAuthenticated()
            complete()
        }
    }
    private fun authenticate(sensitive: Boolean = false, preferCredential: Boolean = false, complete: () -> Unit) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            return
        }
        val requestGeneration = verification.begin(complete) ?: return
        promptRunning.value = true
        fun pin() {
            if (requestGeneration != verification.generation) return
            try {
                val intent = keyguard.createConfirmDeviceCredentialIntent("TG Photos", if (sensitive) "Verify this file action" else "Unlock your private gallery")
                if (intent != null && verification.credential(requestGeneration)) credential.launch(intent) else cancelVerification()
            } catch (_: Exception) {
                cancelVerification()
            }
        }
        if (preferCredential || Build.VERSION.SDK_INT < 28) {
            pin()
            return
        }
        val executor = java.util.concurrent.Executor { Handler(Looper.getMainLooper()).post(it) }
        try {
            val builder = BiometricPrompt.Builder(this).setTitle("TG Photos").setSubtitle(if (sensitive) "Verify this file action" else "Verify to open your private gallery")
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            } else if (Build.VERSION.SDK_INT >= 29) {
                builder.setDeviceCredentialAllowed(true)
            } else {
                builder.setNegativeButton("Use phone PIN", executor) { _, _ -> pin() }
            }
            signal = CancellationSignal()
            builder.build().authenticate(
                signal!!,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationFailed() {
                        if (requestGeneration != verification.generation) return
                        failures.value++
                        if (!sensitive && failures.value >= 3 && appPasswordAvailable) openPassword()
                    }
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val action = verification.take(requestGeneration) ?: return
                        signal = null
                        promptRunning.value = false
                        failures.value = 0
                        passwordRequested.value = false
                        markAuthenticated()
                        action()
                    }
                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        if (requestGeneration != verification.generation) return
                        if (code in setOf(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT) && !sensitive && appPasswordAvailable) {
                            openPassword()
                        } else if (code in setOf(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT, BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE)) {
                            pin()
                        } else if (!verification.credentialInFlight) {
                            cancelVerification()
                        }
                    }
                }
            )
        } catch (_: Exception) {
            if (requestGeneration == verification.generation) cancelVerification()
        }
    }
    override fun onPostResume() {
        super.onPostResume()
        // Activity results normally arrive before RESUMED. Recover OEM-dropped results only
        // after returning from the external credential screen, never while it is displayed.
        val token = verification.generation
        lockHandler.postDelayed({
            if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && token == verification.generation && verification.resumedWithoutCredentialResult()) {
                signal?.cancel()
                signal = null
                promptRunning.value = false
            }
        }, 500)
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
        if (verification.background()) {
            signal?.cancel()
            signal = null
            promptRunning.value = false
        }
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
        cancelVerification()
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
        externalReturn = ExternalViewerReturn(savedInstanceState?.getString("externalViewerReturn"))
        if (savedInstanceState == null || externalReturn.requestId != null) intent?.let(::acceptIncoming)
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
                val generation = verification.generation
                val permitted = appLock.permits(grantScope, lockGraceMinutes)
                if (generation == verification.generation && grantScope == drive.guidanceScope && !protection.blocked()) {
                    if (permitted) {
                        authenticatedDay = currentLockDay()
                        authenticated.value = true
                    }
                    grantChecked.value = true
                }
            }
            var quietUnlockFinished by remember(grantScope, authenticated.value) { mutableStateOf(false) }
            LaunchedEffect(authenticated.value, driveState.stage, auth.stage, discoveredSession, grantScope) {
                if (discoveredSession && authenticated.value && auth.stage == AuthProtocol.State.READY && driveState.stage == app.televault.data.VaultStage.LOCKED) {
                    try {
                        drive.quickUnlock().join()
                    } finally {
                        quietUnlockFinished = true
                    }
                }
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
                                    app.televault.ui.QuietStartupGate()
                                } else {
                                    val setupGeneration = verification.generation
                                    VaultForm(driveState, drive) { verified ->
                                        if (verified && setupGeneration == verification.generation) {
                                            failures.value = 0
                                            markAuthenticated()
                                        }
                                    }
                                }
                            }
                        } else if (authenticated.value) {
                            TeleVaultRoot(auth, model::submit, dynamicColor, preferences::setDynamicColor, phoneCountries, model::preparePhoneCountries) {
                                if (driveState.stage == app.televault.data.VaultStage.LOCKED && driveState.quickUnlockAvailable && !quietUnlockFinished) {
                                    app.televault.ui.QuietStartupGate()
                                    return@TeleVaultRoot
                                }
                                PhotosScreen(drive, dynamicColor, preferences::setDynamicColor, appearanceMode, preferences::setAppearanceMode, interfaceStyle, preferences::setInterfaceStyle, lockGraceMinutes, { minutes ->
                                    val token = appLock.generation()
                                    appLock.save(drive.guidanceScope, LockGrace.minutes(minutes), token)
                                    preferences.setLockGraceMinutes(minutes)
                                    graceMinutes = LockGrace.minutes(minutes)
                                    authenticatedDay = currentLockDay()
                                }, logout = { model.submit(AuthProtocol.Action.LOGOUT) }, exportRequestToken = exportRequestToken, onExportRequestHandled = { handled ->
                                    if (exportRequestToken == handled) exportRequestToken = 0
                                }, incomingMedia = incomingMedia, onIncomingHandled = { incomingMedia = null }, onExternalViewerClosed = { id ->
                                    if (externalReturn.close(id)) finish()
                                })
                            }
                        } else {
                            // Do not compose photos or viewers behind a translucent system prompt.
                            BackHandler { if (passwordRequested.value) expireAuthentication() else moveTaskToBack(true) }
                            LaunchedEffect(deviceGateRequired, grantChecked.value) {
                                lifecycle.withResumed {
                                    if (deviceGateRequired && grantChecked.value && !passwordRequested.value) authenticate {}
                                }
                            }
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                if (!grantChecked.value && !passwordRequested.value) {
                                    // Keep a quiet theme-matched gate while the encrypted receipt
                                    // is checked; valid Off/daily grants never flash password UI.
                                    app.televault.ui.QuietStartupGate()
                                } else if (passwordRequested.value) {
                                    val passwordGeneration = verification.generation
                                    Column(Modifier.fillMaxSize()) {
                                        TextButton({ expireAuthentication() }, Modifier.safeDrawingPadding()) { Text(label("Back to fingerprint / phone PIN", "ফিঙ্গারপ্রিন্ট / ফোনের PIN-এ ফিরুন")) }
                                        Box(Modifier.weight(1f)) {
                                            VaultForm(driveState, drive) { verified ->
                                                if (verified && passwordRequested.value && passwordGeneration == verification.generation) {
                                                    failures.value = 0
                                                    passwordRequested.value = false
                                                    markAuthenticated()
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    UnlockScreen(unlock = { retryVerification() }, busy = promptRunning.value, ready = grantChecked.value, phonePin = if (grantChecked.value) ({ retryVerification(true) }) else null, notice = if (failures.value >= 3) label("Biometric verification failed. Try your phone PIN or available vault-password option.", "বায়োমেট্রিক যাচাই হয়নি। ফোনের PIN বা ভল্ট পাসওয়ার্ডের অপশন ব্যবহার করুন।") else "", recovery = if (appPasswordAvailable && grantChecked.value) ({ openPassword() }) else null, back = { moveTaskToBack(true) })
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
