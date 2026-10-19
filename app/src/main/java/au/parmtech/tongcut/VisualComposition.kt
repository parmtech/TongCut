package au.parmtech.tongcut

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence

/**
 * Frame-order-aware Media3 compositor for VIDEO PiP.
 *
 * IMPORTANT: Media3 draws compositor inputs in REVERSE order: input 0 is FRONT.
 * The first VIDEO-only lane therefore drives presentation timestamps; the main
 * full-frame footage is deliberately registered LAST, and drawn BEHIND overlays.
 * The audio-only sequences are appended after all video sequences.
 *
 * Empty regions in each PiP lane generate black gap frames; those regions
 * receive alpha=0 in getOverlaySettings to reveal the main video behind.
 *
 * This path still requires exported-pixel tests on actual Android hardware
 * before it can be marked accepted. For unsupported codecs, export reports
 * the failure rather than discarding overlays silently.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VisualComposition {
    private val base = StaticOverlaySettings.Builder().build()

    private val locations = listOf(
        0.55f to -0.55f,
        -0.55f to -0.55f,
        0.55f to 0.55f
    )

    fun exportLanes(timeline: LayerTimeline, totalUs: Long): List<VisualLanePlan> =
        CompositorVideoPlan.from(timeline, totalUs).overlayLanes

    /** Audio/video media sources with a full-length black (transparent) gap tail. */
    fun sequences(plans: List<VisualLanePlan>, totalUs: Long): List<EditedMediaItemSequence> =
        plans.map { lane ->
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            var duration = 0L
            for (part in lane.items) {
                when (part) {
                    is VisualPart.Gap -> builder.addGap(part.durationUs)
                    is VisualPart.Clip -> {
                        require(part.kind == LayerKind.VIDEO)
                        val start = part.sourceInUs / 1_000L
                        val item = MediaItem.Builder().setUri(part.uri)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(start)
                                    .setEndPositionMs(start + part.durationUs / 1_000L)
                                    .build()
                            ).build()
                        builder.addItem(EditedMediaItem.Builder(item)
                            .setRemoveAudio(true).build())
                    }
                }
                duration += part.durationUs
            }
            require(duration <= totalUs) { "PiP lane runs longer than video" }
            if (duration < totalUs) builder.addGap(totalUs - duration)
            builder.build()
        }

    /**
     * The base video occupies input index plans.size.
     * For all other inputs, opaque video content is a 42% PiP rectangle.
     */
    fun settings(
        plans: List<VisualLanePlan>,
        timeline: LayerTimeline,
        videoDurationUs: Long
    ): VideoCompositorSettings {
        require(plans.isNotEmpty())
        val schedule = CompositorVideoPlan(videoDurationUs, plans)
        val byId = timeline.layers.associateBy { it.id }
        return object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size {
                require(inputSizes.size >= plans.size + 1) { "Base video input missing" }
                return inputSizes[plans.size]
            }

            override fun getOverlaySettings(
                inputId: Int,
                presentationTimeUs: Long
            ): OverlaySettings {
                if (inputId == plans.size) return base
                if (inputId !in plans.indices) return base
                val layer = schedule.layerAt(inputId, presentationTimeUs)
                    ?.let(byId::get)
                val pose = if (layer != null) {
                    MotionCurve.at(layer.motion,
                        (presentationTimeUs - layer.startUs).coerceIn(0L, layer.durationUs))
                } else MotionPose()
                val position = locations[inputId.coerceIn(0, locations.lastIndex)]
                return StaticOverlaySettings.Builder()
                    .setScale(0.42f * pose.scale, 0.42f * pose.scale)
                    .setBackgroundFrameAnchor(
                        (position.first + pose.x).coerceIn(-1f, 1f),
                        (position.second + pose.y).coerceIn(-1f, 1f)
                    )
                    .setOverlayFrameAnchor(0f, 0f)
                    .setRotationDegrees(pose.rotation)
                    .setAlphaScale(schedule.alphaFor(inputId, presentationTimeUs) * pose.opacity)
                    .build()
            }
        }
    }
}
