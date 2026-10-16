package app.televault.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import app.televault.crypto.AuthenticatedRecords
import app.televault.telegram.TelegramGateway
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*

/** Media3 receives only authenticated plaintext records, with at most one 1-MB record in RAM. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class EncryptedMediaSource(private val repository: DriveRepository, private val gateway: TelegramGateway) : BaseDataSource(true) {
    private var reader: AuthenticatedRecords? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false
    private var cancellation: Job = SupervisorJob()
    override fun getUri(): Uri? = uri
    override fun open(spec: DataSpec): Long {
        if (!cancellation.isActive) cancellation = SupervisorJob()
        transferInitializing(spec)
        uri = spec.uri
        try {
            val id = checkNotNull(uri?.lastPathSegment)
            val (item, _) = repository.streamingFile(id)
            reader = runBlocking(cancellation + Dispatchers.IO) { repository.authenticatedReader(id, cancellation) }
            require(spec.position <= item.size)
            position = spec.position
            remaining = if (spec.length == C.LENGTH_UNSET.toLong()) item.size - position else minOf(spec.length, item.size - position)
            started = true
            transferStarted(spec)
            return remaining
        } catch (e: Exception) {
            if (AuthenticatedRecords.integrityFailure(e)) repository.invalidateStreamCache(uri?.lastPathSegment)
            reader?.let(repository::closeReader)
            reader = null
            throw IOException("Cannot open encrypted media", e)
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        }
        if (remaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        if (repository.state.value.stage != VaultStage.READY) throw IOException("Vault locked")
        val count = try {
            checkNotNull(reader).read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: IOException) {
            if (AuthenticatedRecords.integrityFailure(e)) repository.invalidateStreamCache(uri?.lastPathSegment)
            throw e
        }
        if (repository.state.value.stage != VaultStage.READY) {
            java.util.Arrays.fill(buffer, offset, offset + count.coerceAtLeast(0), 0.toByte())
            throw IOException("Vault locked")
        }
        if (count > 0) {
            position += count
            remaining -= count
            bytesTransferred(count)
        }
        return count
    }
    override fun close() {
        cancellation.cancel()
        reader?.let(repository::closeReader)
        reader = null
        uri = null
        if (started) {
            started = false
            transferEnded()
        }
    }
}
