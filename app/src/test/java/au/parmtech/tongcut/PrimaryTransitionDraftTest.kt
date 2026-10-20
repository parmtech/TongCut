package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrimaryTransitionDraftTest {
    private fun clip(id: String, durationMs: Long, reverse: Boolean = false) = DraftClip(
        sourceUri = "content://media/$id",
        label = id,
        durationMs = durationMs,
        id = id,
        reverseVideo = reverse
    )

    @Test fun cutTransitionIsAnImmutableDraftEditAndCompressesTimeline() {
        val before = EditorDraft(clips = listOf(clip("a", 4_000), clip("b", 3_000)))
        val after = before.withPrimaryTransitionAfter(0, 500)

        assertEquals(emptyList<PrimaryTransitionSpec>(), before.primaryTransitions)
        assertEquals(500_000L, after.primaryTransitions.single().durationUs)
        assertEquals(6_500L, SequentialTimeline(after.clips, after.primaryTransitions).totalMs)
        val timeline = SequentialTimeline(after.clips, after.primaryTransitions)
        assertEquals(4_000L, timeline.startOf(1))
        assertEquals(4_000L, timeline.timeOf(1, 0L))
        assertEquals(4_750L, timeline.timeOf(1, 750L))
        assertEquals(5_000L, timeline.sourceTimeOf(1, 1_500L))
        val during = requireNotNull(timeline.pointAt(3_750L))
        assertEquals(0, during.clipIndex)
        assertEquals(3_750L, during.sourceMs)
        assertEquals(emptyList<PrimaryTransitionSpec>(),
            after.withPrimaryTransitionAfter(0, null).primaryTransitions)
    }

    @Test fun relinkPreservesIdentityAndSplitCreatesIdentityForRightHalf() {
        val source = clip("stable", 4_000)
        assertEquals("stable", source.relink("content://replacement", "replacement", 3_000).id)
        val halves = requireNotNull(source.split(2_000))
        assertEquals("stable", halves.first.id)
        assertNotEquals(halves.first.id, halves.second.id)
    }

    @Test fun reverseClipsCannotReceivePrimaryDissolves() {
        val draft = EditorDraft(clips = listOf(clip("a", 3_000), clip("b", 3_000, reverse = true)))
        assertThrows(IllegalArgumentException::class.java) {
            draft.withPrimaryTransitionAfter(0, 500)
        }
    }
}
