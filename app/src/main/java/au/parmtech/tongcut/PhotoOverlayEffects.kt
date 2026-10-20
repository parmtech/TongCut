package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay

/**
 * Bounded static-photo overlays. They are applied AFTER primary video composition,
 * so a fully opaque primary frame cannot obscure them.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object PhotoOverlayEffects {
    fun overlays(
        context: Context, timeline: LayerTimeline,
        outputWidthPx: Int, canvasAspect: Float,
        colorLutProfile: ColorLutProfile? = null
    ): List<TextureOverlay> {
        require(outputWidthPx > 0)
        require(canvasAspect.isFinite() && canvasAspect in 0.1f..10f)
        val byTrack = timeline.tracks.associateBy { it.id }
        val photos = timeline.layers.filter {
            it.kind == LayerKind.IMAGE && byTrack[it.trackId]?.hidden != true
        }.sortedWith(compareBy<TimelineLayer> { byTrack[it.trackId]!!.order }.thenBy { it.startUs })

        return photos.map { layer ->
            val position = VisualOverlaySlots.canvasPosition(timeline, layer, canvasAspect)
            object : BitmapOverlay() {
                private var image: Bitmap? = null

                override fun release() {
                    // Media3 deletes the GL texture in super.release(), but
                    // does not recycle the decoded source bitmap. A 1024px
                    // masked photo can otherwise retain native memory after
                    // repeated exports on memory-constrained devices.
                    try {
                        super.release()
                    } finally {
                        image?.let { if (!it.isRecycled) it.recycle() }
                        image = null
                    }
                }

                override fun getBitmap(presentationTimeUs: Long): Bitmap {
                    image?.let { return it }
                    val uri = Uri.parse(requireNotNull(layer.sourceUri))
                    // Missing/malformed project LUTs decode as null and remain a neutral no-op.
                    return PhotoBitmapDecoder.decode(
                        context, uri, layer.photoEffects, colorLutProfile
                    )
                        .also { image = it }
                }

                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                    // Media3's setScale is relative to the bitmap's native pixels,
                    // not the output frame. Use the same 42%-width, 16:9 FIT box
                    // used by the Compose PiP preview, preserving photo aspect.
                    val bitmap = image ?: getBitmap(presentationTimeUs)
                    val fitted = PhotoOverlaySizing.fitScale(
                        bitmap.width, bitmap.height, outputWidthPx
                    )
                    val pose = MotionCurve.at(
                        layer.motion,
                        (presentationTimeUs - layer.startUs).coerceIn(0L, layer.durationUs)
                    )
                    return StaticOverlaySettings.Builder()
                        .setScale(fitted * pose.scale, fitted * pose.scale)
                        .setBackgroundFrameAnchor(
                            (position.first + pose.x).coerceIn(-1f, 1f),
                            (position.second + pose.y).coerceIn(-1f, 1f)
                        )
                        .setOverlayFrameAnchor(0f, 0f)
                        .setRotationDegrees(pose.rotation)
                        .setAlphaScale(
                            if (presentationTimeUs >= layer.startUs &&
                                presentationTimeUs < layer.endUs) {
                                pose.opacity * layer.fade.alphaAt(
                                    presentationTimeUs - layer.startUs,layer.durationUs)
                            } else 0f
                        ).build()
                }
            }
        }
    }
}
