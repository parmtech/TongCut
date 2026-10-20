package au.parmtech.tongcut

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioEqualizerTest {
    @Test fun neutralProcessorPreservesPcm16SamplesExactly() {
        val processor = AudioEqualizerProcessor(AudioEqualizer())
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            .putShort(Short.MIN_VALUE).putShort((-1).toShort()).putShort(0).putShort(32767)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(Short.MIN_VALUE, output.getShort())
        assertEquals((-1).toShort(), output.getShort())
        assertEquals(0.toShort(), output.getShort())
        assertEquals(32767.toShort(), output.getShort())
    }

    @Test fun boostedNearPeakToneReceivesHeadroomInsteadOfHardClipping() {
        val processor = AudioEqualizerProcessor(AudioEqualizer(bassDb = 12f))
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(4_800 * 2).order(ByteOrder.nativeOrder())
        repeat(4_800) { frame ->
            val sample = (0.9 * sin(2.0 * PI * 100.0 * frame / 48_000.0) * 32767.0)
                .toInt().toShort()
            input.putShort(sample)
        }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var peak = 0
        while (output.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(output.getShort().toInt()))
        assertTrue("boosted output should remain below the hard-clip rail", peak in 20_000..32_000)
    }

    @Test fun threeBandBoostDoesNotCancelASeparatedBandWithExcessiveGlobalAttenuation() {
        val processor = AudioEqualizerProcessor(
            AudioEqualizer(bassDb = 12f, midDb = 12f, trebleDb = 12f))
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(4_800 * 2).order(ByteOrder.nativeOrder())
        repeat(4_800) { frame ->
            val sample = (0.5 * sin(2.0 * PI * 1_000.0 * frame / 48_000.0) * 32767.0)
                .toInt().toShort()
            input.putShort(sample)
        }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var peak = 0
        while (output.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(output.getShort().toInt()))
        assertTrue("the mid-band boost should remain audible with other bands boosted", peak > 12_000)
        assertTrue("the limiter should still keep output within the PCM16 rail", peak <= 32_000)
    }

    @Test fun stereoBalanceChangesOnlyTheOppositeStereoSide() {
        val processor = AudioEqualizerProcessor(AudioEqualizer(), stereoBalance = -1f)
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
            .putShort(10_000).putShort(10_000).putShort(-12_000).putShort(-12_000)
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        assertEquals(10_000.toShort(), output.getShort())
        assertEquals(0.toShort(), output.getShort())
        assertEquals((-12_000).toShort(), output.getShort())
        assertEquals(0.toShort(), output.getShort())
    }

    @Test fun monoInputIgnoresStereoBalance() {
        val processor = AudioEqualizerProcessor(AudioEqualizer(), stereoBalance = 1f)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).putShort(12_345)
        input.flip()
        processor.queueInput(input)
        assertEquals(12_345.toShort(), processor.output.order(ByteOrder.nativeOrder()).getShort())
    }

    @Test fun rumbleReductionRejectsSteadyDc() {
        val processor = AudioEqualizerProcessor(AudioEqualizer(), rumbleReduction = true)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val input = ByteBuffer.allocateDirect(48_000 * 2).order(ByteOrder.nativeOrder())
        repeat(48_000) { input.putShort(8_000) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var last = 0
        while (output.hasRemaining()) last = output.getShort().toInt()
        assertTrue("steady DC should decay below the input", kotlin.math.abs(last) < 80)
    }

    @Test fun stereoFoldDownAveragesChannelsAndReportsMonoOutputFormat() {
        val processor = AudioEqualizerProcessor(AudioEqualizer(), monoFoldDown = true)
        val outputFormat = processor.configure(
            AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        assertEquals(1, outputFormat.channelCount)
        processor.flush()
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            .putShort(10_000).putShort(20_000)
        input.flip()
        processor.queueInput(input)
        assertEquals(15_000.toShort(), processor.output.order(ByteOrder.nativeOrder()).getShort())
    }
}
