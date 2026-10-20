package au.parmtech.tongcut.reverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VfrPresentationIndexTest {
    @Test
    fun sortsDemuxOrderByActualPtsAndKeepsSampleMappingForReverseIteration() {
        val builder = VfrPresentationIndexBuilder()
        // Deliberately non-monotonic extractor order; PTS order is the presentation order.
        builder.add(300_000L)
        builder.add(100_000L)
        builder.add(200_000L)
        builder.add(400_000L)

        val index = builder.build(inUs = 100_000L, outUs = 400_000L)!!
        assertFalse(index.extractorOrderWasStrictlyIncreasing)
        assertEquals(3, index.frameCount)
        assertEquals(
            listOf(
                VfrPresentationSample(extractorSampleOrdinal = 0, presentationTimeUs = 300_000L),
                VfrPresentationSample(extractorSampleOrdinal = 2, presentationTimeUs = 200_000L),
                VfrPresentationSample(extractorSampleOrdinal = 1, presentationTimeUs = 100_000L)
            ),
            index.reverseOrder().toList()
        )
        assertEquals(300_000L, index.sourceTimeUs(0))
        assertEquals(100_000L, index.sourceTimeUs(2))
    }

    @Test
    fun strictlyIncreasingInputIsReportedAndTrimUsesHalfOpenBounds() {
        val builder = VfrPresentationIndexBuilder()
        builder.add(0L)
        builder.add(40_000L)
        builder.add(80_000L)
        builder.add(120_000L)
        val index = builder.build(inUs = 40_000L, outUs = 120_000L)!!

        assertTrue(index.extractorOrderWasStrictlyIncreasing)
        assertEquals(2, index.frameCount)
        assertEquals(80_000L, index.chronologicalAt(1).presentationTimeUs)
        assertEquals(40_000L, index.reverseAt(1).presentationTimeUs)
    }

    @Test
    fun duplicatePtsAreRejectedEvenWhenNotAdjacentInExtractorOrder() {
        val builder = VfrPresentationIndexBuilder()
        builder.add(100L)
        builder.add(200L)
        builder.add(100L)
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(inUs = 0L, outUs = 300L)
        }
    }

    @Test
    fun rejectsNegativePtsAndExceedingSampleBound() {
        assertThrows(IllegalArgumentException::class.java) {
            VfrPresentationIndexBuilder().add(-1L)
        }
        val bounded = VfrPresentationIndexBuilder(maxSamples = 2)
        bounded.add(1L)
        bounded.add(2L)
        assertThrows(IllegalArgumentException::class.java) {
            bounded.add(3L)
        }
    }

    @Test
    fun emptyTrimAndNoFrameTrimReturnNull() {
        assertNull(VfrPresentationIndexBuilder().build(inUs = 0L, outUs = 0L))
        val builder = VfrPresentationIndexBuilder()
        builder.add(10L)
        builder.add(20L)
        assertNull(builder.build(inUs = 11L, outUs = 19L))
    }
}
