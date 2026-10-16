package app.televault.data

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.televault.domain.AudioCut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*

/** Decode a chosen range into a new explicit SAF WAV copy. No plaintext staging file. */
internal object AudioTrim {
    suspend fun copy(context: Context, source: Uri, destination: Uri, startMs: Long, endMs: Long, gain: Float = 1f, fadeInMs: Long = 0, fadeOutMs: Long = 0, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        require(source != destination && startMs >= 0 && endMs > startMs)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var committed = false
        var safeDestination = false
        try {
            extractor.setDataSource(context, source, null)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("No audio track")
            val format = extractor.getTrackFormat(index)
            val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sampleRate in 8000..192000 && channels in 1..8)
            val startUs = Math.multiplyExact(startMs, 1000L)
            val endUs = Math.multiplyExact(endMs, 1000L)
            extractor.selectTrack(index)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val descriptor = checkNotNull(context.contentResolver.openFileDescriptor(destination, "rw"))
            // WAV header needs a seekable output. Refuse pipes before writing rather than corrupt output.
            try {
                checkNotNull(context.contentResolver.openFileDescriptor(source, "r")).use { original ->
                    val before = Os.fstat(original.fileDescriptor)
                    val copy = Os.fstat(descriptor.fileDescriptor)
                    require(before.st_ino != copy.st_ino || before.st_dev != copy.st_dev) { "Destination must be a different file" }
                }
                safeDestination = true
                Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
            } catch (e: Exception) {
                descriptor.close()
                throw e
            }
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                output.channel.truncate(0)
                output.write(wavHeader(0, sampleRate, channels))
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var bytesWritten = 0L
                var idleAt = android.os.SystemClock.elapsedRealtime()
                while (!outputEnded) {
                    ensureActive()
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val buffer = checkNotNull(decoder.getInputBuffer(inputIndex))
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (size < 0 || time >= endUs + 1000000) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, time, 0)
                                extractor.advance()
                            }
                            idleAt = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                    val outputIndex = decoder.dequeueOutputBuffer(info, 10000)
                    if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val actual = decoder.outputFormat
                        require(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == sampleRate && actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == channels)
                        require(!actual.containsKey(MediaFormat.KEY_PCM_ENCODING) || actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "Unsupported PCM format" }
                    } else if (outputIndex >= 0) {
                        try {
                            val buffer = checkNotNull(decoder.getOutputBuffer(outputIndex))
                            val frameBytes = channels * 2
                            val frames = info.size / frameBytes
                            val first = AudioCut.frame(startUs, info.presentationTimeUs, sampleRate, frames)
                            val until = AudioCut.frame(endUs, info.presentationTimeUs, sampleRate, frames)
                            if (until > first && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val count = (until - first) * frameBytes
                                require(bytesWritten + count <= 0xffffffffL - 36) { "WAV exceeds4 GB" }
                                val data = ByteArray(count)
                                try {
                                    buffer.position(info.offset + first * frameBytes)
                                    buffer.get(data)
                                    app.televault.domain.PcmGain.apply(data, channels, sampleRate, info.presentationTimeUs + first.toLong() * 1_000_000 / sampleRate, startUs, endUs, gain, fadeInMs * 1000, fadeOutMs * 1000)
                                    output.write(data)
                                    bytesWritten += count
                                } finally {
                                    data.fill(0)
                                }
                                progress(((info.presentationTimeUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f))
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= endUs
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                        idleAt = android.os.SystemClock.elapsedRealtime()
                    }
                    check(android.os.SystemClock.elapsedRealtime() - idleAt < 30000) { "Audio decoder stalled" }
                }
                check(bytesWritten > 0) { "Empty audio range" }
                output.channel.position(0)
                output.write(wavHeader(bytesWritten, sampleRate, channels))
                output.fd.sync()
                committed = true
                progress(1f)
            }
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
            if (!committed && safeDestination) runCatching { androidx.documentfile.provider.DocumentFile.fromSingleUri(context, destination)?.delete() }
        }
    }
    private fun wavHeader(size: Long, rate: Int, channels: Int): ByteArray = AudioCut.header(size, rate, channels)
}
