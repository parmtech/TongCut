package au.parmtech.tongcut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PresentationTimestampCollectorTest {
    @Test fun sortsDecodeOrderIntoPresentationOrder() {
        val collector = PresentationTimestampCollector()
        collector.add(80_000L)
        collector.add(40_000L)
        collector.add(120_000L)

        assertArrayEquals(longArrayOf(40_000L, 80_000L, 120_000L), collector.toSortedArray())
    }

    @Test fun growsWithinTheConfiguredSampleBound() {
        val collector = PresentationTimestampCollector(maxSamples = 2)
        collector.add(0L)
        collector.add(40_000L)
        assertEquals(2, collector.toSortedArray().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicatePresentationTimestamps() {
        val collector = PresentationTimestampCollector()
        collector.add(40_000L)
        collector.add(40_000L)
        collector.toSortedArray()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativePresentationTimestamps() {
        PresentationTimestampCollector().add(-1L)
    }

    @Test fun rejectsSamplesPastBound() {
        val collector = PresentationTimestampCollector(maxSamples = 1)
        collector.add(0L)
        try {
            collector.add(1L)
            fail("Expected sample bound to reject the next timestamp")
        } catch (_: IllegalArgumentException) {
            // Expected: never silently truncate the index.
        }
    }
}

