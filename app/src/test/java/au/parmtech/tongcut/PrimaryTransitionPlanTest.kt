package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PrimaryTransitionPlanTest {
    private fun clip(id: String, lengthMs: Long, inMs: Long = 0L, outMs: Long = lengthMs) =
        PrimaryTransitionClip(
            id = id,
            sourceDurationUs = lengthMs * 1_000L,
            sourceInUs = inMs * 1_000L,
            sourceOutUs = outMs * 1_000L
        )

    @Test fun singleClipRetainsTrimAndHasNoTransition() {
        val plan = PrimaryTransitionPlan.build(listOf(clip("a", 5_000, 500, 4_500)), emptyList())
        assertEquals(4_000_000L, plan.durationUs)
        assertEquals(500_000L, plan.clips.single().sourceAt(0L))
        assertEquals(4_499_000L, plan.clips.single().sourceAt(3_999_000L))
        assertEquals(listOf("a"), plan.activeAt(1_000_000L).map { it.clip.id })
        assertNull(plan.transitionAt(1_000_000L))
    }

    @Test fun crossDissolveOverlapsAdjacentPrimaryClipsAndMapsBothSourceClocks() {
        val plan = PrimaryTransitionPlan.build(
            listOf(clip("a", 4_000), clip("b", 5_000, 1_000, 4_000)),
            listOf(PrimaryTransitionSpec(afterClipIndex = 0, durationUs = 500_000L))
        )
        assertEquals(7_500_000L, plan.durationUs)
        assertEquals(3_500_000L, plan.clips[1].timelineStartUs)
        val at = 3_750_000L
        assertEquals(listOf("a", "b"), plan.activeAt(at).map { it.clip.id })
        assertEquals(3_750_000L, plan.clips[0].sourceAt(at))
        assertEquals(1_250_000L, plan.clips[1].sourceAt(at))
        val transition = requireNotNull(plan.transitionAt(at))
        assertEquals("a", transition.outgoingClipId)
        assertEquals("b", transition.incomingClipId)
        val (outgoing, incoming) = transition.alphaAt(at)
        assertEquals(0.5f, outgoing, 0.001f)
        assertEquals(0.5f, incoming, 0.001f)
        assertEquals(1f, outgoing + incoming, 0.001f)
    }

    @Test fun twoTransitionsKeepDistinctWindowsAndLeaveMiddleClipContent() {
        val plan = PrimaryTransitionPlan.build(
            listOf(clip("a", 3_000), clip("b", 4_000), clip("c", 3_000)),
            listOf(
                PrimaryTransitionSpec(0, 500_000L),
                PrimaryTransitionSpec(1, 700_000L)
            )
        )
        assertEquals(8_800_000L, plan.durationUs)
        assertEquals(2, plan.transitions.size)
        assertNull(plan.transitionAt(4_000_000L))
        assertEquals(listOf("b"), plan.activeAt(4_000_000L).map { it.clip.id })
    }

    @Test fun audioUsesEqualPowerGainsAtTransitionMidpoint() {
        val plan = PrimaryTransitionPlan.build(
            listOf(clip("a", 3_000), clip("b", 3_000)),
            listOf(PrimaryTransitionSpec(0, 1_000_000L))
        )
        val (outgoing, incoming) = plan.transitions.single()
            .audioGainsAt(2_500_000L)
        assertEquals(0.7071f, outgoing, 0.001f)
        assertEquals(0.7071f, incoming, 0.001f)
    }

    @Test fun rejectsInvalidCutDuplicateIdsAndOverlappingTransitionsThatConsumeClip() {
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryTransitionPlan.build(listOf(clip("a", 1_000)), listOf(PrimaryTransitionSpec(0, 500_000L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryTransitionPlan.build(
                listOf(clip("a", 2_000), clip("a", 2_000)),
                emptyList()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryTransitionPlan.build(
                listOf(clip("a", 2_000), clip("b", 2_000), clip("c", 2_000)),
                listOf(
                    PrimaryTransitionSpec(0, 1_000_000L),
                    PrimaryTransitionSpec(1, 1_000_000L)
                )
            )
        }
    }

    @Test fun transitionIntervalsAreHalfOpen() {
        val plan = PrimaryTransitionPlan.build(
            listOf(clip("a", 2_000), clip("b", 2_000)),
            listOf(PrimaryTransitionSpec(0, 500_000L))
        )
        val transition = plan.transitions.single()
        assertEquals(0f, transition.progressAt(transition.startUs), 0f)
        assertEquals(0.5f, transition.progressAt(transition.startUs + 250_000L), 0.001f)
        assertEquals(listOf("b"), plan.activeAt(transition.endUs).map { it.clip.id })
        assertNull(plan.transitionAt(transition.endUs))
    }
}
