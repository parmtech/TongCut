package au.parmtech.tongcut.effects.presets

import org.json.JSONException
import org.json.JSONObject

/** Stable identifiers are persisted in project data; never rename an ID after release. */
enum class EffectKernel(val id: String) {
    SEA_GLASS("sea-glass-v1"),
    SUN_PRINT("sun-print-v1"),
    PAPER_MONO("paper-mono-v1");

    companion object {
        fun fromId(id: String): EffectKernel? = entries.firstOrNull { it.id == id }
    }

}

data class EffectParameterSpec(
    val id: String,
    val label: String,
    val minimum: Float,
    val maximum: Float,
    val defaultValue: Float
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,31}")))
        require(label.isNotBlank())
        require(minimum.isFinite() && maximum.isFinite() && defaultValue.isFinite())
        require(minimum < maximum && defaultValue in minimum..maximum)
    }

    fun validate(value: Float): Boolean = value.isFinite() && value in minimum..maximum
}

/** Renderer contracts name the exact deterministic kernel; unsupported video paths are explicit. */
data class EffectRenderDescriptor(
    val previewKernelId: String,
    val exportKernelId: String,
    val previewMedia: String = "still/ARGB_8888",
    val exportMedia: String = "still/ARGB_8888",
    val videoPreviewKernelId: String? = null,
    val videoExportKernelId: String? = null,
    val videoPreviewMedia: String? = null,
    val videoExportMedia: String? = null
) {
    init {
        require(previewKernelId.isNotBlank() && exportKernelId.isNotBlank())
        require(previewMedia.isNotBlank() && exportMedia.isNotBlank())
        val videoFields = listOf(
            videoPreviewKernelId, videoExportKernelId, videoPreviewMedia, videoExportMedia
        )
        require(videoFields.all { it == null } || videoFields.all { !it.isNullOrBlank() })
    }

    val stillPreviewExportParityDeclared: Boolean
        get() = previewKernelId == exportKernelId && previewMedia == exportMedia

    val videoPreviewExportParityDeclared: Boolean
        get() = videoPreviewKernelId != null &&
            videoPreviewKernelId == videoExportKernelId &&
            videoPreviewMedia == videoExportMedia
}

data class OfflineEffectPreset(
    val id: String,
    val title: String,
    val description: String,
    val kernel: EffectKernel,
    val parameters: List<EffectParameterSpec>,
    val render: EffectRenderDescriptor,
    val provenance: String = "Original TongCut implementation; no external visual assets"
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,47}")))
        require(title.isNotBlank() && description.isNotBlank() && provenance.isNotBlank())
        require(parameters.map { it.id }.distinct().size == parameters.size)
        require(render.previewKernelId == kernel.id && render.exportKernelId == kernel.id)
        require(render.videoPreviewKernelId == kernel.id &&
            render.videoExportKernelId == kernel.id &&
            render.videoPreviewExportParityDeclared) {
            "Offline look requires an explicitly declared SDR VIDEO preview/export kernel"
        }
    }

    fun defaults(): EffectPresetState =
        EffectPresetState(id, parameters.associate { it.id to it.defaultValue })
}

/** Validated effect selection and values. No editor/project persistence is implied by this type. */
data class EffectPresetState(val presetId: String, val values: Map<String, Float>) {
    init {
        require(presetId.isNotBlank())
        require(values.keys.all { it.matches(Regex("[a-z][a-z0-9_]{0,31}")) })
        require(values.values.all { it.isFinite() })
    }
}

object OfflineEffectPresets {
    private fun strength() = EffectParameterSpec(
        id = "strength", label = "Strength", minimum = 0f, maximum = 1f, defaultValue = 0.65f
    )

    val all: List<OfflineEffectPreset> = listOf(
        OfflineEffectPreset(
            id = "sea-glass",
            title = "Sea Glass",
            description = "A restrained cool lift with a TongCut aqua tint.",
            kernel = EffectKernel.SEA_GLASS,
            parameters = listOf(strength()),
            render = EffectRenderDescriptor(
                EffectKernel.SEA_GLASS.id, EffectKernel.SEA_GLASS.id,
                videoPreviewKernelId = EffectKernel.SEA_GLASS.id,
                videoExportKernelId = EffectKernel.SEA_GLASS.id,
                videoPreviewMedia = "video/SDR/GLES2",
                videoExportMedia = "video/SDR/GLES2"
            )
        ),
        OfflineEffectPreset(
            id = "sun-print",
            title = "Sun Print",
            description = "A warm highlight bias with gently cooled deep tones.",
            kernel = EffectKernel.SUN_PRINT,
            parameters = listOf(strength()),
            render = EffectRenderDescriptor(
                EffectKernel.SUN_PRINT.id, EffectKernel.SUN_PRINT.id,
                videoPreviewKernelId = EffectKernel.SUN_PRINT.id,
                videoExportKernelId = EffectKernel.SUN_PRINT.id,
                videoPreviewMedia = "video/SDR/GLES2",
                videoExportMedia = "video/SDR/GLES2"
            )
        ),
        OfflineEffectPreset(
            id = "paper-mono",
            title = "Paper Mono",
            description = "A soft neutral monochrome with a subtle warm paper cast.",
            kernel = EffectKernel.PAPER_MONO,
            parameters = listOf(strength()),
            render = EffectRenderDescriptor(
                EffectKernel.PAPER_MONO.id, EffectKernel.PAPER_MONO.id,
                videoPreviewKernelId = EffectKernel.PAPER_MONO.id,
                videoExportKernelId = EffectKernel.PAPER_MONO.id,
                videoPreviewMedia = "video/SDR/GLES2",
                videoExportMedia = "video/SDR/GLES2"
            )
        )
    )

    private val byId = all.associateBy(OfflineEffectPreset::id)

    fun find(id: String): OfflineEffectPreset? = byId[id]

    fun validate(state: EffectPresetState): Boolean {
        val preset = byId[state.presetId] ?: return false
        val specs = preset.parameters.associateBy(EffectParameterSpec::id)
        return state.values.keys.all(specs::containsKey) &&
            specs.all { (id, spec) -> state.values[id]?.let(spec::validate) ?: true }
    }

    /** True only when every parameter resolves to its neutral lower bound. */
    fun isNeutral(state: EffectPresetState): Boolean {
        if (!validate(state)) return false
        val preset = requireNotNull(find(state.presetId))
        return preset.parameters.all { spec ->
            (state.values[spec.id] ?: spec.defaultValue) == spec.minimum
        }
    }
}

/** Strict, versioned codec for isolated preset configurations. Unknown keys are rejected. */
object EffectPresetCodec {
    private const val SCHEMA = 1

    fun encode(state: EffectPresetState): String {
        require(OfflineEffectPresets.validate(state)) { "Unknown preset or out-of-range effect parameter" }
        val values = JSONObject()
        state.values.toSortedMap().forEach { (key, value) -> values.put(key, value.toDouble()) }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("presetId", state.presetId)
            .put("values", values)
            .toString()
    }

    fun decode(json: String): EffectPresetState? {
        return try {
        val root = JSONObject(json)
        if (root.optInt("schema", -1) != SCHEMA) return null
        val id = root.optString("presetId", "")
        val preset = OfflineEffectPresets.find(id) ?: return null
        val encodedValues = root.optJSONObject("values") ?: return null
        val values = mutableMapOf<String, Float>()
        val keys = encodedValues.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (preset.parameters.none { it.id == key }) return null
            val value = encodedValues.getDouble(key).toFloat()
            if (!value.isFinite()) return null
            values[key] = value
        }
        EffectPresetState(id, values).takeIf(OfflineEffectPresets::validate)
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Deterministic bounded ARGB_8888 still-image reference implementation. This is deliberately
 * not a video renderer; app preview/export adapters must preserve the descriptor's kernel.
 */
object OfflineEffectRenderer {
    const val MAX_PIXELS = 1_500_000

    fun render(width: Int, height: Int, sourceArgb: IntArray, state: EffectPresetState): IntArray {
        require(width >= 1 && height >= 1)
        require(width.toLong() * height <= MAX_PIXELS) { "Still image exceeds effect renderer bound" }
        require(sourceArgb.size == width * height)
        require(OfflineEffectPresets.validate(state)) { "Unsupported preset or parameter configuration" }
        val preset = requireNotNull(OfflineEffectPresets.find(state.presetId))
        val strength = state.values["strength"] ?: preset.parameters.first().defaultValue
        if (strength == 0f) return sourceArgb.copyOf()

        return IntArray(sourceArgb.size) { index ->
            val pixel = sourceArgb[index]
            val alpha = pixel ushr 24 and 0xff
            val r = (pixel ushr 16 and 0xff) / 255f
            val g = (pixel ushr 8 and 0xff) / 255f
            val b = (pixel and 0xff) / 255f
            var effectR = r
            var effectG = g
            var effectB = b
            when (preset.kernel) {
                EffectKernel.SEA_GLASS -> {
                    effectR = (r * 0.94f).coerceIn(0f, 1f)
                    effectG = (g * 1.025f + 0.008f).coerceIn(0f, 1f)
                    effectB = (b * 1.075f + 0.012f).coerceIn(0f, 1f)
                }
                EffectKernel.SUN_PRINT -> {
                    effectR = (r * 1.055f + 0.012f).coerceIn(0f, 1f)
                    effectG = (g * 1.015f + 0.004f).coerceIn(0f, 1f)
                    effectB = (b * 0.91f).coerceIn(0f, 1f)
                }
                EffectKernel.PAPER_MONO -> {
                    val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
                    effectR = (luminance * 1.015f).coerceIn(0f, 1f)
                    effectG = luminance
                    effectB = (luminance * 0.94f).coerceIn(0f, 1f)
                }
            }
            (alpha shl 24) or (mix(r, effectR, strength) shl 16) or
                (mix(g, effectG, strength) shl 8) or mix(b, effectB, strength)
        }
    }

    private fun mix(original: Float, effect: Float, strength: Float): Int =
        ((original + (effect - original) * strength).coerceIn(0f, 1f) * 255f)
            .toInt().coerceIn(0, 255)
}
