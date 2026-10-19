package au.parmtech.tongcut

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LayerTimelineCodecTest {
    private val timeline = LayerTimeline(
        rate = TimelineRate(30_000, 1001),
        tracks = listOf(
            TimelineTrack("video", "V2", LayerKind.VIDEO, 0),
            TimelineTrack("text", "Captions", LayerKind.TEXT, 1, hidden = true)
        ),
        layers = listOf(
            TimelineLayer(
                "v1", "video", LayerKind.VIDEO, 0, 4_000_000,
                sourceUri = "content://footage", sourceInUs = 1_000_000,
                sourceDurationUs = 10_000_000
            ),
            TimelineLayer("t1", "text", LayerKind.TEXT, 1_000_000, 1_500_000, text="Hello, world")
        )
    )

    @Test fun fullLayerRoundtripIncludingNulls() {
        val result = LayerTimelineCodec.decode(LayerTimelineCodec.encode(timeline))
        assertEquals(timeline, result)
        assertEquals(2, result.layers.size)
        assertEquals("Hello, world", result.layers.single { it.kind == LayerKind.TEXT }.text)
        assertFalse(result.activeAt(2_000_000).any { it.kind == LayerKind.TEXT })
    }

    @Test fun emptyLayerTimelineRoundtrip() {
        val result = LayerTimelineCodec.decode(LayerTimelineCodec.encode(LayerTimeline()))
        assertEquals(LayerTimeline(), result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateLayerIds() {
        val doc = LayerTimelineCodec.encode(timeline)
        val arr = doc.getJSONArray("layers")
        arr.put(JSONObject(arr.getJSONObject(0).toString()).put("trackId", "video"))
        LayerTimelineCodec.decode(doc)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingTrack() {
        val obj = LayerTimelineCodec.encode(timeline)
        obj.getJSONArray("layers").getJSONObject(0).put("trackId","missing")
        LayerTimelineCodec.decode(obj)
    }

    @Test fun olderProjectWithoutGainPreservesFullVolume() {
        val encoded = LayerTimelineCodec.encode(timeline)
        val tracks = encoded.getJSONArray("tracks")
        for (index in 0 until tracks.length()) {
            tracks.getJSONObject(index).remove("gain")
        }
        val decoded = LayerTimelineCodec.decode(encoded)
        assertTrue(decoded.tracks.all { it.gain == 1f })
    }

    @Test fun gainSurvivesLayerSaveRoundtrip() {
        val changed = timeline.copy(tracks = timeline.tracks.map { it.copy(gain = 0.35f) })
        assertEquals(changed, LayerTimelineCodec.decode(LayerTimelineCodec.encode(changed)))
    }
    @Test fun importedPhotoAndVideoSurviveRestartCodec() {
        val photo = TimelineTrack("photo", "Photo", LayerKind.IMAGE, 3, hidden = false)
        val video = TimelineTrack("overlay", "Overlay", LayerKind.VIDEO, 4, locked = true)
        val graph = LayerTimeline(
            tracks = listOf(photo,video),
            layers = listOf(
                TimelineLayer("img","photo",LayerKind.IMAGE,1_000_000,5_000_000,
                    sourceUri="content://images/1"),
                TimelineLayer("vid","overlay",LayerKind.VIDEO,3_000_000,3_000_000,
                    sourceUri="content://videos/2",sourceInUs=500_000,sourceDurationUs=10_000_000)
            )
        )
        assertEquals(graph,LayerTimelineCodec.decode(LayerTimelineCodec.encode(graph)))
    }

}
