package au.parmtech.tongcut

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.audio.SpeedProvider
import kotlin.math.roundToLong
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

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

    fun exportLanes(timeline: LayerTimeline, totalUs: Long): List<VisualLanePlan> =
        CompositorVideoPlan.from(timeline, totalUs).overlayLanes

    /** Audio/video media sources with a full-length black (transparent) gap tail. */
    fun sequences(
        plans: List<VisualLanePlan>, totalUs: Long, timeline: LayerTimeline,
        maxFrameRate: Int = ExportFrameRate.DEFAULT,
        colorLutProfile: ColorLutProfile? = null
    ): List<EditedMediaItemSequence> =
        plans.map { lane ->
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            var duration = 0L
            for (part in lane.items) {
                when (part) {
                    is VisualPart.Gap -> builder.addGap(part.durationUs)
                    is VisualPart.Clip -> {
                        require(part.kind == LayerKind.VIDEO)
                        val start = part.sourceInUs / 1_000L
                        val sourceSpanMs = (
                            (part.sourceSpanUs ?: (
                                part.durationUs.toDouble() * part.playbackSpeed
                            ).roundToLong()) / 1_000L
                        ).coerceAtLeast(1L)
                        val item = MediaItem.Builder().setUri(part.uri)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(start)
                                    .setEndPositionMs(start + sourceSpanMs)
                                    .build()
                            ).build()
                        val edited = EditedMediaItem.Builder(item)
                            .setFrameRate(maxFrameRate)
                            .setRemoveAudio(true)
                        val effects = timeline.layers.firstOrNull {
                            it.id == part.layerId
                        }?.photoEffects ?: PhotoEffects()
                        if (effects != PhotoEffects()) {
                            // Each source input has its own GL matte before layering.
                            // Unsupported HDR fails explicitly; never drop the authored matte.
                            val videoEffects = buildList {
                                add(VideoMatteEffect(effects))
                                if (effects.colorGrade.hslBands != DEFAULT_HSL_BANDS ||
                                    effects.colorGrade.curves != ColorCurves()) {
                                    add(ColorGradeEffect(
                                        ColorGrade(hslBands = effects.colorGrade.hslBands,
                                            curves = effects.colorGrade.curves),
                                        premultipliedInput = true
                                    ))
                                }
                                addAll(VideoOverlayLutEffects.forLayer(
                                    colorLutProfile, effects
                                ))
                                if (effects.vignette > 0f) {
                                    add(VideoVignetteEffect(effects.vignette))
                                }
                            }
                            edited.setEffects(Effects(emptyList(), videoEffects))
                        }
                        if (part.speedRamp.isNotEmpty()) {
                            // The provider's input clock begins at the clipped source zero.
                            // Motion preview reads the same source-based speed boundaries.
                            val points = part.speedRamp
                            edited.setSpeed(object : SpeedProvider {
                                override fun getSpeed(timeUs: Long): Float =
                                    SpeedRamp.speedAt(timeUs, points)
                                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                                    SpeedRamp.nextChangeAfter(timeUs, points)
                                        ?: C.TIME_UNSET
                            })
                        } else if (part.playbackSpeed != 1f) {
                            val value = part.playbackSpeed
                            edited.setSpeed(object : SpeedProvider {
                                override fun getSpeed(timeUs: Long): Float = value
                                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
                                    C.TIME_UNSET
                            })
                        }
                        builder.addItem(edited.build())
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
            private var measuredInputs: List<Size> = emptyList()
            private var canvasAspect = 16f / 9f

            override fun getOutputSize(inputSizes: List<Size>): Size {
                require(inputSizes.size >= plans.size + 1) { "Base video input missing" }
                val baseSize = inputSizes[plans.size]
                require(baseSize.width > 0 && baseSize.height > 0)
                measuredInputs = inputSizes.toList()
                canvasAspect = baseSize.width.toFloat() / baseSize.height
                return baseSize
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
                // Stable slot from the global VIDEO/IMAGE track order; do not
                // reposition a PiP when another layer starts or ends.
                val position = (layer ?: timeline.layers.firstOrNull {
                    it.trackId == plans[inputId].trackId
                })?.let {
                    VisualOverlaySlots.canvasPosition(timeline, it, canvasAspect)
                } ?: (0f to 0f)
                // A 320x180 PiP over a 180x320 main video must not be
                // stretched to 42% of its OWN pixels. Fit to a 42%-width,
                // 16:9 stage box, exactly as for bitmap PiPs and preview.
                val baseWidth = measuredInputs.getOrNull(plans.size)?.width ?: 320
                val overlaySize = measuredInputs.getOrNull(inputId)
                val fitted = overlaySize?.let {
                    PhotoOverlaySizing.fitScale(it.width, it.height, baseWidth)
                } ?: 0.42f
                return StaticOverlaySettings.Builder()
                    .setScale(fitted * pose.scale, fitted * pose.scale)
                    .setBackgroundFrameAnchor(
                        (position.first + pose.x).coerceIn(-1f, 1f),
                        (position.second + pose.y).coerceIn(-1f, 1f)
                    )
                    .setOverlayFrameAnchor(0f, 0f)
                    .setRotationDegrees(pose.rotation)
                    .setAlphaScale(schedule.alphaFor(inputId, presentationTimeUs) *
                        pose.opacity * (layer?.fade?.alphaAt(
                            (presentationTimeUs - layer.startUs).coerceIn(0L,layer.durationUs),
                            layer.durationUs
                        ) ?: 1f))
                    .build()
            }
        }
    }
}
