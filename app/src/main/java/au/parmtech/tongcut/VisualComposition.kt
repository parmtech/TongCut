package au.parmtech.tongcut

import android.util.Size
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence

/**
 * Experimental native Media3 layered video/image composition, with primary
 * sequence first and at most 3 secondary PiP lanes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VisualComposition {
    private val normal = StaticOverlaySettings.Builder().build()
    private val locations = listOf(
        0.55f to -0.55f, // bottom-right
        -0.55f to -0.55f, // bottom-left
        0.55f to 0.55f // top-right
    )
    val layout: VideoCompositorSettings = object : VideoCompositorSettings {
        override fun getOutputSize(inputSizes: List<Size>): Size =
            requireNotNull(inputSizes.firstOrNull()) { "No primary video frame" }

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            if (inputId == 0) return normal
            val anchor = locations[(inputId - 1).coerceIn(0, locations.lastIndex)]
            return StaticOverlaySettings.Builder()
                .setScale(0.42f, 0.42f)
                .setBackgroundFrameAnchor(anchor.first, anchor.second)
                .setOverlayFrameAnchor(0f, 0f)
                .build()
        }
    }

    fun sequences(plans: List<VisualLanePlan>): List<EditedMediaItemSequence> =
        plans.map { plan ->
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
            plan.items.forEach { part ->
                when (part) {
                    is VisualPart.Gap -> builder.addGap(part.durationUs)
                    is VisualPart.Clip -> {
                        require(part.durationUs % 1_000L == 0L && part.sourceInUs % 1_000L == 0L) {
                            "Overlay requires millisecond-aligned source points"
                        }
                        val item = if (part.kind == LayerKind.IMAGE) {
                            MediaItem.Builder().setUri(part.uri)
                                .setImageDurationMs(part.durationUs / 1_000L)
                                .build()
                        } else {
                            val start = part.sourceInUs / 1_000L
                            MediaItem.Builder().setUri(part.uri)
                                .setClippingConfiguration(
                                    MediaItem.ClippingConfiguration.Builder()
                                        .setStartPositionMs(start)
                                        .setEndPositionMs(start + part.durationUs / 1_000L)
                                        .build()
                                ).build()
                        }
                        builder.addItem(EditedMediaItem.Builder(item)
                            .setRemoveAudio(true)
                            .build())
                    }
                }
            }
            builder.build()
        }
}
