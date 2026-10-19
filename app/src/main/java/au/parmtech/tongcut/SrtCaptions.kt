package au.parmtech.tongcut

import java.util.Locale
import java.util.UUID

/** Offline SubRip interchange: captions become normal undoable/persisted TEXT layers. */
data class SrtCue(val startMs: Long, val endMs: Long, val text: String) {
    init {
        require(startMs >= 0L && endMs > startMs) { "Invalid subtitle time range" }
        require(text.isNotBlank() && text.length <= 300) {
            "Caption text must contain 1–300 characters"
        }
    }
}

object SrtCaptions {
    const val MAX_CUES = 2_000
    const val MAX_INPUT_BYTES = 2_000_000

    private val timestamp = Regex(
        """^(\d{1,6}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,6}):(\d{2}):(\d{2})[,.](\d{3})(?:\s+.*)?$"""
    )

    fun parse(input: String): List<SrtCue> {
        require(input.length <= MAX_INPUT_BYTES) { "Subtitles exceed the 2 MB import limit" }
        val normalized = input.removePrefix("\uFEFF")
            .replace("\r\n", "\n").replace('\r', '\n').trim()
        require(normalized.isNotEmpty()) { "The subtitle file is empty" }
        val blocks = normalized.split(Regex("""\n\s*\n"""))
        require(blocks.size <= MAX_CUES) { "Maximum $MAX_CUES subtitle cues supported" }
        return blocks.mapIndexed { index, block ->
            val lines = block.lines()
            val marker = lines.first().trim()
            val timeIndex = if (marker.all { it.isDigit() } && marker.isNotEmpty()) 1 else 0
            require(timeIndex < lines.size) { "Cue ${index + 1} has no time range" }
            val match = timestamp.matchEntire(lines[timeIndex].trim())
                ?: throw IllegalArgumentException("Invalid SRT time range in cue ${index + 1}")
            val parts = match.groupValues
            fun clock(offset: Int): Long {
                val hours = parts[offset].toLong()
                val minutes = parts[offset + 1].toLong()
                val seconds = parts[offset + 2].toLong()
                val millis = parts[offset + 3].toLong()
                require(minutes < 60L && seconds < 60L) {
                    "Invalid minutes or seconds in cue ${index + 1}"
                }
                return Math.addExact(
                    Math.multiplyExact(hours * 3_600L + minutes * 60L + seconds, 1_000L),
                    millis
                )
            }
            val body = lines.drop(timeIndex + 1).joinToString("\n") { it.trimEnd() }.trim()
            try {
                SrtCue(clock(1), clock(5), body)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid cue ${index + 1}: ${error.message}", error)
            }
        }
    }

    /** Atomic single-history-operation import, with extra lanes for overlapping cues. */
    fun importInto(draft: EditorDraft, raw: String): EditorDraft {
        val cues = parse(raw).sortedWith(compareBy<SrtCue> { it.startMs }.thenBy { it.endMs })
        val endMs = SequentialTimeline(draft.clips).totalMs
        require(endMs > 0L) { "Add main video before importing subtitles" }
        require(cues.all { it.endMs <= endMs }) {
            "One or more captions extend past the end of the video"
        }
        val original = draft.layers
        require(original.layers.size + cues.size <= 5_000) { "Project layer limit exceeded" }
        val tracks = original.tracks.toMutableList()
        val layers = original.layers.toMutableList()
        val subtitleTracks = mutableListOf<Pair<String, Long>>()
        var nextOrder = (tracks.maxOfOrNull { it.order } ?: -1) + 1
        for (cue in cues) {
            val available = subtitleTracks.indexOfFirst { it.second <= cue.startMs }
            val lane = if (available >= 0) {
                subtitleTracks[available].first
            } else {
                require(tracks.size < 64) { "Too many simultaneous subtitle tracks" }
                val trackId = UUID.randomUUID().toString()
                tracks += TimelineTrack(
                    id = trackId, name = "Imported captions ${subtitleTracks.size + 1}",
                    kind = LayerKind.TEXT, order = nextOrder++
                )
                subtitleTracks += trackId to 0L
                trackId
            }
            val laneIndex = subtitleTracks.indexOfFirst { it.first == lane }
            subtitleTracks[laneIndex] = lane to cue.endMs
            layers += TimelineLayer(
                id = UUID.randomUUID().toString(),
                trackId = lane,
                kind = LayerKind.TEXT,
                startUs = Math.multiplyExact(cue.startMs, 1_000L),
                durationUs = Math.multiplyExact(cue.endMs - cue.startMs, 1_000L),
                text = cue.text
            )
        }
        return draft.copy(layers = LayerTimeline(original.rate, tracks, layers))
    }

    /** All visible TEXT clips (including handwritten captions), sorted by timeline time. */
    fun exportFrom(draft: EditorDraft): String {
        val shown = draft.layers.tracks.filter {
            it.kind == LayerKind.TEXT && !it.hidden
        }.map { it.id }.toSet()
        val cues = draft.layers.layers.asSequence()
            .filter { it.kind == LayerKind.TEXT && it.trackId in shown }
            .sortedWith(compareBy<TimelineLayer> { it.startUs }.thenBy { it.endUs }.thenBy { it.id })
            .map { layer ->
                SrtCue(
                    startMs = layer.startUs / 1_000L,
                    endMs = (layer.endUs + 999L) / 1_000L,
                    text = requireNotNull(layer.text)
                )
            }.toList()
        require(cues.isNotEmpty()) { "There are no visible captions to export" }
        require(cues.size <= MAX_CUES) { "Too many subtitles for one SRT file" }
        return cues.mapIndexed { index, cue ->
            "${index + 1}\n${format(cue.startMs)} --> ${format(cue.endMs)}\n${cue.text}"
        }.joinToString("\n\n", postfix = "\n")
    }

    private fun format(totalMs: Long): String {
        val hours = totalMs / 3_600_000L
        val minutes = (totalMs / 60_000L) % 60L
        val seconds = (totalMs / 1_000L) % 60L
        return String.format(
            Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, totalMs % 1_000L
        )
    }
}
