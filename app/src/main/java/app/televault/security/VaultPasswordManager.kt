package app.televault.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.credentials.*
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Android password provider consent; no app server, OTP shortcut or silent secret storage. */
object VaultPasswordManager {
    private fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> null
    }
    fun offerSave(context: Context, account: String, phrase: CharArray, notice: (String) -> Unit) = offerSaveMultiple(context, listOf(account to phrase), notice)
    fun offerSaveMultiple(context: Context, entries: List<Pair<String, CharArray>>, notice: (String) -> Unit) {
        try {
            val host = activity(context)
            val lifecycle = host as? LifecycleOwner
            if (host == null || lifecycle == null) {
                notice("Password Manager is unavailable here. Keep your own recovery copy.")
                return
            }
            val requests = entries.map { (account, phrase) -> CreatePasswordRequest(account, phrase.concatToString()) }
            lifecycle.lifecycleScope.launch {
                for (request in requests) {
                    try {
                        CredentialManager.create(host).createCredential(host, request)
                        notice("Recovery credential saved by your password provider.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: CreateCredentialCancellationException) {
                        notice("Credential saving cancelled. Vault credentials are unchanged; keep an offline copy.")
                        break
                    } catch (_: CreateCredentialException) {
                        notice("Password Manager could not save it. Check your phone's password provider; keep an offline copy.")
                        break
                    }
                }
            }
        } finally {
            entries.forEach { it.second.fill('\u0000') }
        }
    }
    suspend fun fill(context: Context, account: String): String? {
        try {
            val result = CredentialManager.create(context).getCredential(context, GetCredentialRequest(listOf(GetPasswordOption(allowedUserIds = setOf(account, "$account · App pattern")))))
            val password = result.credential as? PasswordCredential ?: return null
            return password.password.takeIf { password.id in setOf(account, "$account · App pattern") }
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            return null
        } catch (_: GetCredentialException) {
            return null
        }
    }
}
