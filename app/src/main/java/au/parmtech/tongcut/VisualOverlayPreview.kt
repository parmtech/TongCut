package au.parmtech.tongcut

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/** Overlay monitor with independently decoded muted videos or photo thumbnails. */
@Composable
internal fun VisualOverlayPreview(
    layer: TimelineLayer,
    timelineMs: Long,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pose = MotionCurve.at(
        layer.motion,
        (timelineMs * 1_000L - layer.startUs).coerceIn(0L, layer.durationUs)
    )
    Box(
        modifier.graphicsLayer {
            translationX = pose.x * size.width
            translationY = -pose.y * size.height
            scaleX = pose.scale
            scaleY = pose.scale
            rotationZ = pose.rotation
            alpha = pose.opacity
        }.then(if (layer.kind == LayerKind.VIDEO) Modifier.background(Color.Black) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (layer.kind == LayerKind.IMAGE) {
            // Bitmap preview and exported Media3 BitmapOverlay consume identical matte pixels.
            val bitmap by produceState<android.graphics.Bitmap?>(
                initialValue = null, layer.sourceUri, layer.photoEffects
            ) {
                value = withContext(Dispatchers.IO) {
                    runCatching {
                        PhotoBitmapDecoder.decode(
                            context, Uri.parse(requireNotNull(layer.sourceUri)), layer.photoEffects
                        )
                    }.getOrNull()
                }
            }
            bitmap?.let { image ->
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Photo with mask and chroma",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        } else if (layer.kind == LayerKind.VIDEO) {
            val player = remember(context,layer.id,layer.sourceUri) {
                ExoPlayer.Builder(context).build().apply { volume = 0f }
            }
            DisposableEffect(player) {
                onDispose { player.release() }
            }
            LaunchedEffect(player, layer.sourceUri, layer.sourceInUs, layer.durationUs) {
                val fromMs = layer.sourceInUs / 1_000L
                val toMs = (layer.sourceInUs + layer.consumedSourceUs) / 1_000L
                player.setMediaItem(
                    MediaItem.Builder().setUri(requireNotNull(layer.sourceUri))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(fromMs)
                                .setEndPositionMs(toMs)
                                .build()
                        ).build()
                )
                player.prepare()
            }
            LaunchedEffect(player, timelineMs, playing, layer.startUs) {
                val absoluteProjectUs = Math.multiplyExact(timelineMs, 1_000L)
                val withinClip = (
                    layer.sourceAt(absoluteProjectUs) - layer.sourceInUs
                ) / 1_000L
                val speed = if (layer.speedRamp.isEmpty()) layer.playbackSpeed
                    else SpeedRamp.speedAt(withinClip * 1_000L, layer.speedRamp)
                player.setPlaybackSpeed(speed)
                if (abs(player.currentPosition - withinClip) > 170L)
                    player.seekTo(withinClip)
                if (playing) player.play() else player.pause()
            }
            AndroidView(
                factory = { viewContext ->
                    TextureView(viewContext).also { player.setVideoTextureView(it) }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
