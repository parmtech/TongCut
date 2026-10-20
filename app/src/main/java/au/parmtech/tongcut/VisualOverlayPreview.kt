package au.parmtech.tongcut

import android.net.Uri
import android.view.TextureView
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/** Overlay monitor: muted Video PiP decoders with preview/export-shared GPU matte. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
            alpha = pose.opacity * layer.fade.alphaAt(
                (timelineMs * 1_000L - layer.startUs).coerceIn(0L,layer.durationUs),
                layer.durationUs
            )
        }.then(if (layer.kind == LayerKind.VIDEO &&
            layer.photoEffects == PhotoEffects()
        ) Modifier.background(Color.Black) else Modifier),
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
            var videoAspect by remember(player) { mutableFloatStateOf(16f / 9f) }
            var videoError by remember(player) { mutableStateOf<String?>(null) }
            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        videoError = "Video preview failed: ${error.message ?: "unsupported media or GPU effect"}"
                    }
                    override fun onVideoSizeChanged(size: VideoSize) {
                        if (size.width <= 0 || size.height <= 0) return
                        val rotated = size.unappliedRotationDegrees == 90 ||
                            size.unappliedRotationDegrees == 270
                        val w = if (rotated) size.height else size.width
                        val h = if (rotated) size.width else size.height
                        val ratio = w.toFloat() * size.pixelWidthHeightRatio / h.toFloat()
                        if (ratio.isFinite() && ratio in 0.1f..10f) videoAspect = ratio
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    player.release()
                }
            }
            // The SAME per-source GPU key/mask as the exported Media3 MP4.
            // Reconfigure only on actual effect changes; don't reset source time.
            LaunchedEffect(player, layer.photoEffects) {
                val videoEffects = if (layer.photoEffects == PhotoEffects()) emptyList()
                else buildList {
                    if (layer.photoEffects.faceRetouch.isActive) {
                        add(ManualFaceRetouchEffect(layer.photoEffects.faceRetouch))
                    }
                    add(VideoMatteEffect(layer.photoEffects))
                    if (layer.photoEffects.colorGrade.hslBands != DEFAULT_HSL_BANDS ||
                        layer.photoEffects.colorGrade.curves != ColorCurves()) {
                        add(ColorGradeEffect(
                            ColorGrade(hslBands = layer.photoEffects.colorGrade.hslBands,
                                curves = layer.photoEffects.colorGrade.curves),
                            premultipliedInput = true
                        ))
                    }
                    if (layer.photoEffects.vignette > 0f) {
                        add(VideoVignetteEffect(layer.photoEffects.vignette))
                    }
                }
                player.setVideoEffects(videoEffects)
            }
            LaunchedEffect(player, layer.sourceUri, layer.sourceInUs, layer.durationUs,
                layer.sourceSpanUs, layer.playbackSpeed, layer.speedRamp) {
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
            LaunchedEffect(player, timelineMs, playing, layer.startUs,
                layer.playbackSpeed, layer.speedRamp, layer.sourceInUs) {
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
            // A bare TextureView stretches portrait overlays to 16:9; fit the
            // actual decoded video aspect in the same bounded PiP box used by
            // Media3 export. PlayerView's SurfaceView would bypass layer alpha.
            BoxWithConstraints(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                val fittedWidth = minOf(maxWidth, maxHeight * videoAspect)
                val fittedHeight = fittedWidth / videoAspect
                AndroidView(
                    factory = { viewContext ->
                        TextureView(viewContext).also { texture ->
                            texture.isOpaque = false
                            player.setVideoTextureView(texture)
                        }
                    },
                    modifier = Modifier.size(fittedWidth, fittedHeight)
                )
                videoError?.let { error ->
                    Text(error, color = Color(0xFFFFC987), fontSize = 11.sp)
                }
            }
        }
    }
}
