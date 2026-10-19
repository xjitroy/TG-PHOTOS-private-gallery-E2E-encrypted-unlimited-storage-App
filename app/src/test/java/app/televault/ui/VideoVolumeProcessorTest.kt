package app.televault.ui

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoVolumeProcessorTest {
    private fun processor(encoding: Int = C.ENCODING_PCM_16BIT) = VideoVolumeProcessor().apply {
        configure(AudioFormat(48000, 2, encoding))
        flush()
    }
    private fun buffer(vararg samples: Short): ByteBuffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
        samples.forEach(::putShort)
        flip()
    }

    @Test fun oneHundredPercentCopiesEveryPcmBitWithoutChangingFrameCount() {
        val processor = processor()
        val input = buffer(-32768, -12000, 0, 1234, 32767, -1)
        val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        processor.queueInput(input)
        val actual = processor.output
        assertEquals(0, input.remaining())
        assertArrayEquals(expected, ByteArray(actual.remaining()).also { actual.get(it) })
    }

    @Test fun gainChangesApplyToTheSameDecoderWithoutRecreatingOrOverflowingIt() {
        val processor = processor()
        processor.boostPercent = 200
        processor.queueInput(buffer(1000, -1000, 32767, -32768))
        val output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(2000, output.short.toInt())
        assertEquals(-2000, output.short.toInt())
        assertTrue(output.short.toInt() in 28000..32767)
        assertTrue(output.short.toInt() in -32768..-28000)
        processor.boostPercent = 100
        processor.queueInput(buffer(1000, -1000))
        val normal = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(1000, normal.short.toInt())
        assertEquals(-1000, normal.short.toInt())
    }

    @Test fun floatPcmIsFiniteAndPreservesTheNumberOfSamples() {
        val processor = processor(C.ENCODING_PCM_FLOAT)
        processor.boostPercent = 200
        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putFloat(.1f).putFloat(-1f).putFloat(Float.NaN).putFloat(0f).apply { flip() }
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(16, output.remaining())
        assertEquals(.2f, output.float, .00001f)
        assertTrue(output.float in -1f..-.85f)
        assertEquals(0f, output.float, 0f)
        assertEquals(0f, output.float, 0f)
    }

    @Test fun unsupportedInputIsBypassedInsteadOfReinterpretedAsSixteenBitAudio() {
        assertFalse(processor(C.ENCODING_PCM_8BIT).isActive)
    }
}
