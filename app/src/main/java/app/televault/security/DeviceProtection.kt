package app.televault.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Debug
import app.televault.BuildConfig
import app.televault.telegram.TransportProtection
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Evidence-based local safeguards, not an antivirus or root-proof attestation service. */
class DeviceProtection(context: Context) : TransportProtection {
    enum class Reason { NONE, VPN, ROOT, INSTRUMENTATION }
    private val connections = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reasonMutable = MutableStateFlow(Reason.NONE)
    val reason = reasonMutable.asStateFlow()
    private val pauseMutable = MutableStateFlow(false)
    override val paused = pauseMutable.asStateFlow()

    @Volatile private var root = rootEvidence()

    @Volatile private var instrumentation = !BuildConfig.DEBUG && (Debug.isDebuggerConnected() || injectedAgent())

    @Volatile private var blockedAction: (() -> Unit)? = null
    init {
        refresh()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refresh()
            }
            override fun onLost(network: Network) {
                refresh()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                refresh()
            }
        }
        runCatching { connections.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback) }
        scope.launch {
            while (isActive) {
                root = rootEvidence()
                instrumentation = !BuildConfig.DEBUG && (Debug.isDebuggerConnected() || injectedAgent())
                refresh()
                delay(2000)
            }
        }
    }
    fun onBlocked(action: () -> Unit) {
        blockedAction = action
        if (blocked()) action()
    }
    private fun vpn(): Boolean = runCatching { connections.allNetworks.any { connections.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } }.getOrDefault(false)
    private fun refresh(): Boolean {
        val next = when {
            root -> Reason.ROOT
            instrumentation -> Reason.INSTRUMENTATION
            vpn() -> Reason.VPN
            else -> Reason.NONE
        }
        val wasBlocked = pauseMutable.value
        reasonMutable.value = next
        pauseMutable.value = next != Reason.NONE
        if (!wasBlocked && pauseMutable.value) blockedAction?.invoke()
        return pauseMutable.value
    }

    // Readers check this published flag at every record boundary without costly Binder calls.
    // OS capability callbacks and the fallback poll update it and synchronously lock the vault.
    override fun blocked(): Boolean = pauseMutable.value
    private fun rootEvidence(): Boolean = android.os.Process.myUid() == 0 || listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/adb/magisk/magisk").any { path ->
        runCatching { File(path).let { it.isFile && it.canExecute() } }.getOrDefault(false)
    }
    private fun injectedAgent(): Boolean = runCatching {
        File("/proc/self/maps").bufferedReader().useLines { lines ->
            lines.take(8192).any { line ->
                app.televault.domain.RuntimeEvidence.injectedAgent(line)
            }
        }
    }.getOrDefault(false)
}
