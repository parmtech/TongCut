package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorGradePresetsTest {
    @Test fun presetsAreNamedBoundedAndIncludeNeutralDefault() {
        assertEquals(
            listOf(
                "Neutral", "Amber", "Tidal", "Soft Fade", "Cobalt Film",
                "Rose Quartz", "Graphite", "Apricot Glow", "Moss Light", "Midnight Teal"
            ),
            ColorGradePresets.all.map { it.name }
        )
        assertEquals(ColorGrade(), ColorGradePresets.all.first().grade)
        assertEquals(ColorGradePresets.all.size,
            ColorGradePresets.all.map { it.name }.toSet().size)
        ColorGradePresets.all.forEach { preset ->
            assertTrue(preset.grade.exposure in -2f..2f)
            assertTrue(preset.grade.contrast in 0f..2f)
            assertTrue(preset.grade.saturation in 0f..2f)
            assertTrue(preset.grade.temperature in -1f..1f)
            assertTrue(preset.grade.tint in -1f..1f)
        }
    }

    @Test fun looksAreDistinctAndNeutralIsPixelIdentity() {
        assertNotEquals(ColorGradePresets.all[1].grade, ColorGradePresets.all[2].grade)
        val pixel = 0x7F4080C0
        assertEquals(pixel, ColorGradePresets.all.first().grade.apply(pixel))
    }
}
