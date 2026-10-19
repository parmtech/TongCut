package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class WaveformPeaksTest {
    @Test fun waveformDistributesFramesByAbsoluteTimestamp() {
        val peaks = WaveformPeaks(4, 1_000_000, 4_000_000)
        peaks.addAt(500_000, 1f) // ignored
        peaks.addAt(1_000_000, 0.1f)
        peaks.addAt(1_250_000, 0.75f)
        peaks.addAt(2_500_000, 0.5f)
        peaks.addAt(4_250_000, 0.25f)
        peaks.addAt(5_000_000, 0.9f) // ignored, exclusive
        assertEquals(listOf(0.75f,0.5f,0f,0.25f), peaks.snapshot())
    }

    @Test fun clampsAmplitudeWithoutNormalizingQuietAudioToLoud() {
        val peaks = WaveformPeaks(4,0,4_000)
        peaks.addAt(1,2.0f)
        peaks.addAt(2,0.5f)
        assertEquals(1f,peaks.snapshot()[0])
        assertEquals(0f,peaks.snapshot()[1])
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsNonFiniteSamples() {
        WaveformPeaks(8,0,100_000).addAt(10,Float.NaN)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsTooManyBins() {
        WaveformPeaks(4096,0,10_000)
    }
}
