package au.parmtech.tongcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
            val visible = StaticOverlaySettings.Builder()
                .setScale(0.42f, 0.42f)
                .setBackgroundFrameAnchor(position.first,position.second)
                .setOverlayFrameAnchor(0f,0f)
                .setAlphaScale(1f).build()
            val hidden = StaticOverlaySettings.Builder()
                .setScale(0.42f,0.42f)
                .setBackgroundFrameAnchor(position.first,position.second)
                .setOverlayFrameAnchor(0f,0f)
                .setAlphaScale(0f).build()
            object : BitmapOverlay() {
                private var image: Bitmap? = null

                override fun getBitmap(presentationTimeUs: Long): Bitmap {
                    image?.let { return it }
                    val uri = Uri.parse(requireNotNull(layer.sourceUri))
                    // Decode a bounded texture; 4K phone photos must not cause OOM.
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    val metadataStream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("Cannot read overlay photo")
                    // decodeStream deliberately returns null in inJustDecodeBounds mode.
                    metadataStream.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val largest = maxOf(bounds.outHeight, bounds.outWidth)
                    require(largest > 0) { "Invalid image" }
                    val scaled = BitmapFactory.Options().apply {
                        inSampleSize = (largest / 1024).coerceAtLeast(1)
                    }
                    val bitmap = context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it,null,scaled)
                    } ?: throw IllegalArgumentException("Cannot decode overlay photo")
                    return bitmap.also { image = it }
                }

                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                    if (presentationTimeUs >= layer.startUs && presentationTimeUs < layer.endUs)
                        visible else hidden
            }
        }
    }
}
