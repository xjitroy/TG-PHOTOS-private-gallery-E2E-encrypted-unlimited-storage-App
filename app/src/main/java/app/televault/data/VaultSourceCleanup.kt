package app.televault.data

import android.content.Context
import android.net.Uri
import app.televault.TeleVaultApplication
import java.util.UUID

/** Transfers app-owned cloud originals only after the independent Vault copy has been verified. */
internal object VaultSourceCleanup {
    suspend fun deviceOriginals(context: Context, vault: PrivateVaultRepository, verified: List<Pair<Uri, String>>): List<Pair<Uri, String>> {
        val app = context.applicationContext as TeleVaultApplication
        val epoch = vault.currentEpoch()
        val device = mutableListOf<Pair<Uri, String>>()
        for ((source, targetId) in verified) {
            check(vault.valid(epoch))
            val ownCloud = source.scheme == "content" && source.authority == "${context.packageName}.private-media" && source.pathSegments.size == 2 && source.pathSegments.first() == "media"
            if (!ownCloud) {
                device += source to targetId
                continue
            }
            val sourceId = UUID.fromString(checkNotNull(source.lastPathSegment)).toString()
            val target = vault.item(targetId)
            check(target.cloud != null)
            val hash = target.hash.clone()
            try {
                val originals = app.drive.verifiedDeviceCopies(setOf(sourceId))
                app.drive.moveCloudToPrivateVault(sourceId, target.size, hash) { vault.valid(epoch) }
                device += originals.map { it.local.uri to targetId }
            } finally {
                hash.fill(0)
            }
        }
        return device.distinctBy { it.first }
    }
}
