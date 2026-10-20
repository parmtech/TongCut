package au.parmtech.tongcut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.text.StaticLayout
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
import kotlin.math.ceil

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
                CaptionTextOverlay(layer, visible, styled)
            }
        return overlays
    }

    fun create(timeline: LayerTimeline): OverlayEffect? =
        overlays(timeline).takeIf { it.isNotEmpty() }?.let { OverlayEffect(it) }

}

/** Media3's stock text bitmap hugs the StaticLayout bounds, clipping TextPaint shadows. */
@androidx.annotation.OptIn(UnstableApi::class)
private class CaptionTextOverlay(
    private val layer: TimelineLayer,
    private val visible: Boolean,
    private val text: SpannableString
) : TextOverlay() {
    private val paddingPx = if (layer.captionStyle.shadow) {
        ceil(TextOverlay.TEXT_SIZE_PIXELS * 0.18).toInt().coerceIn(1, 32)
    } else 0
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TextOverlay.TEXT_SIZE_PIXELS.toFloat()
    }
    private val layoutWidth = measureLayoutWidth(text, textPaint)
    private val textLayout = createLayout(text, textPaint, layoutWidth)
    private val bitmapWidth = Math.addExact(layoutWidth, paddingPx * 2).coerceAtLeast(1)
    private val bitmapHeight = Math.addExact(textLayout.height, paddingPx * 2).coerceAtLeast(1)
    private var paddedBitmap: Bitmap? = null

    override fun getText(presentationTimeUs: Long): SpannableString = text

    override fun getBitmap(presentationTimeUs: Long): Bitmap = paddedBitmap ?: Bitmap.createBitmap(
        bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888
    ).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.save()
        canvas.translate(paddingPx.toFloat(), paddingPx.toFloat())
        textLayout.draw(canvas)
        canvas.restore()
        paddedBitmap = bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val relativeUs = (presentationTimeUs - layer.startUs).coerceIn(0L, layer.durationUs)
        val pose = MotionCurve.at(layer.motion, relativeUs)
        val entrance = CaptionEntranceMotion.at(
            layer.captionStyle.entrance, relativeUs, layer.durationUs
        )
        val alignmentOffset = when (layer.captionStyle.alignment) {
            CaptionAlignment.LEFT -> -CAPTION_ALIGNMENT_BOX_HALF_WIDTH * pose.scale
            CaptionAlignment.CENTER -> 0f
            CaptionAlignment.RIGHT -> CAPTION_ALIGNMENT_BOX_HALF_WIDTH * pose.scale
        }
        val anchorPadding = if (bitmapWidth > 0) 2f * paddingPx / bitmapWidth else 0f
        val overlayAnchorX = when (layer.captionStyle.alignment) {
            CaptionAlignment.LEFT -> -1f + anchorPadding
            CaptionAlignment.CENTER -> 0f
            CaptionAlignment.RIGHT -> 1f - anchorPadding
        }
        val placementOffsetY = when (layer.captionStyle.placement) {
            CaptionPlacement.TOP -> 0.65f
            CaptionPlacement.CENTER -> 0f
            CaptionPlacement.BOTTOM -> -0.65f
        }
        val baseScale = pose.scale * layer.captionStyle.size * entrance.scale
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                (pose.x + alignmentOffset).coerceIn(-1f, 1f),
                (pose.y + placementOffsetY + entrance.y).coerceIn(-1f, 1f)
            )
            .setOverlayFrameAnchor(overlayAnchorX, 0f)
            .setScale(
                baseScale * (layoutWidth.toFloat() / bitmapWidth),
                baseScale * (textLayout.height.toFloat() / bitmapHeight)
            )
            .setRotationDegrees(pose.rotation)
            .setAlphaScale(
                if (visible && presentationTimeUs >= layer.startUs &&
                    presentationTimeUs < layer.endUs) {
                    pose.opacity * entrance.alpha * layer.fade.alphaAt(relativeUs, layer.durationUs)
                } else 0f
            ).build()
    }

    override fun release() {
        try {
            super.release()
        } finally {
            paddedBitmap?.recycle()
            paddedBitmap = null
        }
    }

    private companion object {
        fun measureLayoutWidth(text: SpannableString, paint: android.text.TextPaint): Int {
            val measuredWidth = paint.measureText(text, 0, text.length).toInt().coerceAtLeast(1)
            val measuredLayout = createLayout(text, paint, measuredWidth)
            val lineWidthSum = (0 until measuredLayout.lineCount).sumOf { line ->
                measuredLayout.getLineWidth(line).toDouble()
            }
            return ceil(lineWidthSum).toInt().coerceAtLeast(1)
        }

        fun createLayout(
            text: SpannableString, paint: android.text.TextPaint, width: Int
        ): StaticLayout = StaticLayout.Builder.obtain(
            text, 0, text.length, paint, width.coerceAtLeast(1)
        ).build()
    }
}

private class CaptionShadowSpan : CharacterStyle() {
    override fun updateDrawState(textPaint: TextPaint) {
        val radius = (textPaint.textSize * 0.11f).coerceAtLeast(1f)
        textPaint.setShadowLayer(radius, 0f, radius * 0.35f, 0xD9000000.toInt())
    }
}
