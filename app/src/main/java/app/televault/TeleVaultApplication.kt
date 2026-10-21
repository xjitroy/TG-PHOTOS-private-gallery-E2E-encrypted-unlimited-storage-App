package app.televault

import android.app.Application
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import app.televault.data.DriveRepository
import app.televault.telegram.TdlibTelegramGateway
import app.televault.telegram.TelegramCredentials
import app.televault.telegram.TelegramGateway
import kotlinx.coroutines.launch

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class TeleVaultApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguage.context(base))
    }

    @Volatile internal var intelligencePaused = false
    internal val smartModels by lazy { app.televault.smart.SmartModels(this) }
    internal val faceModels by lazy { app.televault.people.FaceModels(this) }
    internal val memoryModels by lazy { app.televault.memories.MemoryModels(this) }
    internal val ocrModels by lazy { app.televault.ocr.OcrModels(this) }
    internal val appLockStore by lazy { app.televault.security.AppLockStore(this) }
    val motion by lazy { AppMotion(this) }
    private val privateVaultLazy = lazy { app.televault.data.PrivateVaultRepository(this) }
    internal val privateVault get() = privateVaultLazy.value
    internal fun lockExistingPrivateVault() {
        if (privateVaultLazy.isInitialized()) privateVaultLazy.value.lock()
    }
    private val startupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    internal data class Services(val telegram: TelegramGateway, val drive: DriveRepository)
    private val startup = StartupLoader(startupScope) {
        val gateway = telegram
        val repository = drive
        repository.people
        repository.ocr
        Services(gateway, repository)
    }
    internal suspend fun prepareServices() = startup.load()
    internal val forgottenVaults by lazy { app.televault.data.ForgottenVaultDeletion(this) }
    val protection by lazy { app.televault.security.DeviceProtection(this) }
    private val driveLazy = lazy { DriveRepository(this, telegram, protection) }
    override fun onCreate() {
        super.onCreate()
        // The bound inference process must never start account services or background jobs.
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else runCatching { java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
        if (process.endsWith(":intelligence")) return
        RuntimeDiagnostics.install(this)
        val animationClock = motion
        androidx.compose.ui.platform.WindowRecomposerPolicy.setFactory { view ->
            view.createLifecycleAwareWindowRecomposer(coroutineContext = animationClock)
        }
        // Security callbacks remain synchronous, including in worker-only processes.
        protection.onBlocked {
            if (driveLazy.isInitialized()) driveLazy.value.lock()
            lockExistingPrivateVault()
        }
        // Recover work/storage on IO; never keep Android's first frame behind file/Room locks.
        startupScope.launch {
            runCatching { app.televault.data.MediaIntelligenceScheduling.ensure(this@TeleVaultApplication) }
            runCatching { app.televault.data.SharedMedia.schedule(this@TeleVaultApplication) }
            runCatching { app.televault.data.BackupScheduling.request(this@TeleVaultApplication) }
            runCatching { forgottenVaults.scheduleRecovery() }
        }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == TRIM_MEMORY_RUNNING_CRITICAL) smartModels.memoryPressure()
        if (level >= TRIM_MEMORY_RUNNING_LOW && driveLazy.isInitialized()) driveLazy.value.trimMemory()
    }
    val telegram: TelegramGateway by lazy {
        TdlibTelegramGateway(this, TelegramCredentials(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH), protection, { driveLazy.isInitialized() && !driveLazy.value.policy.settings().dataSaver })
    }
    val drive: DriveRepository get() = driveLazy.value
}
