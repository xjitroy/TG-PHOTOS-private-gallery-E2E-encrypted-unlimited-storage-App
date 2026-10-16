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
    // Notification navigation waits behind the normal authentication and Drive-readiness gates.
    private var exportRequestToken by mutableStateOf(0)
    private var exportRequestSequence = 0
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
            if (intent?.action == Intent.ACTION_SCREEN_OFF) expireAuthentication()
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
    private var vaultModel: DriveViewModel? = null
    private val lockHandler = Handler(Looper.getMainLooper())
    private val expireLock = Runnable { expireAuthentication() }
    private fun expireAuthentication() {
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
            authenticated.value = true
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
                    authenticated.value = true
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
        lifecycleScope.launch { runCatching { (application as TeleVaultApplication).telegram.reconnect() } }
        lockHandler.removeCallbacks(expireLock)
        if (LockGrace.expired(backgroundAt, SystemClock.elapsedRealtime(), graceMinutes)) expireAuthentication()
        backgroundAt = -1L
        lockHandler.post {
            if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && deviceGateRequired && !authenticated.value && !passwordRequested.value && (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) authenticate {}
        }
    }
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !videoPipVisible.value) {
            backgroundAt = SystemClock.elapsedRealtime()
            lockHandler.removeCallbacks(expireLock)
            lockHandler.postDelayed(expireLock, LockGrace.durationMillis(graceMinutes))
        }
    }
    override fun onDestroy() {
        authenticationGeneration++
        pending = null
        signal?.cancel()
        lockHandler.removeCallbacks(expireLock)
        if (!isChangingConfigurations) {
            vaultModel?.lock()
            (application as TeleVaultApplication).privateVault.lock()
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
            val lockGraceMinutes by preferences.lockGraceMinutes.collectAsStateWithLifecycle(initialValue = 2)
            LaunchedEffect(lockGraceMinutes) { graceMinutes = lockGraceMinutes }
            val model: AuthViewModel = viewModel(factory = AuthViewModel.Factory((application as TeleVaultApplication).telegram))
            val auth by model.authorization.collectAsStateWithLifecycle()
            val phoneCountries by model.phoneCountries.collectAsStateWithLifecycle()
            var startupAttempt by remember { mutableStateOf(0) }
            val driveResult = remember(startupAttempt) { runCatching { (application as TeleVaultApplication).drive } }
            if (driveResult.isFailure) {
                TeleVaultTheme(dynamicColor, appearanceMode, interfaceStyle) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Text(label("TG Photos could not start", "TG Photos চালু হতে পারেনি"), style = MaterialTheme.typography.titleLarge)
                            Text(label("Check available storage, then retry. Your existing Drive and password have not been reset.", "ফোনের খালি জায়গা পরীক্ষা করে আবার চেষ্টা করুন। আপনার Drive বা পাসওয়ার্ড রিসেট হয়নি।"))
                            TextButton({ startupAttempt++ }) { Text(label("Retry", "আবার চেষ্টা")) }
                        }
                    }
                }
                return@setContent
            }
            val drive: DriveViewModel = viewModel(factory = DriveViewModel.Factory(driveResult.getOrThrow()))
            vaultModel = drive
            val driveState by drive.state.collectAsStateWithLifecycle()
            val protection = (application as TeleVaultApplication).protection
            val protectionReason by protection.reason.collectAsStateWithLifecycle()
            LaunchedEffect(protectionReason) { if (protectionReason != app.televault.security.DeviceProtection.Reason.NONE) expireAuthentication() }
            var discoveredSession by remember(auth.stage) { mutableStateOf(false) }
            deviceGateRequired = protectionReason == app.televault.security.DeviceProtection.Reason.NONE && discoveredSession && auth.stage == AuthProtocol.State.READY && driveState.quickUnlockAvailable && driveState.stage in setOf(app.televault.data.VaultStage.LOCKED, app.televault.data.VaultStage.READY)
            appPasswordAvailable = protectionReason == app.televault.security.DeviceProtection.Reason.NONE && auth.stage == AuthProtocol.State.READY && driveState.stage in setOf(app.televault.data.VaultStage.LOCKED, app.televault.data.VaultStage.READY)
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
                                            authenticated.value = true
                                        }
                                    }
                                }
                            }
                        } else if (authenticated.value) {
                            TeleVaultRoot(auth, model::submit, dynamicColor, preferences::setDynamicColor, phoneCountries, model::preparePhoneCountries) {
                                PhotosScreen(drive, dynamicColor, preferences::setDynamicColor, appearanceMode, preferences::setAppearanceMode, interfaceStyle, preferences::setInterfaceStyle, lockGraceMinutes, { minutes ->
                                    graceMinutes = LockGrace.minutes(minutes)
                                    preferences.setLockGraceMinutes(minutes)
                                }, logout = { model.submit(AuthProtocol.Action.LOGOUT) }, exportRequestToken = exportRequestToken, onExportRequestHandled = { handled ->
                                    if (exportRequestToken == handled) exportRequestToken = 0
                                })
                            }
                        } else {
                            // Do not compose photos or viewers behind a translucent system prompt.
                            BackHandler { if (passwordRequested.value) expireAuthentication() else moveTaskToBack(true) }
                            LaunchedEffect(deviceGateRequired) { if (deviceGateRequired) authenticate {} }
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
                                                    authenticated.value = true
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    UnlockScreen(unlock = { authenticate {} }, busy = promptRunning.value, notice = if (failures.value >= 3) label("Biometric verification failed. Try your phone PIN or available vault-password option.", "বায়োমেট্রিক যাচাই হয়নি। ফোনের PIN বা ভল্ট পাসওয়ার্ডের অপশন ব্যবহার করুন।") else "", recovery = if (appPasswordAvailable) ({ openPassword() }) else null, back = { moveTaskToBack(true) })
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
