package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEnvelopeTest {
    private fun draft() = LayerEdits.addAudio(
        EditorDraft(clips = listOf(DraftClip("content://main", "Main", durationMs = 10_000L))),
        playheadMs = 1_000L, uri = "content://audio", durationMs = 4_000L
    )

    @Test fun interpolatesAndPersistsClipGainAutomation() {
        val original = draft()
        val id = original.layers.layers.single().id
        val low = LayerEdits.setAudioEnvelopeAt(original, id, 1_000L, 0.2f)
        val shaped = LayerEdits.setAudioEnvelopeAt(low, id, 5_000L, 0.8f)
        val layer = shaped.layers.layers.single()
        assertEquals(0.5f, AudioEnvelope.gainAt(2_000_000L, layer.durationUs, layer.audioEnvelope), 0.001f)
        assertEquals(shaped.layers, LayerTimelineCodec.decode(LayerTimelineCodec.encode(shaped.layers)))
        val part = AudioMixPlan.from(shaped.layers, 10_000_000L).single()
            .parts.filterIsInstance<AudioPart.Source>().single()
        assertEquals(layer.audioEnvelope, part.envelope)
    }

    @Test fun splitKeepsContinuousAutomationOnBothClips() {
        val original = draft()
        val id = original.layers.layers.single().id
        val shaped = LayerEdits.setAudioEnvelopeAt(
            LayerEdits.setAudioEnvelopeAt(original, id, 1_000L, 0f), id, 5_000L, 1f
        )
        val divided = shaped.layers.splitLayer(id, 3_000_000L, "right")
        val left = divided.layers.single { it.id == id }
        val right = divided.layers.single { it.id == "right" }
        assertEquals(0.5f, AudioEnvelope.gainAt(left.durationUs, left.durationUs, left.audioEnvelope), 0.001f)
        assertEquals(0.5f, AudioEnvelope.gainAt(0L, right.durationUs, right.audioEnvelope), 0.001f)
        assertTrue(right.audioEnvelope.all { it.timeUs <= right.durationUs })
    }
}
