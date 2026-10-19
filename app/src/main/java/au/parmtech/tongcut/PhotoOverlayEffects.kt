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
    fun overlays(context: Context, timeline: LayerTimeline): List<TextureOverlay> {
        val byTrack = timeline.tracks.associateBy { it.id }
        val photos = timeline.layers.filter {
            it.kind == LayerKind.IMAGE && byTrack[it.trackId]?.hidden != true
        }.sortedWith(compareBy<TimelineLayer> { byTrack[it.trackId]!!.order }.thenBy { it.startUs })

        return photos.mapIndexed { index, layer ->
            val position = when (index % 3) {
                0 -> 0.55f to -0.55f
                1 -> -0.55f to -0.55f
                else -> 0.55f to 0.55f
            }
            object : BitmapOverlay() {
                private var image: Bitmap? = null

                override fun getBitmap(presentationTimeUs: Long): Bitmap {
                    image?.let { return it }
                    val uri = Uri.parse(requireNotNull(layer.sourceUri))
                    return PhotoBitmapDecoder.decode(context, uri, layer.photoEffects)
                        .also { image = it }
                }

                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                    val pose = MotionCurve.at(
                        layer.motion,
                        (presentationTimeUs - layer.startUs).coerceIn(0L, layer.durationUs)
                    )
                    return StaticOverlaySettings.Builder()
                        .setScale(0.42f * pose.scale, 0.42f * pose.scale)
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
