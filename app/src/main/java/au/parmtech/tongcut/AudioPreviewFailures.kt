package au.parmtech.tongcut

/**
 * Framework-free source-aware failure cache. Stops a permanently bad audio URI
 * exhausting limited decoders during playback; changing/relinking the immutable
 * layer lifts the block. The UI reports a short error, not a wall of retries.
 */
internal class AudioPreviewFailures {
    private data class Failure(val layer: TimelineLayer, val message: String)
    private val entries = LinkedHashMap<String, Failure>()

    fun messageFor(layer: TimelineLayer): String? =
        entries[layer.id]?.takeIf { it.layer == layer }?.message

    fun record(layer: TimelineLayer, reason: String): String {
        val label = reason.trim().ifEmpty { "Decoder unavailable" }.take(160)
        val message = "Audio layer cannot play: $label. Relink or replace this clip."
        entries[layer.id] = Failure(layer, message)
        return message
    }

    fun retain(layers: List<TimelineLayer>) {
        val byId = layers.associateBy { it.id }
        entries.entries.removeAll { entry ->
            byId[entry.key] != entry.value.layer
        }
    }

    fun clear() = entries.clear()
}
