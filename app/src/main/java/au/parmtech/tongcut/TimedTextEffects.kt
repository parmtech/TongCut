package au.parmtech.tongcut

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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

    fun create(timeline: LayerTimeline): OverlayEffect? {
        val visibleTracks = timeline.tracks.associateBy { it.id }
        val overlays: List<TextureOverlay> = timeline.layers
            .filter { it.kind == LayerKind.TEXT }
            .map { layer ->
                val visible = visibleTracks[layer.trackId]?.hidden != true
                val styled = SpannableString(layer.text.orEmpty()).apply {
                    setSpan(
                        ForegroundColorSpan(Color.WHITE),
                        0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    setSpan(
                        StyleSpan(Typeface.BOLD),
                        0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                // Never return an empty string to TextOverlay: it can create a
                // zero-width Bitmap and crash the GL export worker.
                val shown = StaticOverlaySettings.Builder().setAlphaScale(1f).build()
                val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
                object : TextOverlay() {
                    override fun getText(presentationTimeUs: Long): SpannableString = styled

                    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                        if (visible && presentationTimeUs >= layer.startUs &&
                            presentationTimeUs < layer.endUs) shown else hidden
                }
            }
        return if (overlays.isEmpty()) null else OverlayEffect(overlays)
    }
}
