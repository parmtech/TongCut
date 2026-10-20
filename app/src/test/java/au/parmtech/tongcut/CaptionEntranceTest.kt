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

    @Test fun shortCaptionsStillFinishEntranceAtTheirEnd() {
        assertEquals(CaptionEntrancePose(), CaptionEntranceMotion.at(
            CaptionEntrance.POP, 120_000L, 120_000L
        ))
    }
}
