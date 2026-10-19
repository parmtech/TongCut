package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class AudioPreviewFailuresTest {
    private fun audio(uri: String = "content://audio/bad") =
        TimelineLayer(
            "a", "audio", LayerKind.AUDIO, 0L, 2_000_000L,
            sourceUri = uri, sourceDurationUs = 3_000_000L
        )

    @Test fun brokenDecoderIsBlockedUntilSourceIsRelinked() {
        val failures = AudioPreviewFailures()
        val old = audio()
        assertNull(failures.messageFor(old))
        val reason = failures.record(old, "Not supported")
        assertTrue(reason.contains("Relink"))
        assertEquals(reason, failures.messageFor(old))
        failures.retain(listOf(old))
        assertEquals(reason, failures.messageFor(old))
        val replacement = old.copy(sourceUri = "content://audio/new")
        failures.retain(listOf(replacement))
        assertNull(failures.messageFor(replacement))
    }

    @Test fun sourceTrimChangeAlsoAllowsRetryAndStaleRecordsAreCleared() {
        val failures = AudioPreviewFailures()
        val old = audio()
        failures.record(old, "Unreadable")
        assertNull(failures.messageFor(old.copy(sourceInUs = 250_000L)))
        failures.retain(emptyList())
        assertNull(failures.messageFor(old))
    }

    @Test fun invalidMessageIsBoundedAndClearable() {
        val failures = AudioPreviewFailures()
        val old = audio()
        failures.record(old, "X".repeat(2000))
        val msg = requireNotNull(failures.messageFor(old))
        assertTrue(msg.length <= 245)
        failures.clear()
        assertNull(failures.messageFor(old))
    }
}
