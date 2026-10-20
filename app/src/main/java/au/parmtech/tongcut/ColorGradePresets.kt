package au.parmtech.tongcut

/** Original TongCut one-tap looks, expressed in the shared CPU/GPU grade model. */
data class ColorGradePreset(val name: String, val grade: ColorGrade) {
    init { require(name.isNotBlank() && name.length <= 32) }
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
        ))
    )
}
