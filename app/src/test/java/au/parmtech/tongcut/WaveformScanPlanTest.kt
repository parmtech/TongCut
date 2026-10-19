package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class WaveformScanPlanTest {
    @Test fun shortClipsDecodeEntireSource() {
        val windows = WaveformScanPlan.windows(5_000_000L, 50_000_000L)
        assertEquals(1, windows.size)
        assertEquals(5_000_000L, windows.single().startUs)
        assertEquals(55_000_000L, windows.single().endUs)
    }

    @Test fun ninetySecondBoundaryStillGetsFullWaveform() {
        val windows = WaveformScanPlan.windows(0L, 90_000_000L)
        assertEquals(1, windows.size)
        assertEquals(90_000_000L, windows.single().durationUs)
    }

    @Test fun longAudioScansAllEightyRegionsInsteadOfOnlyTheOpening() {
        val start = 20_000_000L
        val duration = 20L * 60L * 1_000_000L
        val windows = WaveformScanPlan.windows(start, duration)
        assertEquals(80, windows.size)
        assertTrue(windows.first().startUs > start)
        assertTrue(windows.last().endUs > start + duration * 95 / 100)
        assertTrue(windows.zipWithNext().all { (a,b) -> a.endUs <= b.startUs })
        assertTrue(windows.all {
            it.durationUs <= 750_000L &&
                it.startUs >= start && it.endUs <= start + duration
        })
        assertTrue(windows.sumOf { it.durationUs } <= 60_000_000L)
    }

    @Test fun trimmedSourceOffsetsAreHonoured() {
        val windows = WaveformScanPlan.windows(35_000_000L, 8_000_000L)
        assertEquals(35_000_000L, windows.first().startUs)
        assertEquals(43_000_000L, windows.last().endUs)
    }

    @Test(expected=IllegalArgumentException::class)
    fun startPlusDurationOverflowIsRejected() {
        WaveformScanPlan.windows(Long.MAX_VALUE-100L,1_000L)
    }

    @Test(expected=IllegalArgumentException::class)
    fun emptyClipIsRejected() {
        WaveformScanPlan.windows(1_000L,0L)
    }
}
