package app.televault.data

internal enum class VaultImportStage { SOURCE, CLOUD_ROOT, ENCRYPTION, UPLOAD, SERVER_VERIFICATION, COMMIT }

/** Safe stage identifier for UI diagnostics; never show provider paths, server text or keys. */
internal class PrivateVaultImportFailure(val stage: VaultImportStage, cause: Exception) : Exception("Private Vault import interrupted at ${stage.name}", cause)
