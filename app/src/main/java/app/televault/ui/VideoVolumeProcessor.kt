package app.televault.ui

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import app.televault.domain.VideoVolume
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Bounded decoder PCM processing;100percent is an exact copy. No original file is edited. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoVolumeProcessor : BaseAudioProcessor() {
    @Volatile var boostPercent: Int = 100
        set(value) {
            field = value.coerceIn(100, 200)
        }
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
        else -> AudioFormat.NOT_SET
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val gain = VideoVolume.gain(boostPercent)
        if (gain == 1f) {
            output.put(inputBuffer)
        } else {
            inputBuffer.order(ByteOrder.nativeOrder())
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                while (inputBuffer.remaining() >= 4) output.putFloat(VideoVolume.amplify(inputBuffer.float, gain))
            } else {
                while (inputBuffer.remaining() >= 2) {
                    val value = VideoVolume.amplify(inputBuffer.short / 32768f, gain)
                    output.putShort((value * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
                }
            }
            // Decoder PCM is frame-aligned; retain any unexpected tail instead of overrunning.
            while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        }
        output.flip()
    }
}
