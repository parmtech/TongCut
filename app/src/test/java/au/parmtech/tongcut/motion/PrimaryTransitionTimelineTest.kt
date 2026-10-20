package au.parmtech.tongcut.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryTransitionTimelineTest {
    private fun pair(outgoing: PrimaryClipTiming = outClip(), incoming: PrimaryClipTiming = inClip()) =
        Triple(outgoing, incoming, 9_000L)

    private fun outClip() = PrimaryClipTiming(
        clipId = "clip:out",
        timelineStartMs = 0L,
        sourceDurationMs = 10_000L,
        sourceInMs = 1_000L,
        sourceOutMs = 5_000L
    )

    private fun inClip() = PrimaryClipTiming(
        clipId = "clip->in",
        timelineStartMs = 5_000L,
        sourceDurationMs = 9_000L,
        sourceInMs = 1_000L,
        sourceOutMs = 5_000L
    )

    private fun config(
        outgoingId: String = "clip:out",
        incomingId: String = "clip->in",
        durationMs: Long = 500L,
        curve: PrimaryTransitionCurve = PrimaryTransitionCurve.SMOOTHSTEP
    ) = PrimaryTransitionConfig(
        boundary = PrimaryTransitionBoundary(outgoingId, incomingId),
        durationMs = durationMs,
        curve = curve
    )

    @Test
    fun transitionUsesStableClipBoundaryAndCollisionSafeKey() {
        val a = PrimaryTransitionBoundary("ab", "c")
        val b = PrimaryTransitionBoundary("a", "bc")
        assertNotEquals(a.stableKey, b.stableKey)
        assertEquals(a, PrimaryTransitionBoundary("ab", "c"))
    }

    @Test
    fun handlePlanCentersWindowAndPreservesProjectDuration() {
        val (outgoing, incoming, duration) = pair()
        val plan = PrimaryTransitionPlan.create(outgoing, incoming, config(), duration)

        assertEquals(5_000L, plan.cutTimeMs)
        assertEquals(4_750L, plan.transitionStartMs)
        assertEquals(5_250L, plan.transitionEndMs)
        assertEquals(250L, plan.incomingHeadUsedMs)
        assertEquals(250L, plan.outgoingTailUsedMs)
        assertEquals(4_750L, plan.outgoingSourceStartMs)
        assertEquals(750L, plan.incomingSourceStartMs)
        assertEquals(duration, plan.projectDurationBeforeMs)
        assertEquals(duration, plan.projectDurationAfterMs)
        assertEquals(0L, plan.projectDurationDeltaMs)
    }

    @Test
    fun sampleUsesBothSourceClocksAndSelectedCutAtMixMidpoint() {
        val (outgoing, incoming, duration) = pair()
        val plan = PrimaryTransitionPlan.create(outgoing, incoming, config(), duration)
        val before = plan.sampleAt(plan.transitionStartMs)
        val atCut = plan.sampleAt(plan.cutTimeMs)
        val after = plan.sampleAt(plan.transitionEndMs)

        assertEquals(4_750L, before.outgoingSourceTimeMs)
        assertEquals(750L, before.incomingSourceTimeMs)
        assertEquals(5_000L, atCut.outgoingSourceTimeMs)
        assertEquals(1_000L, atCut.incomingSourceTimeMs)
        assertEquals(5_250L, after.outgoingSourceTimeMs)
        assertEquals(1_250L, after.incomingSourceTimeMs)
        assertEquals(0f, before.incomingMix)
        assertEquals(0.5f, atCut.incomingMix)
        assertEquals(1f, after.incomingMix)
        assertEquals(1f, atCut.outgoingMix + atCut.incomingMix, 0.0001f)
    }

    @Test
    fun linearAndSmoothCurvesStayBoundedAndMonotonic() {
        for (curve in PrimaryTransitionCurve.entries) {
            var previous = -1f
            for (step in 0..100) {
                val value = curve.map(step / 100f)
                assertTrue(value in 0f..1f)
                assertTrue(value >= previous)
                previous = value
            }
            assertEquals(0f, curve.map(-1f))
            assertEquals(1f, curve.map(2f))
        }
        assertEquals(0.5f, PrimaryTransitionCurve.LINEAR.map(0.5f))
        assertEquals(0.5f, PrimaryTransitionCurve.SMOOTHSTEP.map(0.5f))
    }

    @Test
    fun oddDurationAssignsRemainderToOutgoingTail() {
        val plan = PrimaryTransitionPlan.create(
            outClip(), inClip(),
            config(durationMs = 501L),
            projectDurationMs = 9_000L
        )
        assertEquals(250L, plan.incomingHeadUsedMs)
        assertEquals(251L, plan.outgoingTailUsedMs)
        assertEquals(4_750L, plan.transitionStartMs)
        assertEquals(5_251L, plan.transitionEndMs)
    }

    @Test
    fun invalidDurationBoundaryHandlesGapsAndWindowSamplesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { config(durationMs = 99L) }
        assertThrows(IllegalArgumentException::class.java) { config(durationMs = 3_001L) }
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryTransitionBoundary("same", "same")
        }

        val (outgoing, incoming, duration) = pair()
        val plan = PrimaryTransitionPlan.create(outgoing, incoming, config(), duration)
        assertThrows(IllegalArgumentException::class.java) { plan.sampleAt(plan.transitionStartMs - 1L) }
        assertThrows(IllegalArgumentException::class.java) { plan.sampleAt(plan.transitionEndMs + 1L) }
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryTransitionPlan.create(
                outgoing,
                incoming.copy(timelineStartMs = 5_001L),
                config(),
                duration
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryTransitionPlan.create(outgoing, incoming, config(incomingId = "replaced"), duration)
        }
    }

    @Test
    fun insufficientIncomingHeadOrOutgoingTailHandleIsRejected() {
        val outgoing = outClip().copy(sourceOutMs = 9_800L)
        val incoming = inClip().copy(sourceInMs = 200L)
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryTransitionPlan.create(outgoing, inClip(), config(), 9_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryTransitionPlan.create(outClip(), incoming, config(), 9_000L)
        }
    }

    @Test
    fun projectClockOverflowIsRejectedAtClipConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            PrimaryClipTiming(
                clipId = "overflow",
                timelineStartMs = Long.MAX_VALUE - 20L,
                sourceDurationMs = 1_000L,
                sourceInMs = 0L,
                sourceOutMs = 100L
            )
        }
    }
}
