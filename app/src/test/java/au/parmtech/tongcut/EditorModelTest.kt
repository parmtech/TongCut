package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class EditorModelTest {
    @Test fun trimRejectsInvertedRange() {
        assertFalse(TrimWindow(900, 200, 1_000).isValid())
    }
    @Test fun trimRejectsOutOfBoundsRange() {
        assertFalse(TrimWindow(0, 1_100, 1_000).isValid())
    }
    @Test fun trimAcceptsHundredMilliseconds() {
        assertTrue(TrimWindow(0, 100, 1_000).isValid())
    }
    @Test fun formatsTime() {
        assertEquals("01:05", 65_000L.asTimestamp())
    }
}
