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
import androidx.media3.transformer.Effects

/** Full-frame incoming-footage lane for ordinary primary-track cross dissolves. */
@androidx.annotation.OptIn(UnstableApi::class)
object PrimaryTransitionComposition {
    fun sequence(
        clips: List<Clip>,
        plan: PrimaryTransitionPlan,
        frameRate: Int,
        videoEffects: List<androidx.media3.common.Effect>
    ): EditedMediaItemSequence {
        val byId = clips.associateBy { it.id }
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursorUs = 0L
        plan.transitions.forEach { window ->
            require(window.startUs >= cursorUs) { "Primary transition windows overlap" }
            if (window.startUs > cursorUs) builder.addGap(window.startUs - cursorUs)
            val clip = byId[window.incomingClipId]
                ?: error("Transition source clip is missing")
            val incomingHeadMs = window.durationUs / 1_000L
            require(window.durationUs % 1_000L == 0L && incomingHeadMs in 100L..clip.trimmedDurationMs) {
                "Transition duration must fit the incoming clip on millisecond boundaries"
            }
            val source = MediaItem.Builder().setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.startMs + incomingHeadMs)
                        .build()
                ).build()
            builder.addItem(
                EditedMediaItem.Builder(source)
                    .setFrameRate(frameRate)
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()
            )
            cursorUs = window.endUs
        }
        if (cursorUs < plan.durationUs) builder.addGap(plan.durationUs - cursorUs)
        return builder.build()
    }

    /** Input 0 is the dissolve lane; input 1 is the ordinary primary sequence. */
    fun settings(plan: PrimaryTransitionPlan): VideoCompositorSettings {
        val transparent = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        return object : VideoCompositorSettings {
            private var outputSize = Size(0, 0)
            override fun getOutputSize(inputSizes: List<Size>): Size {
                require(inputSizes.size >= 2) { "Primary transition base video input missing" }
                outputSize = inputSizes[1]
                require(outputSize.width > 0 && outputSize.height > 0)
                return outputSize
            }

            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 1) return StaticOverlaySettings.Builder().build()
                if (inputId != 0) return transparent
                val window = plan.transitionAt(presentationTimeUs) ?: return transparent
                val alpha = window.alphaAt(presentationTimeUs).second
                return StaticOverlaySettings.Builder().setAlphaScale(alpha).build()
            }
        }
    }
}
