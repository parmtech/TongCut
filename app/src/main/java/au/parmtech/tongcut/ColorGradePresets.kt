package au.parmtech.tongcut

/** Original TongCut one-tap looks, expressed in the shared CPU/GPU grade model. */
data class ColorGradePreset(val name: String, val grade: ColorGrade) {
    init {
        require(name.isNotBlank() && name.length <= 32)
        require(grade.curves == ColorCurves()) {
            "Preset strength interpolation supports parametric looks, not custom curves"
        }
    }

    fun gradeAtStrength(strength: Float): ColorGrade {
        require(strength.isFinite() && strength in 0f..1f)
        if (strength == 0f) return ColorGrade()
        if (strength == 1f) return grade

        fun blend(neutral: Float, target: Float) = neutral + (target - neutral) * strength
        val bands = DEFAULT_HSL_BANDS.indices.map { index ->
            val neutral = DEFAULT_HSL_BANDS[index]
            val target = grade.hslBands[index]
            HslBandAdjustment(
                hueDegrees = blend(neutral.hueDegrees, target.hueDegrees),
                saturation = blend(neutral.saturation, target.saturation),
                lightness = blend(neutral.lightness, target.lightness)
            )
        }
        return ColorGrade(
            exposure = blend(0f, grade.exposure),
            contrast = blend(1f, grade.contrast),
            saturation = blend(1f, grade.saturation),
            temperature = blend(0f, grade.temperature),
            tint = blend(0f, grade.tint),
            hueDegrees = blend(0f, grade.hueDegrees),
            hslSaturation = blend(0f, grade.hslSaturation),
            hslLightness = blend(0f, grade.hslLightness),
            hslBands = bands
        )
    }
}

object ColorGradePresets {
    val all: List<ColorGradePreset> = listOf(
        ColorGradePreset("Neutral", ColorGrade()),
        ColorGradePreset("Amber", ColorGrade(
            exposure = 0.12f, contrast = 1.05f, saturation = 1.04f, temperature = 0.24f
        )),
        ColorGradePreset("Tidal", ColorGrade(
            exposure = 0.04f, contrast = 1.08f, saturation = 0.94f,
            temperature = -0.22f, tint = -0.06f
        )),
        ColorGradePreset("Soft Fade", ColorGrade(
            exposure = 0.10f, contrast = 0.88f, saturation = 0.86f, temperature = 0.05f
        )),
        ColorGradePreset("Cobalt Film", ColorGrade(
            exposure = -0.04f, contrast = 1.12f, saturation = 0.86f,
            temperature = -0.32f, tint = -0.05f, hueDegrees = -2f
        )),
        ColorGradePreset("Rose Quartz", ColorGrade(
            exposure = 0.05f, contrast = 0.95f, saturation = 0.92f,
            temperature = 0.08f, tint = 0.26f, hueDegrees = 4f
        )),
        ColorGradePreset("Graphite", ColorGrade(
            exposure = 0.02f, contrast = 1.14f, saturation = 0f,
            hslLightness = 0.02f
        )),
        ColorGradePreset("Apricot Glow", ColorGrade(
            exposure = 0.20f, contrast = 0.90f, saturation = 1.05f,
            temperature = 0.32f, tint = 0.04f, hslLightness = 0.04f
        )),
        ColorGradePreset("Moss Light", ColorGrade(
            exposure = 0.08f, contrast = 0.93f, saturation = 0.82f,
            temperature = -0.08f, tint = -0.18f, hueDegrees = -12f,
            hslSaturation = -0.04f
        )),
        ColorGradePreset("Midnight Teal", ColorGrade(
            exposure = -0.24f, contrast = 1.18f, saturation = 0.94f,
            temperature = -0.24f, tint = -0.12f, hueDegrees = -8f,
            hslSaturation = 0.05f, hslLightness = -0.03f
        ))
    )
}
