package app.televault.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaMuxer
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.media3.common.*
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.effect.*
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import androidx.media3.transformer.*
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/** Transcodes directly into the explicitly chosen seekable output FD; never creates a plaintext staging file. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object VideoEditExport {
    fun effects(rotation: Int, ratio: Float, width: Int, height: Int, brightness: Float, grayscale: Boolean, gain: Float): Effects {
        require(rotation in 0..3 && ratio >= 0 && brightness in -1f..1f && gain in 0f..2f)
        val video = mutableListOf<Effect>()
        if (rotation != 0) video += ScaleAndRotateTransformation.Builder().setRotationDegrees(rotation * 90f).build()
        if (ratio > 0) {
            require(width > 0 && height > 0)
            val sourceRatio = if (rotation % 2 == 0) width.toFloat() / height else height.toFloat() / width
            val horizontal = minOf(1f, ratio / sourceRatio)
            val vertical = minOf(1f, sourceRatio / ratio)
            video += Crop(-horizontal, horizontal, -vertical, vertical)
        }
        if (brightness != 0f) video += Brightness(brightness)
        if (grayscale) video += RgbFilter.createGrayscaleFilter()
        val audio = if (gain == 1f) {
            emptyList()
        } else {
            listOf(
                ChannelMixingAudioProcessor().apply {
                    for (channels in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(gain))
                }
            )
        }
        return Effects(audio, video)
    }
    suspend fun save(context: Context, source: Uri, target: Uri, startMs: Long, endMs: Long, rotation: Int, ratio: Float, width: Int, height: Int, brightness: Float, grayscale: Boolean, gain: Float, mute: Boolean, progress: (Int?) -> Unit) {
        require(startMs >= 0 && endMs > startMs)
        val effect = effects(rotation, ratio, width, height, brightness, grayscale, gain)
        val output = withContext(Dispatchers.IO) { MediaEditCopies.destination(context, source, target) }
        var complete = false
        try {
            withContext(Dispatchers.IO) { Os.ftruncate(output.fileDescriptor, 0) }
            withContext(Dispatchers.Main.immediate) {
                var transformer: Transformer? = null
                var polling: Job? = null
                try {
                    val coroutine = currentCoroutineContext()
                    suspendCancellableCoroutine { continuation ->
                        val listener = object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(exception)
                            }
                        }
                        val factory = object : Muxer.Factory {
                            private var created = false
                            override fun create(path: String): Muxer {
                                check(!created) { "Multiple output muxers are unsupported." }
                                created = true
                                return DestinationMuxer(output)
                            }
                            override fun getSupportedSampleMimeTypes(trackType: Int) = DefaultMuxer.Factory().getSupportedSampleMimeTypes(trackType)
                        }
                        transformer = Transformer.Builder(context.applicationContext).setLooper(Looper.getMainLooper())
                            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setMuxerFactory(factory).addListener(listener).build()
                        val media = MediaItem.Builder().setUri(source).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build()
                        val edited = EditedMediaItem.Builder(media).setRemoveAudio(mute).setEffects(effect).build()
                        continuation.invokeOnCancellation { android.os.Handler(Looper.getMainLooper()).post { transformer?.cancel() } }
                        polling = CoroutineScope(coroutine).launch {
                            val holder = ProgressHolder()
                            while (isActive) {
                                progress(if (transformer!!.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                                delay(300)
                            }
                        }
                        // The factory ignores this label. It is never opened as a path.
                        transformer!!.start(edited, "user-selected-output-fd")
                    }
                } finally {
                    polling?.cancel()
                    transformer?.cancel()
                }
            }
            withContext(Dispatchers.IO) {
                Os.fsync(output.fileDescriptor)
                check(Os.fstat(output.fileDescriptor).st_size > 0) { "Empty video export" }
            }
            complete = true
            progress(100)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                output.close()
                if (!complete) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, target) }
            }
        }
    }
    private class DestinationMuxer(output: ParcelFileDescriptor) : Muxer {
        private val native = MediaMuxer(output.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var started = false
        private var closed = false
        override fun addTrack(format: Format): Int = guarded {
            check(!started && !closed)
            require(format.drmInitData == null)
            if (format.sampleMimeType?.startsWith("video/") == true) native.setOrientationHint(format.rotationDegrees)
            native.addTrack(MediaFormatUtil.createMediaFormatFromFormat(format))
        }
        override fun writeSampleData(trackId: Int, data: ByteBuffer, info: BufferInfo) {
            guarded {
                check(!closed)
                if (!started) {
                    native.start()
                    started = true
                }
                require(info.presentationTimeUs >= 0 && info.size >= 0 && info.size <= data.remaining())
                val nativeInfo = MediaCodec.BufferInfo().apply { set(data.position(), info.size, info.presentationTimeUs, info.flags) }
                native.writeSampleData(trackId, data, nativeInfo)
            }
        }
        override fun addMetadataEntry(metadataEntry: Metadata.Entry) { /* Optional container metadata is intentionally excluded from edited copies. */ }
        override fun close() {
            if (closed) return
            closed = true
            try {
                if (started) guarded { native.stop() }
            } finally {
                native.release()
            }
        }
        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (failure: Exception) {
            throw MuxerException("Selected output cannot be muxed", failure)
        }
    }
}
