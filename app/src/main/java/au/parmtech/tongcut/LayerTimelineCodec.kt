package au.parmtech.tongcut

import org.json.JSONArray
import org.json.JSONObject

/**
 * Schema v1 for layered state nested inside ProjectStore schema v2.
 * This codec is pure JVM and validates all parsed tracks/clips via constructors.
 */
object LayerTimelineCodec {
    fun encode(timeline: LayerTimeline): JSONObject = JSONObject().apply {
        put("schema", 20)
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
                    put("duckingSource", track.duckingSource)
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
                    put("captionStyle", JSONObject().apply {
                        put("tone", item.captionStyle.tone.name)
                        put("size", item.captionStyle.size.toDouble())
                        put("bold", item.captionStyle.bold)
                        put("font", item.captionStyle.font.name)
                        put("alignment", item.captionStyle.alignment.name)
                        put("background", item.captionStyle.background)
                        put("placement", item.captionStyle.placement.name)
                        put("entrance", item.captionStyle.entrance.name)
                        put("shadow", item.captionStyle.shadow)
                    })
                    put("playbackSpeed", item.playbackSpeed.toDouble())
                    put("preservePitch", item.preservePitch)
                    put("visualSlot", item.visualSlot ?: JSONObject.NULL)
                    put("sourceSpanUs", item.sourceSpanUs ?: JSONObject.NULL)
                    put("fade", JSONObject().apply {
                        put("inUs", item.fade.inUs)
                        put("outUs", item.fade.outUs)
                        put("offsetUs", item.fade.phaseOffsetUs)
                        put("totalUs", item.fade.phaseDurationUs ?: JSONObject.NULL)
                    })
                    put("photoFx", JSONObject().apply {
                        put("mask", item.photoEffects.mask.name)
                        put("feather", item.photoEffects.feather.toDouble())
                        put("centerX", item.photoEffects.maskCenterX.toDouble())
                        put("centerY", item.photoEffects.maskCenterY.toDouble())
                        put("scaleX", item.photoEffects.maskScaleX.toDouble())
                        put("scaleY", item.photoEffects.maskScaleY.toDouble())
                        put("invert", item.photoEffects.invertMask)
                        put("rotation", item.photoEffects.maskRotationDegrees.toDouble())
                        put("blur", item.photoEffects.blur.toDouble())
                        put("sharpen", item.photoEffects.sharpen.toDouble())
                        put("grain", item.photoEffects.grain.toDouble())
                        put("colorGrade", ColorGradeCodec.encode(item.photoEffects.colorGrade))
                        put("chroma", item.photoEffects.chroma?.let { key ->
                            JSONObject().apply {
                                put("color", key.keyArgb)
                                put("tolerance", key.tolerance.toDouble())
                                put("feather", key.feather.toDouble())
                                put("spill", key.spillSuppression.toDouble())
                            }
                        } ?: JSONObject.NULL)
                    })
                    put("speedRamp", JSONArray().apply {
                        item.speedRamp.forEach { point ->
                            put(JSONObject().apply {
                                put("sourceOffsetUs", point.sourceOffsetUs)
                                put("speed", point.speed.toDouble())
                            })
                        }
                    })
                    put("audioEnvelope", JSONArray().apply {
                        item.audioEnvelope.forEach { point ->
                            put(JSONObject().apply {
                                put("timeUs", point.timeUs)
                                put("gain", point.gain.toDouble())
                            })
                        }
                    })
                    put("motion", JSONArray().apply {
                        item.motion.forEach { frame ->
                            put(JSONObject().apply {
                                put("timeUs", frame.timeUs)
                                put("mode", frame.interpolation.name)
                                put("x", frame.pose.x.toDouble())
                                put("y", frame.pose.y.toDouble())
                                put("scale", frame.pose.scale.toDouble())
                                put("rotation", frame.pose.rotation.toDouble())
                                put("opacity", frame.pose.opacity.toDouble())
                            })
                        }
                    })
                })
            }
        })
    }

    fun decode(obj: JSONObject): LayerTimeline {
        require(obj.getInt("schema") in 1..20) { "Unsupported layered timeline format" }
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
                gain = track.optDouble("gain", 1.0).toFloat(),
                duckingSource = track.optBoolean("duckingSource", false)
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
                text = item.takeNullableString("text"),
                captionStyle = item.optJSONObject("captionStyle")?.let { style ->
                    CaptionStyle(
                        tone = CaptionTone.valueOf(style.optString("tone", "PEARL")),
                        size = style.optDouble("size", 1.0).toFloat(),
                        bold = style.optBoolean("bold", true),
                        font = CaptionFont.entries.firstOrNull {
                            it.name == style.optString("font")
                        } ?: CaptionFont.SANS,
                        alignment = CaptionAlignment.entries.firstOrNull {
                            it.name == style.optString("alignment")
                        } ?: CaptionAlignment.CENTER,
                        background = style.optBoolean("background", false),
                        placement = CaptionPlacement.entries.firstOrNull {
                            it.name == style.optString("placement")
                        } ?: CaptionPlacement.CENTER,
                        entrance = CaptionEntrance.entries.firstOrNull {
                            it.name == style.optString("entrance")
                        } ?: CaptionEntrance.NONE,
                        shadow = style.optBoolean("shadow", false)
                    )
                } ?: CaptionStyle(),
                fade = item.optJSONObject("fade")?.let { fx ->
                    LayerFade(
                        inUs = fx.optLong("inUs",0L),
                        outUs = fx.optLong("outUs",0L),
                        phaseOffsetUs = fx.optLong("offsetUs",0L),
                        phaseDurationUs = if (fx.has("totalUs") && !fx.isNull("totalUs"))
                            fx.getLong("totalUs") else null
                    )
                } ?: LayerFade(),
                photoEffects = item.optJSONObject("photoFx")?.let { fx ->
                    PhotoEffects(
                        mask = PhotoMask.valueOf(fx.optString("mask","NONE")),
                        feather = fx.optDouble("feather",0.04).toFloat(),
                        maskCenterX = fx.optDouble("centerX",0.0).toFloat(),
                        maskCenterY = fx.optDouble("centerY",0.0).toFloat(),
                        maskScaleX = fx.optDouble("scaleX",1.0).toFloat(),
                        maskScaleY = fx.optDouble("scaleY",1.0).toFloat(),
                        invertMask = fx.optBoolean("invert",false),
                        maskRotationDegrees = fx.optDouble("rotation",0.0).toFloat(),
                        colorGrade = ColorGradeCodec.decode(fx.optJSONObject("colorGrade")),
                        blur = fx.optDouble("blur",0.0).toFloat(),
                        sharpen = fx.optDouble("sharpen",0.0).toFloat(),
                        grain = fx.optDouble("grain",0.0).toFloat(),
                        chroma = fx.optJSONObject("chroma")?.let { c ->
                            PhotoChroma(
                                keyArgb = c.optInt("color",0xFF00FF00.toInt()),
                                tolerance = c.optDouble("tolerance",0.18).toFloat(),
                                feather = c.optDouble("feather",0.12).toFloat(),
                                spillSuppression = c.optDouble("spill",0.0).toFloat()
                            )
                        }
                    )
                } ?: PhotoEffects(),
                playbackSpeed = item.optDouble("playbackSpeed", 1.0).toFloat(),
                preservePitch = item.opt("preservePitch") as? Boolean ?: true,
                visualSlot = if (item.has("visualSlot") && !item.isNull("visualSlot"))
                    item.getInt("visualSlot") else null,
                sourceSpanUs = if (item.has("sourceSpanUs") &&
                    !item.isNull("sourceSpanUs")) item.getLong("sourceSpanUs") else null,
                speedRamp = item.optJSONArray("speedRamp")?.let { points ->
                    require(points.length() <= SpeedRamp.MAX_POINTS)
                    (0 until points.length()).map { index ->
                        val key = points.getJSONObject(index)
                        SpeedKeyframe(
                            sourceOffsetUs = key.getLong("sourceOffsetUs"),
                            speed = key.getDouble("speed").toFloat()
                        )
                    }
                } ?: emptyList(),
                audioEnvelope = item.optJSONArray("audioEnvelope")?.let { points ->
                    require(points.length() <= AudioEnvelope.MAX_POINTS)
                    (0 until points.length()).map { index ->
                        val key = points.getJSONObject(index)
                        AudioGainKeyframe(
                            timeUs = key.getLong("timeUs"),
                            gain = key.getDouble("gain").toFloat()
                        )
                    }
                } ?: emptyList(),
                motion = item.optJSONArray("motion")?.let { frames ->
                    require(frames.length() <= MotionCurve.MAX_KEYFRAMES)
                    (0 until frames.length()).map { index ->
                        val data = frames.getJSONObject(index)
                        MotionKeyframe(
                            timeUs = data.getLong("timeUs"),
                            interpolation = MotionInterpolation.valueOf(
                                data.optString("mode", "LINEAR")
                            ),
                            pose = MotionPose(
                                x = data.getDouble("x").toFloat(),
                                y = data.getDouble("y").toFloat(),
                                scale = data.getDouble("scale").toFloat(),
                                rotation = data.getDouble("rotation").toFloat(),
                                opacity = data.getDouble("opacity").toFloat()
                            )
                        )
                    }
                } ?: emptyList()
            )
        }
        return LayerTimeline(rate, tracks, clips)
    }

    private fun JSONObject.takeNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
