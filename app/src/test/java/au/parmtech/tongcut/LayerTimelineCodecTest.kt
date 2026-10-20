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

    @Test fun captionStylesSurviveSchema8AndSchema7Defaults() {
        val textLayer = timeline.layers.first { it.kind == LayerKind.TEXT }
        val styled = textLayer.copy(
            captionStyle = CaptionStyle(CaptionTone.CORAL, size = 1.8f, bold = false,
                font = CaptionFont.SERIF, alignment = CaptionAlignment.RIGHT, background = true,
                placement = CaptionPlacement.BOTTOM, entrance = CaptionEntrance.POP, shadow = true)
        )
        val project = timeline.copy(layers = listOf(timeline.layers.first(), styled))
        val encoded = LayerTimelineCodec.encode(project)
        assertEquals(26, encoded.getInt("schema"))
        assertEquals(project, LayerTimelineCodec.decode(encoded))
        val legacy = LayerTimelineCodec.encode(timeline)
        legacy.put("schema", 7)
        for (index in 0 until legacy.getJSONArray("layers").length()) {
            legacy.getJSONArray("layers").getJSONObject(index).remove("captionStyle")
        }
        val migrated = LayerTimelineCodec.decode(legacy)
        assertEquals(CaptionStyle(),
            migrated.layers.first { it.kind == LayerKind.TEXT }.captionStyle)

        val oldCaptionStyle = LayerTimelineCodec.encode(project).put("schema", 18)
        oldCaptionStyle.getJSONArray("layers").getJSONObject(1)
            .getJSONObject("captionStyle").remove("font")
        oldCaptionStyle.getJSONArray("layers").getJSONObject(1)
            .getJSONObject("captionStyle").remove("shadow")
        assertEquals(CaptionFont.SANS,
            LayerTimelineCodec.decode(oldCaptionStyle).layers.last().captionStyle.font)
        oldCaptionStyle.getJSONArray("layers").getJSONObject(1)
            .getJSONObject("captionStyle").put("alignment", "INVALID")
        oldCaptionStyle.getJSONArray("layers").getJSONObject(1)
            .getJSONObject("captionStyle").remove("background")
        oldCaptionStyle.getJSONArray("layers").getJSONObject(1)
            .getJSONObject("captionStyle").put("placement", "INVALID")
        oldCaptionStyle.getJSONArray("layers").getJSONObject(1)
            .getJSONObject("captionStyle").put("entrance", "INVALID")
        assertEquals(CaptionAlignment.CENTER,
            LayerTimelineCodec.decode(oldCaptionStyle).layers.last().captionStyle.alignment)
        assertFalse(LayerTimelineCodec.decode(oldCaptionStyle).layers.last().captionStyle.background)
        assertEquals(CaptionPlacement.CENTER,
            LayerTimelineCodec.decode(oldCaptionStyle).layers.last().captionStyle.placement)
        assertEquals(CaptionEntrance.NONE,
            LayerTimelineCodec.decode(oldCaptionStyle).layers.last().captionStyle.entrance)
        assertFalse(LayerTimelineCodec.decode(oldCaptionStyle).layers.last().captionStyle.shadow)
        val captionBeforePlacement = JSONObject(oldCaptionStyle.toString())
        captionBeforePlacement.getJSONArray("layers").getJSONObject(1)
            .getJSONObject("captionStyle").remove("placement")
        assertEquals(CaptionPlacement.CENTER,
            LayerTimelineCodec.decode(captionBeforePlacement).layers.last().captionStyle.placement)
        captionBeforePlacement.getJSONArray("layers").getJSONObject(1)
            .getJSONObject("captionStyle").remove("entrance")
        assertEquals(CaptionEntrance.NONE,
            LayerTimelineCodec.decode(captionBeforePlacement).layers.last().captionStyle.entrance)
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

    @Test fun motionKeyframesSurviveJsonRoundtrip() {
        val base = timeline.layers.first()
        val animated = base.copy(motion = listOf(
            MotionKeyframe(0L, MotionPose(x = -0.5f, scale = 0.5f)),
            MotionKeyframe(3_000_000L,
                MotionPose(y = 0.4f, rotation = 90f, opacity = 0.3f),
                MotionInterpolation.SMOOTH)
        ))
        val graph = timeline.copy(layers = listOf(animated) + timeline.layers.drop(1))
        assertEquals(graph, LayerTimelineCodec.decode(LayerTimelineCodec.encode(graph)))
    }

    @Test fun schema1WithoutMotionLoadsWithStaticPoses() {
        val encoded = LayerTimelineCodec.encode(timeline)
        encoded.put("schema", 1)
        val clips = encoded.getJSONArray("layers")
        for (i in 0 until clips.length()) {
            clips.getJSONObject(i).remove("motion")
        }
        val graph = LayerTimelineCodec.decode(encoded)
        assertTrue(graph.layers.all { it.motion.isEmpty() })
    }


    @Test fun speedSurvivesSerializationAndSchema2DefaultsToNormal() {
        val video = timeline.layers.first()
        val fast = video.copy(playbackSpeed = 1.5f)
        val changed = timeline.copy(layers = listOf(fast) + timeline.layers.drop(1))
        assertEquals(changed,LayerTimelineCodec.decode(LayerTimelineCodec.encode(changed)))
        val legacy = LayerTimelineCodec.encode(timeline)
        legacy.put("schema",2)
        val raw = legacy.getJSONArray("layers")
        for (i in 0 until raw.length()) raw.getJSONObject(i).remove("playbackSpeed")
        assertTrue(LayerTimelineCodec.decode(legacy).layers.all { it.playbackSpeed == 1f })
    }

    @Test fun photoMaskAndChromaSurviveCurrentSchemaSave() {
        val photo = TimelineTrack("photo5", "Photo", LayerKind.IMAGE, 7)
        val edit = PhotoEffects(
            mask = PhotoMask.CIRCLE,
            feather = 0.12f,
            blur = 0.03f,
            sharpen = 0.4f,
            grain = 0.25f,
            vignette = 0.75f,
            faceRetouch = ManualFaceRetouch(
                centerX = 0.41f, centerY = 0.55f, radiusX = 0.21f, radiusY = 0.28f,
                faceWidth = 0.12f, jawWidth = 0.08f, noseWidth = 0.03f,
                eyeScale = 0.10f, skinSoftening = 0.35f
            ),
            chroma = PhotoChroma(
                keyArgb = 0xFF0000FF.toInt(), tolerance = 0.25f, feather = 0.04f
            )
        )
        val project = LayerTimeline(tracks = listOf(photo), layers = listOf(
            TimelineLayer("still", photo.id, LayerKind.IMAGE, 0, 2_000_000,
                sourceUri="content://image", photoEffects=edit)
        ))
        val json = LayerTimelineCodec.encode(project)
        assertEquals(26, json.getInt("schema"))
        assertEquals(project,LayerTimelineCodec.decode(json))

        val previousRetouchSchema = JSONObject(json.toString()).put("schema", 25)
        previousRetouchSchema.getJSONArray("layers").getJSONObject(0)
            .getJSONObject("photoFx").remove("faceRetouch")
        assertEquals(ManualFaceRetouch(), LayerTimelineCodec.decode(previousRetouchSchema)
            .layers.single().photoEffects.faceRetouch)

        val beforeVignette = JSONObject(json.toString()).put("schema", 20)
        beforeVignette.getJSONArray("layers").getJSONObject(0)
            .getJSONObject("photoFx").remove("vignette")
        assertEquals(0f, LayerTimelineCodec.decode(beforeVignette)
            .layers.single().photoEffects.vignette, 0f)

        val legacy = JSONObject(json.toString()).put("schema", 17)
        legacy.getJSONArray("layers").getJSONObject(0).getJSONObject("photoFx").remove("grain")
        assertEquals(0f, LayerTimelineCodec.decode(legacy).layers.single().photoEffects.grain, 0f)
        legacy.getJSONArray("layers").getJSONObject(0).getJSONObject("photoFx").remove("vignette")
        assertEquals(0f, LayerTimelineCodec.decode(legacy).layers.single().photoEffects.vignette, 0f)
    }

    @Test fun schema4WithoutPhotoFxDefaultsToUnmodifiedPhoto() {
        val photo = TimelineTrack("photo4", "Photo", LayerKind.IMAGE, 3)
        val project = LayerTimeline(tracks=listOf(photo), layers=listOf(
            TimelineLayer("image","photo4",LayerKind.IMAGE,0,500_000,sourceUri="content://pic")
        ))
        val raw = LayerTimelineCodec.encode(project)
        raw.put("schema",4)
        raw.getJSONArray("layers").getJSONObject(0).remove("photoFx")
        val restored = LayerTimelineCodec.decode(raw)
        assertEquals(PhotoEffects(),restored.layers.single().photoEffects)
        assertEquals(0f, restored.layers.single().photoEffects.blur, 0f)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectInvalidPhotoFeatherFromJson() {
        val photo = TimelineTrack("photo", "Photo", LayerKind.IMAGE, 0)
        val graph = LayerTimeline(tracks=listOf(photo), layers=listOf(
            TimelineLayer("f","photo",LayerKind.IMAGE,0,1_000_000,sourceUri="content://i")
        ))
        val raw = LayerTimelineCodec.encode(graph)
        raw.getJSONArray("layers").getJSONObject(0).getJSONObject("photoFx").put("feather",5.0)
        LayerTimelineCodec.decode(raw)
    }

    @Test fun schema6PersistsSplitFadePhaseAndSchema5LoadsDefault() {
        val photo = TimelineTrack("photo6","Photo",LayerKind.IMAGE,0)
        val fx = LayerFade(1_000_000L,2_000_000L,3_000_000L,6_000_000L)
        val graph = LayerTimeline(tracks=listOf(photo),layers=listOf(
            TimelineLayer("p","photo6",LayerKind.IMAGE,0,3_000_000L,
                sourceUri="content://photo",fade=fx)
        ))
        val serialized = LayerTimelineCodec.encode(graph)
        assertEquals(26, serialized.getInt("schema"))
        assertEquals(graph,LayerTimelineCodec.decode(serialized))
        serialized.put("schema",5)
        serialized.getJSONArray("layers").getJSONObject(0).remove("fade")
        assertEquals(LayerFade(),LayerTimelineCodec.decode(serialized).layers.single().fade)
    }

    @Test fun overlayColorGradeSurvivesTimelineCodecAndLegacyDefaults() {
        val grade = ColorGrade(exposure = 0.5f, saturation = 1.5f, temperature = -0.25f)
        val layer = timeline.layers.first { it.kind == LayerKind.VIDEO }
            .copy(photoEffects = PhotoEffects(colorGrade = grade))
        val encoded = LayerTimelineCodec.encode(timeline.copy(layers = listOf(layer)))
        assertEquals(grade, LayerTimelineCodec.decode(encoded).layers.single().photoEffects.colorGrade)

        val legacy = LayerTimelineCodec.encode(timeline.copy(layers = listOf(layer)))
        legacy.put("schema", 9)
        legacy.getJSONArray("layers").getJSONObject(0).getJSONObject("photoFx").remove("colorGrade")
        assertEquals(ColorGrade(), LayerTimelineCodec.decode(legacy).layers.single().photoEffects.colorGrade)
    }

    @Test fun videoOverlayRetouchSurvivesSchema26AndOlderSchemaDefaultsNeutral() {
        val video = TimelineTrack("video", "Video", LayerKind.VIDEO, 0)
        val retouch = ManualFaceRetouch(
            centerX = 0.46f, centerY = 0.53f, radiusX = 0.24f, radiusY = 0.29f,
            faceWidth = 0.11f, jawWidth = -0.08f, eyeScale = 0.09f
        )
        val graph = LayerTimeline(tracks = listOf(video), layers = listOf(
            TimelineLayer("overlay", "video", LayerKind.VIDEO, 0, 2_000_000L,
                sourceUri = "content://video", photoEffects = PhotoEffects(faceRetouch = retouch))
        ))
        val encoded = LayerTimelineCodec.encode(graph)
        assertEquals(26, encoded.getInt("schema"))
        assertEquals(graph, LayerTimelineCodec.decode(encoded))

        val legacy = JSONObject(encoded.toString()).put("schema", 25)
        legacy.getJSONArray("layers").getJSONObject(0).getJSONObject("photoFx")
            .remove("faceRetouch")
        assertEquals(ManualFaceRetouch(), LayerTimelineCodec.decode(legacy)
            .layers.single().photoEffects.faceRetouch)
    }

    @Test fun dialogueDuckingPersistsAndSchemaTenDefaultsOff() {
        val audio = TimelineTrack("voice", "Voiceover", LayerKind.AUDIO, 0,
            duckingSource = true)
        val graph = LayerTimeline(tracks = listOf(audio), layers = listOf(
            TimelineLayer("speech", "voice", LayerKind.AUDIO, 0, 2_000_000,
                sourceUri = "content://voice")
        ))
        val encoded = LayerTimelineCodec.encode(graph)
        assertEquals(26, encoded.getInt("schema"))
        assertEquals(graph, LayerTimelineCodec.decode(encoded))

        val legacy = JSONObject(encoded.toString()).put("schema", 10)
        legacy.getJSONArray("tracks").getJSONObject(0).remove("duckingSource")
        assertFalse(LayerTimelineCodec.decode(legacy).tracks.single().duckingSource)
    }

    @Test fun audioEqualizerAndRumbleSettingPersistWithLegacyDefaults() {
        val eq = AudioEqualizer(bassDb = 5f, midDb = -3f, trebleDb = 8f)
        val track = TimelineTrack("voice", "Voiceover", LayerKind.AUDIO, 0,
            equalizer = eq, stereoBalance = -0.4f, monoFoldDown = true,
            rumbleReduction = true)
        val graph = LayerTimeline(tracks = listOf(track), layers = listOf(
            TimelineLayer("speech", "voice", LayerKind.AUDIO, 0, 2_000_000,
                sourceUri = "content://voice")
        ))
        val encoded = LayerTimelineCodec.encode(graph)
        assertEquals(26, encoded.getInt("schema"))
        assertEquals(graph, LayerTimelineCodec.decode(encoded))

        val legacy = JSONObject(encoded.toString()).put("schema", 24)
        legacy.getJSONArray("tracks").getJSONObject(0).apply {
            remove("equalizer")
            remove("stereoBalance")
            remove("monoFoldDown")
            remove("rumbleReduction")
        }
        val restored = LayerTimelineCodec.decode(legacy).tracks.single()
        assertEquals(AudioEqualizer(), restored.equalizer)
        assertEquals(0f, restored.stereoBalance)
        assertEquals(false, restored.monoFoldDown)
        assertEquals(false, restored.rumbleReduction)
    }
}
