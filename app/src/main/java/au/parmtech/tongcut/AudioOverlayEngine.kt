package au.parmtech.tongcut

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Lightweight Android-backed monitor for independently imported audio tracks.
 * Final sample timing is still owned by the Media3 exporter; each playback decoder
 * is only an approximate monitor until hardware sync acceptance has passed.
 *
 * Error handling prevents a missing file or unsupported decoder
 * from being re-created on every timeline poll (~90 ms). The same failed source
 * stays paused with an actionable message until it is relinked/changed.
 */
class AudioOverlayEngine(private val context: Context) {
    private data class Entry(val layer: TimelineLayer, val player: ExoPlayer)
    private val active = LinkedHashMap<String, Entry>()
    private val failures = AudioPreviewFailures()

    /** Called on Android's main thread with the main video playhead. */
    fun sync(graph: LayerTimeline, globalPositionMs: Long, playing: Boolean): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val timeUs = Math.multiplyExact(globalPositionMs.coerceAtLeast(0L), 1_000L)
        val tracks = graph.tracks.associateBy { it.id }
        val audible = graph.layers.filter { layer ->
            layer.kind == LayerKind.AUDIO &&
                tracks[layer.trackId]?.muted == false &&
                layer.startUs <= timeUs && timeUs < layer.endUs
        }.sortedWith(
            compareBy<TimelineLayer> { tracks[it.trackId]?.order ?: 0 }.thenBy { it.id }
        )
        failures.retain(graph.layers)
        if (audible.size > AudioMixPlan.MAX_MIXED_LANES) {
            releaseActivePlayers()
            return "Audio preview supports at most ${AudioMixPlan.MAX_MIXED_LANES} active tracks"
        }

        val old = active.iterator()
        while (old.hasNext()) {
            val item = old.next()
            val source = audible.firstOrNull { it.id == item.key }
            if (source == null || source != item.value.layer ||
                failures.messageFor(source) != null
            ) {
                item.value.player.release()
                old.remove()
            }
        }

        var warning: String? = null
        audible.forEach { layer ->
            val blocked = failures.messageFor(layer)
            if (blocked != null) {
                if (warning == null) warning = blocked
                return@forEach
            }
            val cached = active[layer.id]
            val entry = cached ?: run {
                var created: ExoPlayer? = null
                try {
                    val player = ExoPlayer.Builder(context).build()
                    created = player
                    val startMs = layer.sourceInUs / 1_000L
                    val endMs = (layer.sourceInUs + layer.consumedSourceUs) / 1_000L
                    val media = MediaItem.Builder()
                        .setUri(Uri.parse(requireNotNull(layer.sourceUri)))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(startMs)
                                .setEndPositionMs(endMs)
                                .build()
                        ).build()
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            if (active[layer.id]?.player === player) {
                                failures.record(layer, error.message ?: "Unsupported audio")
                            }
                        }
                    })
                    player.setMediaItem(media)
                    player.prepare()
                    Entry(layer, player).also { active[layer.id] = it }
                } catch (error: Exception) {
                    created?.release()
                    val message = failures.record(
                        layer, error.message ?: "Audio decoder unavailable"
                    )
                    if (warning == null) warning = message
                    null
                }
            }
            if (entry == null) return@forEach
            val sourceOffsetUs = (layer.sourceAt(timeUs) - layer.sourceInUs)
                .coerceIn(0L, layer.consumedSourceUs)
            val offsetMs = sourceOffsetUs / 1_000L
            val speed = if (layer.speedRamp.isEmpty()) layer.playbackSpeed
                else SpeedRamp.speedAt(sourceOffsetUs, layer.speedRamp)
            val player = entry.player
            try {
                // Avoid rebuilding the audio processor on every ~90ms UI tick:
                // speed ramps only need a new parameter at a source-clock keyframe.
                // Repeated setPlaybackSpeed calls can cause audible clicks/glitches.
                if (abs(player.playbackParameters.speed - speed) > 0.0001f) {
                    player.setPlaybackSpeed(speed)
                }
                val trackGain = tracks[layer.trackId]?.gain ?: 1f
                val targetGain = trackGain * AudioEnvelope.gainAt(
                    (timeUs - layer.startUs).coerceIn(0L, layer.durationUs),
                    layer.durationUs, layer.audioEnvelope
                )
                if (player.volume != targetGain) player.volume = targetGain
                // The seek threshold is in SOURCE time. Scale it by playback
                // speed so a 4x clip does not tolerate 4x project-time drift.
                val allowedSourceDriftMs = (120f * speed).toLong().coerceAtLeast(30L)
                if (cached == null ||
                    abs(player.currentPosition - offsetMs) > allowedSourceDriftMs) {
                    player.seekTo(offsetMs)
                }
                if (playing) {
                    if (!player.isPlaying) player.play()
                } else player.pause()
            } catch (error: Exception) {
                val message = failures.record(
                    layer, error.message ?: "Audio player failed"
                )
                if (warning == null) warning = message
                active.remove(layer.id)?.player?.release()
            }
        }
        return warning
    }

    private fun releaseActivePlayers() {
        active.values.forEach { it.player.release() }
        active.clear()
    }

    fun release() {
        check(Looper.myLooper() == Looper.getMainLooper())
        releaseActivePlayers()
        failures.clear()
    }
}
