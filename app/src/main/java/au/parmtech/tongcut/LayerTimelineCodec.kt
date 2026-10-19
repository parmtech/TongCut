package au.parmtech.tongcut

import org.json.JSONArray
import org.json.JSONObject

/**
 * Schema v1 for layered state nested inside ProjectStore schema v2.
 * This codec is pure JVM and validates all parsed tracks/clips via constructors.
 */
object LayerTimelineCodec {
    fun encode(timeline: LayerTimeline): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("fpsNum", timeline.rate.numerator)
        put("fpsDen", timeline.rate.denominator)
        put("tracks", JSONArray().apply {
            timeline.tracks.forEach { track ->
                put(JSONObject().apply {
                    put("id", track.id)
                    put("name", track.name)
                    put("kind", track.kind.name)
                    put("order", track.order)
                    put("locked", track.locked)
                    put("hidden", track.hidden)
                    put("muted", track.muted)
                    put("gain", track.gain.toDouble())
                })
            }
        })
        put("layers", JSONArray().apply {
            timeline.layers.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("trackId", item.trackId)
                    put("kind", item.kind.name)
                    put("startUs", item.startUs)
                    put("durationUs", item.durationUs)
                    put("sourceUri", item.sourceUri ?: JSONObject.NULL)
                    put("sourceInUs", item.sourceInUs)
                    put("sourceDurationUs", item.sourceDurationUs ?: JSONObject.NULL)
                    put("text", item.text ?: JSONObject.NULL)
                })
            }
        })
    }

    fun decode(obj: JSONObject): LayerTimeline {
        require(obj.getInt("schema") == 1) { "Unsupported layered timeline format" }
        val rate = TimelineRate(
            numerator = obj.getInt("fpsNum"),
            denominator = obj.getInt("fpsDen")
        )
        val tracksJson = obj.getJSONArray("tracks")
        val itemsJson = obj.getJSONArray("layers")
        require(tracksJson.length() <= 64 && itemsJson.length() <= 5_000) {
            "Project layer limit exceeded"
        }
        val tracks = (0 until tracksJson.length()).map { i ->
            val track = tracksJson.getJSONObject(i)
            TimelineTrack(
                id = track.getString("id"),
                name = track.getString("name"),
                kind = LayerKind.valueOf(track.getString("kind")),
                order = track.getInt("order"),
                locked = track.optBoolean("locked", false),
                hidden = track.optBoolean("hidden", false),
                muted = track.optBoolean("muted", false),
                gain = track.optDouble("gain", 1.0).toFloat()
            )
        }
        val clips = (0 until itemsJson.length()).map { i ->
            val item = itemsJson.getJSONObject(i)
            TimelineLayer(
                id = item.getString("id"),
                trackId = item.getString("trackId"),
                kind = LayerKind.valueOf(item.getString("kind")),
                startUs = item.getLong("startUs"),
                durationUs = item.getLong("durationUs"),
                sourceUri = item.takeNullableString("sourceUri"),
                sourceInUs = item.optLong("sourceInUs", 0L),
                sourceDurationUs = if (item.has("sourceDurationUs") &&
                    !item.isNull("sourceDurationUs")) item.getLong("sourceDurationUs") else null,
                text = item.takeNullableString("text")
            )
        }
        return LayerTimeline(rate, tracks, clips)
    }

    private fun JSONObject.takeNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
