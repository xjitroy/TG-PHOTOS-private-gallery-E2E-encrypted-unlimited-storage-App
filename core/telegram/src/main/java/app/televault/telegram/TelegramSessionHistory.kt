package app.televault.telegram

import android.content.Context
import app.televault.domain.AuthProtocol
import java.io.File

/** Durable non-secret hints only. TDLib's encrypted database remains the authority. */
internal class TelegramSessionHistory(private val context: Context) {
    private val preferences = context.getSharedPreferences("telegram-session-history", Context.MODE_PRIVATE)
    val previouslyAuthorized: Boolean get() = if (preferences.contains("authorized")) preferences.getBoolean("authorized", false) else context.noBackupFilesDir.listFiles().orEmpty().any { it.isFile && it.name.matches(Regex("drive-root-[0-9]+\\.json(?:\\.bak)?")) }
    fun observe(stage: AuthProtocol.State) {
        val edit = preferences.edit().putString("stage", stage.name)
        if (stage == AuthProtocol.State.READY) edit.putBoolean("authorized", true)
        if (stage == AuthProtocol.State.LOGGING_OUT) edit.putBoolean("authorized", false)
        // Synchronous persistence: killing the process must not discard a completed login hint.
        edit.commit()
    }
    fun summary(): String {
        val wrapped = File(context.noBackupFilesDir, "tdlib-db-key.wrap")
        val database = File(context.noBackupFilesDir, "tdlib/database")
        val hasKey = wrapped.exists() || File(wrapped.path + ".bak").exists()
        val hasDatabase = database.listFiles()?.isNotEmpty() == true
        return "Telegram ${preferences.getString("stage", "STARTING")} · saved session=$previouslyAuthorized · wrapped key=$hasKey · database=$hasDatabase"
    }
}
