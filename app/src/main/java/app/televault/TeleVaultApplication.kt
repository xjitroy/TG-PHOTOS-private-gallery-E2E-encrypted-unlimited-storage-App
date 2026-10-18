package app.televault

import android.app.Application
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import app.televault.data.DriveRepository
import app.televault.telegram.TdlibTelegramGateway
import app.televault.telegram.TelegramCredentials
import app.televault.telegram.TelegramGateway

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class TeleVaultApplication : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLanguage.context(base))
    }
    internal val faceModels by lazy { app.televault.people.FaceModels(this) }
    internal val ocrModels by lazy { app.televault.ocr.OcrModels(this) }
    val motion by lazy { AppMotion(this) }
    internal val privateVault by lazy { app.televault.data.PrivateVaultRepository(this) }
    internal val forgottenVaults by lazy { app.televault.data.ForgottenVaultDeletion(this) }
    val protection by lazy { app.televault.security.DeviceProtection(this) }
    private val driveLazy = lazy { DriveRepository(this, telegram, protection) }
    override fun onCreate() {
        super.onCreate()
        RuntimeDiagnostics.install(this)
        val animationClock = motion
        androidx.compose.ui.platform.WindowRecomposerPolicy.setFactory { view ->
            view.createLifecycleAwareWindowRecomposer(coroutineContext = animationClock)
        }
        protection.onBlocked {
            if (driveLazy.isInitialized()) driveLazy.value.lock()
            privateVault.lock()
        }
        runCatching { app.televault.data.SharedMedia.schedule(this) }
        runCatching { app.televault.data.BackupScheduling.request(this) }
        runCatching { forgottenVaults.scheduleRecovery() }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && driveLazy.isInitialized()) driveLazy.value.trimMemory()
    }
    val telegram: TelegramGateway by lazy {
        TdlibTelegramGateway(this, TelegramCredentials(BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH), protection, { driveLazy.isInitialized() && !driveLazy.value.policy.settings().dataSaver })
    }
    val drive: DriveRepository get() = driveLazy.value
}
