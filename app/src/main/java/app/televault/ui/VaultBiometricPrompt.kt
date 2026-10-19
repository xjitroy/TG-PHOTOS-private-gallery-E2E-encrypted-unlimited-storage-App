package app.televault.ui

import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.televault.data.VaultBiometricKey
import java.util.concurrent.Executor
import javax.crypto.Cipher

/** Only a successful hardware-authorized CryptoObject is accepted. Password remains the fallback. */
@Suppress("DEPRECATION")
@Composable
internal fun VaultBiometricPrompt(request: VaultBiometricKey.Request, verified: (Cipher) -> Unit, cancelled: () -> Unit) {
    val context = LocalContext.current
    val onVerified by rememberUpdatedState(verified)
    val onCancelled by rememberUpdatedState(cancelled)
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(request, owner) {
        val signal = CancellationSignal()
        val gate = BiometricCallbackGate()
        val handler = Handler(Looper.getMainLooper())
        fun cancel() {
            if (gate.terminal()) onCancelled()
        }
        fun success(cipher: Cipher?) {
            if (cipher != null && cipher === request.cipher) {
                if (gate.terminal()) onVerified(cipher)
            } else {
                cancel()
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                cancel()
                signal.cancel()
            }
        }
        owner.lifecycle.addObserver(observer)
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val executor = Executor { handler.post(it) }
                val builder = BiometricPrompt.Builder(context).setTitle("Private Vault")
                    .setSubtitle(if (request.enrolling) "Enable biometric access on this phone" else "Unlock your Vault")
                    .setNegativeButton("Use Vault password", executor) { _, _ -> cancel() }
                if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                builder.build().authenticate(
                    BiometricPrompt.CryptoObject(request.cipher),
                    signal,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            success(result.cryptoObject?.cipher)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            cancel()
                        }
                    }
                )
            } else {
                val manager = context.getSystemService(FingerprintManager::class.java)
                check(manager != null && manager.isHardwareDetected && manager.hasEnrolledFingerprints())
                manager.authenticate(
                    FingerprintManager.CryptoObject(request.cipher),
                    signal,
                    0,
                    object : FingerprintManager.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) {
                            success(result.cryptoObject?.cipher)
                        }
                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            cancel()
                        }
                    },
                    handler
                )
            }
        } catch (_: Exception) {
            handler.post { cancel() }
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            gate.dispose()
            signal.cancel()
            request.close()
        }
    }
}
