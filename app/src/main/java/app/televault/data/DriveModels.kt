package app.televault.data

import app.televault.telegram.RemoteDocument

enum class VaultStage { DISCOVERING, CREATE, RECOVER, LOCKED, READY, ERROR }
data class DriveFile(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val date: Long,
    val album: String,
    val hash: String,
    val data: RemoteDocument,
    val thumbnail: String,
    val sourceToken: String,
    val parts: List<RemoteDocument> = emptyList(),
    val sourceUri: String = "",
    val favorite: Boolean = false,
    val trashedAt: Long = 0,
    val albumIds: Set<String> = emptySet(),
    val facts: MediaFacts = MediaFacts(),
    val trashOrigin: String = "",
    val sourceAddedAt: Long = 0,
    val sourceModifiedAt: Long = 0,
    val backedUpAt: Long = 0
) {
    val mediaCategories: Int = app.televault.domain.MediaCategories.classify(name, album, mime, facts.cameraMake.isNotBlank() || facts.cameraModel.isNotBlank(), facts.durationMs)
}
data class DriveTransfer(val id: String, val name: String, val phase: String, val progress: Long, val total: Long, val download: Boolean, val running: Boolean = false, val batchId: String = "", val batchName: String = "", val batchCount: Int = 0, val batchBytes: Long = -1, val fileBytes: Long = -1)
data class BackupFolder(val id: String, val name: String, val wifiOnly: Boolean, val deviceFolder: String? = null, val uri: String = "")
data class CloudAlbum(val id: String, val name: String, val folder: Boolean = false)
data class OfflineDownloads(val ids: Set<String> = emptySet(), val bytes: Long = 0, val quota: Long = 512L * 1024 * 1024, val activeId: String? = null, val progress: Long = 0, val total: Long = 0)
data class WorkProgress(val name: String, val completed: Long, val total: Long)
data class DeviceCleanupRequest(val id: String, val uri: String, val fingerprint: String, val origin: String)
data class BackedDeviceCopy(val local: LocalMedia, val cloudId: String)
data class DriveSnapshot(
    val vaultId: String = "",
    val indexLoaded: Boolean = false,
    val indexLoading: Boolean = false,
    val indexFailed: Boolean = false,
    val indexNeedsRepair: Boolean = false,
    val quickUnlockAvailable: Boolean = false,
    val dualRecoveryAvailable: Boolean = false,
    val patternRecoveryAvailable: Boolean = false,
    val stage: VaultStage = VaultStage.DISCOVERING,
    val files: List<DriveFile> = emptyList(),
    val transfers: List<DriveTransfer> = emptyList(),
    val backups: List<BackupFolder> = emptyList(),
    val albums: List<CloudAlbum> = emptyList(),
    val backedSources: Set<String> = emptySet(),
    val backedDeviceFiles: Map<String, String> = emptyMap(),
    val deviceCleanupRequests: List<DeviceCleanupRequest> = emptyList(),
    val deviceCopyProgress: WorkProgress? = null,
    val backupVerification: WorkProgress? = null,
    val deviceTrashDates: Map<String, Long> = emptyMap(),
    val deviceTrashOrigins: Map<String, String> = emptyMap(),
    val privateProfile: PrivateProfile? = null,
    val localFavorites: Set<String> = emptySet(),
    val uploadSettings: UploadSettings = UploadSettings(),
    val busy: Boolean = false,
    val syncProgress: WorkProgress? = null,
    val notice: String = ""
)
