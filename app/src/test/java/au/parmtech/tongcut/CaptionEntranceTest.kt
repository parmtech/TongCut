package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class CaptionEntranceTest {
    @Test fun noEntranceKeepsTheAuthoredPoseAtEveryTime() {
        assertEquals(CaptionEntrancePose(), CaptionEntranceMotion.at(
            CaptionEntrance.NONE, 0L, 1_000_000L
        ))
        assertEquals(CaptionEntrancePose(), CaptionEntranceMotion.at(
            CaptionEntrance.NONE, 800_000L, 1_000_000L
        ))
    }

    @Test fun presetsBeginAtTheirDefinedEntryAndSettleByThreeTenthsOfASecond() {
        assertEquals(0f, CaptionEntranceMotion.at(
            CaptionEntrance.FADE, 0L, 1_000_000L
        ).alpha, 0f)
        assertEquals(0.78f, CaptionEntranceMotion.at(
            CaptionEntrance.POP, 0L, 1_000_000L
        ).scale, 0f)
        assertEquals(-0.16f, CaptionEntranceMotion.at(
            CaptionEntrance.RISE, 0L, 1_000_000L
        ).y, 0f)
        for (preset in CaptionEntrance.entries.filter { it != CaptionEntrance.NONE }) {
            assertEquals(CaptionEntrancePose(), CaptionEntranceMotion.at(
                preset, 300_000L, 1_000_000L
            ))
        }
    }

    @Test fun newOfflineEntrancesHaveDistinctStartingAndMidpointPoses() {
        val zoomStart = CaptionEntranceMotion.at(
            CaptionEntrance.ZOOM, 0L, 1_000_000L
        )
        val dropStart = CaptionEntranceMotion.at(
            CaptionEntrance.DROP, 0L, 1_000_000L
        )
        val bounceStart = CaptionEntranceMotion.at(
            CaptionEntrance.BOUNCE, 0L, 1_000_000L
        )
        assertEquals(0.55f, zoomStart.scale, 0f)
        assertEquals(0.18f, dropStart.y, 0f)
        assertEquals(0.78f, bounceStart.scale, 0.001f)
        assertTrue(listOf(zoomStart, dropStart, bounceStart).distinct().size == 3)

        for (preset in listOf(CaptionEntrance.ZOOM, CaptionEntrance.DROP, CaptionEntrance.BOUNCE)) {
            assertEquals(CaptionEntrancePose(), CaptionEntranceMotion.at(
                preset, CaptionEntranceMotion.DURATION_US, 1_000_000L
            ))
        }
    }

    @Test fun shortCaptionsStillFinishEntranceAtTheirEnd() {
        assertEquals(CaptionEntrancePose(), CaptionEntranceMotion.at(
            CaptionEntrance.POP, 120_000L, 120_000L
        ))
    }
}
