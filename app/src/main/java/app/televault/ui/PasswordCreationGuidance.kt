package app.televault.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.televault.crypto.NewPasswordPolicy

/** Only password screens block capture; normal gallery screenshots remain owner-controlled. */
@Composable internal fun PasswordScreenProtection() {
    val context = LocalContext.current
    fun host(c: Context): Activity? = when (c) {
        is Activity -> c
        is ContextWrapper -> host(c.baseContext)
        else -> null
    }
    DisposableEffect(context) {
        val window = host(context)?.window
        val previous = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!previous) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable internal fun PasswordCreationGuidance(value: String, generated: (String) -> Unit) {
    Column {
        Text(label("12–128 characters · uppercase · lowercase · number · symbol. No spaces. Old passwords still unlock for migration.", "১২–১২৮ অক্ষর · বড় হাতের · ছোট হাতের · সংখ্যা · বিশেষ চিহ্ন। স্পেস নয়। পুরোনো পাসওয়ার্ড দিয়ে রিকভারি হবে।"), style = MaterialTheme.typography.bodySmall, color = if (value.isNotEmpty() && !NewPasswordPolicy.accepts(value)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ generated(NewPasswordPolicy.generate()) }) { Text(label("Suggest a secure random password", "নিরাপদ র‍্যান্ডম পাসওয়ার্ড দিন")) }
        Text(label("Use a unique password. Confirm it, save in Password Manager and keep a safe recovery copy. Do not screenshot it.", "আলাদা পাসওয়ার্ড দিন। নিশ্চিত করে Password Manager ও নিরাপদ রিকভারি কপিতে রাখুন। স্ক্রিনশট নেবেন না।"), style = MaterialTheme.typography.bodySmall)
    }
}
