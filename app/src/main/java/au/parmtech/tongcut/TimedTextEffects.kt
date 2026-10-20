package au.parmtech.tongcut

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.Layout
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.CharacterStyle
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay

/**
 * Time-gated text overlay for the Media3 global composition timebase.
 * Avoids silently ignoring authored text during export.
 *
 * Preview compositing currently uses a parallel Compose overlay; placement/typography
 * across aspect ratios needs device-level verification before final phase acceptance.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object TimedTextEffects {
    fun supported(timeline: LayerTimeline): Boolean = timeline.layers.all { it.kind == LayerKind.TEXT }

    fun visibleAt(timeline: LayerTimeline, timeUs: Long): List<String> =
        timeline.activeAt(timeUs)
            .filter { it.kind == LayerKind.TEXT }
            .mapNotNull { it.text }

    fun overlays(timeline: LayerTimeline): List<TextureOverlay> {
        val visibleTracks = timeline.tracks.associateBy { it.id }
        val overlays: List<TextureOverlay> = timeline.layers
            .filter { it.kind == LayerKind.TEXT }
            .map { layer ->
                val visible = visibleTracks[layer.trackId]?.hidden != true
                val styled = SpannableString(layer.text.orEmpty()).apply {
                    setSpan(
                        ForegroundColorSpan(layer.captionStyle.tone.argb),
                        0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    if (layer.captionStyle.bold) {
                        setSpan(
                            StyleSpan(Typeface.BOLD),
                            0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    setSpan(
                        TypefaceSpan(layer.captionStyle.font.androidFamily),
                        0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    if (layer.captionStyle.shadow && length > 0) {
                        setSpan(CaptionShadowSpan(), 0, length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val alignment = when (layer.captionStyle.alignment) {
                        CaptionAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
                        CaptionAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                        CaptionAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    }
                    setSpan(AlignmentSpan.Standard(alignment), 0, length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (layer.captionStyle.background && length > 0) {
                        setSpan(BackgroundColorSpan(0xB820252C.toInt()), 0, length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                // Per-frame settings animate its pose and align the intrinsic text block
                // against a virtual 88%-frame caption box.
                object : TextOverlay() {
                    override fun getText(presentationTimeUs: Long): SpannableString = styled

                    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                        val relativeUs = (presentationTimeUs - layer.startUs)
                            .coerceIn(0L, layer.durationUs)
                        val pose = MotionCurve.at(
                            layer.motion,
                            relativeUs
                        )
                        val entrance = CaptionEntranceMotion.at(
                            layer.captionStyle.entrance, relativeUs, layer.durationUs
                        )
                        val alignmentOffset = when (layer.captionStyle.alignment) {
                            CaptionAlignment.LEFT ->
                                -CAPTION_ALIGNMENT_BOX_HALF_WIDTH * pose.scale
                            CaptionAlignment.CENTER -> 0f
                            CaptionAlignment.RIGHT ->
                                CAPTION_ALIGNMENT_BOX_HALF_WIDTH * pose.scale
                        }
                        val overlayAnchorX = when (layer.captionStyle.alignment) {
                            CaptionAlignment.LEFT -> -1f
                            CaptionAlignment.CENTER -> 0f
                            CaptionAlignment.RIGHT -> 1f
                        }
                        val placementOffsetY = when (layer.captionStyle.placement) {
                            CaptionPlacement.TOP -> 0.65f
                            CaptionPlacement.CENTER -> 0f
                            CaptionPlacement.BOTTOM -> -0.65f
                        }
                        return StaticOverlaySettings.Builder()
                            .setBackgroundFrameAnchor(
                                (pose.x + alignmentOffset).coerceIn(-1f, 1f),
                                (pose.y + placementOffsetY + entrance.y).coerceIn(-1f, 1f)
                            )
                            .setOverlayFrameAnchor(overlayAnchorX, 0f)
                            .setScale(
                                pose.scale * layer.captionStyle.size * entrance.scale,
                                pose.scale * layer.captionStyle.size * entrance.scale
                            )
                            .setRotationDegrees(pose.rotation)
                            .setAlphaScale(
                                if (visible && presentationTimeUs >= layer.startUs &&
                                    presentationTimeUs < layer.endUs) {
                                    pose.opacity * entrance.alpha * layer.fade.alphaAt(
                                        relativeUs,layer.durationUs)
                                } else 0f
                            ).build()
                    }
                }
            }
        return overlays
    }

    fun create(timeline: LayerTimeline): OverlayEffect? =
        overlays(timeline).takeIf { it.isNotEmpty() }?.let { OverlayEffect(it) }

}

private class CaptionShadowSpan : CharacterStyle() {
    override fun updateDrawState(textPaint: TextPaint) {
        val radius = (textPaint.textSize * 0.11f).coerceAtLeast(1f)
        textPaint.setShadowLayer(radius, 0f, radius * 0.35f, 0xD9000000.toInt())
    }
}
