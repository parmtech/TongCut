package au.parmtech.tongcut.beauty

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi

/**
 * Integration entry point for the SAME Media3 video effect in preview/export.
 *
 * Use `List<Effect>` with ExoPlayer.setVideoEffects() (preview), or with
 * EditedMediaItem.Builder().setEffects(Effects(audioEffects, videoEffects))
 * (Transformer). The caller still has to wire BOTH pathways to the project
 * layer/clip; merely compiling this class does not render a video effect.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object FaceReshapeEffectFactory {

    /**
     * @param edit immutable face reshape payload for this single source clip.
     * @param sourceTimeAtPresentationUs map the EFFECT'S Media3 frame PTS to
     *   the ORIGINAL SOURCE µs, not the project playhead. For trims, fades,
     *   speed ramps and reverse use the real layer/clip source clock; return
     *   null for an unmappable frame. Capturing the current mutable playhead
     *   position here will make export non-deterministic and is forbidden.
     *
     * A new effect instance must be created for each processing pipeline and
     * each item; do not share GL shader instances between preview and export.
     */
    fun videoEffects(
        edit: FaceReshapeEdit?,
        sourceTimeAtPresentationUs: (Long) -> Long?
    ): List<Effect> {
        if (edit == null || edit.parameters.isNeutral || edit.observations.isEmpty()) {
            return emptyList()
        }
        val prepared = edit.preparedTrack()
        return listOf(
            FaceReshapeVideoEffect(
                params = edit.parameters,
                observations = prepared,
                sourceTimeAtPresentationUs = sourceTimeAtPresentationUs
            )
        )
    }
}
