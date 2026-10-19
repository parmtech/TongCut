package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

/** Schema-7 optional spill field must preserve prior users' exported appearance. */
class ChromaSpillPersistenceTest {
    private fun graph(spill: Float = 0.75f): LayerTimeline =
        LayerTimeline(
            tracks = listOf(TimelineTrack("pip", "Video layer", LayerKind.VIDEO, 0)),
            layers = listOf(
                TimelineLayer(
                    "v", "pip", LayerKind.VIDEO, 0L, 1_500_000L,
                    sourceUri = "content://sample/green", sourceDurationUs = 3_000_000L,
                    photoEffects = PhotoEffects(chroma = PhotoChroma(
                        keyArgb = 0xFF00FF00.toInt(),
                        tolerance = 0.19f,
                        feather = 0.08f,
                        spillSuppression = spill
                    ))
                )
            )
        )

    @Test fun savedEffectRoundTripsWithoutModifyingOtherSourceEdits() {
        val original = graph()
        val decoded = LayerTimelineCodec.decode(LayerTimelineCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(0.75f,
            requireNotNull(decoded.layers.single().photoEffects.chroma).spillSuppression, 0f)
    }

    @Test fun loadingOldChromaJSONDefaultsToZeroSpill() {
        val old = LayerTimelineCodec.encode(graph())
        val settings = old.getJSONArray("layers").getJSONObject(0)
            .getJSONObject("photoFx").getJSONObject("chroma")
        settings.remove("spill")
        val decoded = LayerTimelineCodec.decode(old)
        assertEquals(0f,
            requireNotNull(decoded.layers.single().photoEffects.chroma).spillSuppression, 0f)
        assertEquals(0.19f,
            requireNotNull(decoded.layers.single().photoEffects.chroma).tolerance, 0f)
    }

    @Test fun despillSettingsStayWithinSafeBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoChroma(spillSuppression = -0.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoChroma(spillSuppression = 1.1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoChroma(spillSuppression = Float.POSITIVE_INFINITY)
        }
    }
}
