package au.parmtech.tongcut

import org.junit.Assert.*
import org.junit.Test

class AudioDuckingTest {
    private val music = TimelineTrack("music", "Music", LayerKind.AUDIO, 0)
    private val voice = TimelineTrack(
        "voice", "Voiceover", LayerKind.AUDIO, 1, duckingSource = true
    )
    private fun audio(id: String, track: String, atUs: Long, durationUs: Long) =
        TimelineLayer(id, track, LayerKind.AUDIO, atUs, durationUs,
            sourceUri = "content://audio/$id")

    @Test fun dialogueDucksMusicWithShortRampsAroundTheSpeechWindow() {
        val graph = LayerTimeline(
            tracks = listOf(music, voice),
            layers = listOf(audio("music-clip", "music", 0L, 8_000_000L),
                audio("dialogue", "voice", 2_000_000L, 3_000_000L))
        )
        val envelope = AudioMixPlan.from(graph, 8_000_000L)
            .first { it.trackId == "music" }
            .parts.filterIsInstance<AudioPart.Source>().single().envelope

        assertEquals(1f, AudioEnvelope.gainAt(1_700_000L, 8_000_000L, envelope), 0.001f)
        assertEquals(0.625f,
            AudioEnvelope.gainAt(1_940_000L, 8_000_000L, envelope), 0.01f)
        assertEquals(AudioDucking.DUCK_GAIN,
            AudioEnvelope.gainAt(2_500_000L, 8_000_000L, envelope), 0.001f)
        assertEquals(1f, AudioEnvelope.gainAt(5_500_000L, 8_000_000L, envelope), 0.001f)
        assertEquals(emptyList<AudioGainKeyframe>(), graph.layers[1].audioEnvelope)
    }

    @Test fun unityAutomationReportsAValidExclusiveBoundaryForMedia3() {
        val points = listOf(
            AudioGainKeyframe(0L, 1f),
            AudioGainKeyframe(1_000_000L, 1f),
            AudioGainKeyframe(2_000_000L, 0.25f),
            AudioGainKeyframe(3_000_000L, 1f)
        )
        assertEquals(1L, AudioEnvelope.unityUntilSamplePosition(0L, 1_000, 4_000_000L, points))
        assertEquals(501L, AudioEnvelope.unityUntilSamplePosition(500L, 1_000, 4_000_000L, points))
        assertNull(AudioEnvelope.unityUntilSamplePosition(2_500L, 1_000, 4_000_000L, points))
        assertEquals(AudioEnvelope.UNITY_TO_END_OF_STREAM,
            AudioEnvelope.unityUntilSamplePosition(4_000L, 1_000, 4_000_000L, points))
        assertNull(AudioEnvelope.unityUntilSamplePosition(
            0L, 1_000, 4_000_000L, points, globalGain = 0.5f
        ))
    }

    @Test fun authoredAutomationIsMultipliedAndMutedDialogueDoesNotDuck() {
        val musicClip = audio("music", "music", 0L, 6_000_000L).copy(
            audioEnvelope = listOf(AudioGainKeyframe(0L, 1f),
                AudioGainKeyframe(6_000_000L, 0.5f))
        )
        val speech = audio("speech", "voice", 2_000_000L, 1_000_000L)
        val graph = LayerTimeline(tracks = listOf(music, voice),
            layers = listOf(musicClip, speech))
        val mixed = AudioMixPlan.from(graph, 6_000_000L)
            .first { it.trackId == "music" }
            .parts.filterIsInstance<AudioPart.Source>().single()
        assertEquals(0.25f * AudioEnvelope.gainAt(2_500_000L, 6_000_000L,
            musicClip.audioEnvelope), AudioEnvelope.gainAt(2_500_000L,
            mixed.durationUs, mixed.envelope), 0.001f)

        val mutedVoice = graph.copy(tracks = listOf(music, voice.copy(muted = true)))
        val noDuck = AudioMixPlan.from(mutedVoice, 6_000_000L)
            .first { it.trackId == "music" }
            .parts.filterIsInstance<AudioPart.Source>().single()
        assertEquals(musicClip.audioEnvelope, noDuck.envelope)
    }

    @Test fun closeDialogueEditsShareOneDuckingWindowWithoutPumping() {
        val graph = LayerTimeline(tracks = listOf(music, voice), layers = listOf(
            audio("a", "voice", 1_000_000L, 1_000_000L),
            audio("b", "voice", 2_050_000L, 1_000_000L)
        ))
        assertEquals(listOf(AudioDucking.Window(1_000_000L, 3_050_000L)),
            AudioDucking.sourceWindows(graph))
        val windows = AudioDucking.sourceWindows(graph)
        assertEquals(AudioDucking.DUCK_GAIN,
            AudioDucking.gainAt(2_000_000L, windows), 0.001f)
    }

    @Test fun denseSeparatedDialogueFailsWithAnActionableAutomationLimit() {
        val musicClip = audio("music", "music", 0L, 10_000_000L)
        val dialogue = (0 until 14).map { index ->
            audio("speech-$index", "voice", index * 600_000L, 100_000L)
        }
        val graph = LayerTimeline(tracks = listOf(music, voice),
            layers = listOf(musicClip) + dialogue)

        try {
            AudioMixPlan.from(graph, 10_000_000L)
            fail("Dense dialogue must not silently truncate generated gain points")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("too many automation points"))
            assertTrue(error.message.orEmpty().contains("music"))
        }
    }

    @Test fun rationalFrameSnappedLayerPositionsProduceMillisecondSafeKeys() {
        val musicClip = audio("music", "music", 1_033_333L, 4_000_000L)
        val speech = audio("speech", "voice", 2_100_000L, 1_000_000L)
        val graph = LayerTimeline(tracks = listOf(music, voice),
            layers = listOf(musicClip, speech))
        val envelope = AudioMixPlan.from(graph, 6_000_000L)
            .first { it.trackId == "music" }
            .parts.filterIsInstance<AudioPart.Source>().single().envelope

        assertTrue(envelope.isNotEmpty())
        assertTrue(envelope.all { it.timeUs % 1_000L == 0L })
        assertTrue(envelope.all { it.timeUs <= musicClip.durationUs })
        assertEquals(AudioDucking.DUCK_GAIN,
            AudioEnvelope.gainAt(1_067_000L, musicClip.durationUs, envelope), 0.01f)
    }

    @Test fun sourceTrackDoesNotDuckItselfAndNonAudioTrackCannotBeSource() {
        val graph = LayerTimeline(tracks = listOf(voice),
            layers = listOf(audio("voice", "voice", 0L, 2_000_000L)))
        val source = AudioMixPlan.from(graph, 2_000_000L).single()
            .parts.filterIsInstance<AudioPart.Source>().single()
        assertTrue(source.envelope.isEmpty())
        try {
            TimelineTrack("video", "Video", LayerKind.VIDEO, 0, duckingSource = true)
            fail("A video track cannot be an audio ducking source")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun sourceToggleIsUndoableDraftStateAndRespectsTrackLock() {
        val draft = EditorDraft(layers = LayerTimeline(tracks = listOf(music)))
        val enabled = LayerEdits.setAudioDuckingSource(draft, "music", true)
        assertFalse(draft.layers.tracks.single().duckingSource)
        assertTrue(enabled.layers.tracks.single().duckingSource)
        val locked = enabled.copy(layers = enabled.layers.setTrackLocked("music", true))
        try {
            LayerEdits.setAudioDuckingSource(locked, "music", false)
            fail("Locked audio tracks must not accept edits")
        } catch (_: IllegalArgumentException) { }
    }
}
