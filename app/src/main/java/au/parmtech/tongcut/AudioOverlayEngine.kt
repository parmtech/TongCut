package au.parmtech.tongcut

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Lightweight synchronized preview monitor for imported audio tracks.
 * It follows the sequential video player's global timeline position. Audio is
 * decoded by Android, and multiple ExoPlayers are mixed by the system.
 *
 * This is NOT sample-accurate mixing: seek latency and device codec scheduling
 * can cause audible offsets. Hardware integration testing is required.
 */
class AudioOverlayEngine(private val context: Context) {
    private data class Entry(val layer: TimelineLayer, val player: ExoPlayer)
    private val active = LinkedHashMap<String, Entry>()

    /** Called on Android's main thread with the main video playhead. */
    fun sync(graph: LayerTimeline, globalPositionMs: Long, playing: Boolean): String? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val timeUs = globalPositionMs.coerceAtLeast(0L) * 1_000L
        val trackMap = graph.tracks.associateBy { it.id }
        val audible = graph.layers.filter { layer ->
            layer.kind == LayerKind.AUDIO &&
                trackMap[layer.trackId]?.muted == false &&
                layer.startUs <= timeUs && timeUs < layer.endUs
        }.sortedWith(compareBy<TimelineLayer> { trackMap[it.trackId]?.order ?: 0 }.thenBy { it.id })
        if (audible.size > AudioMixPlan.MAX_MIXED_LANES) {
            // Do not crash when a previously saved project has too many active lanes.
            release()
            return "Audio preview requires at most ${AudioMixPlan.MAX_MIXED_LANES} tracks"
        }
        val old = active.iterator()
        while (old.hasNext()) {
            val item = old.next()
            val matching = audible.firstOrNull { it.id == item.key }
            if (matching == null || matching != item.value.layer) {
                item.value.player.release()
                old.remove()
            }
        }
        audible.forEach { layer ->
            val wasCached = active.containsKey(layer.id)
            val entry = active[layer.id] ?: run {
                val player = ExoPlayer.Builder(context).build()
                val startMs = layer.sourceInUs / 1_000L
                val endMs = (layer.sourceInUs + layer.durationUs) / 1_000L
                val input = MediaItem.Builder().setUri(Uri.parse(requireNotNull(layer.sourceUri)))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build()
                    ).build()
                player.setMediaItem(input)
                player.prepare()
                Entry(layer, player).also { active[layer.id] = it }
            }
            val offsetMs = ((timeUs - layer.startUs) / 1_000L)
                .coerceIn(0, (layer.durationUs / 1_000L).coerceAtLeast(0L))
            val decoder = entry.player
            decoder.volume = trackMap[layer.trackId]?.gain ?: 1f
            // Resynchronise after scrubbing / large drift, not every 90ms tick.
            if (!wasCached || abs(decoder.currentPosition - offsetMs) > 240L) {
                decoder.seekTo(offsetMs)
            }
            if (playing) {
                if (!decoder.isPlaying) decoder.play()
            } else decoder.pause()
        }
        return null
    }

    fun release() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active.values.forEach { it.player.release() }
        active.clear()
    }
}
