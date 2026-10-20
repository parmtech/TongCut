package au.parmtech.tongcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionLookTest {
    @Test fun everyLookUsesValidStyleAndHasAnOriginalName() {
        assertTrue(CaptionLook.entries.size >= 4)
        assertEquals(CaptionLook.entries.size, CaptionLook.entries.map { it.title }.toSet().size)
        CaptionLook.entries.forEach { look ->
            val style = look.applyTo(CaptionStyle())
            assertTrue(look.matches(style))
            assertTrue(style.size in 0.5f..3f)
        }
    }

    @Test fun applyingLookPreservesCaptionLayoutAndAnimationChoices() {
        val existing = CaptionStyle(
            alignment = CaptionAlignment.RIGHT,
            placement = CaptionPlacement.TOP,
            entrance = CaptionEntrance.RISE
        )

        val updated = CaptionLook.FILM_CARD.applyTo(existing)

        assertEquals(CaptionAlignment.RIGHT, updated.alignment)
        assertEquals(CaptionPlacement.TOP, updated.placement)
        assertEquals(CaptionEntrance.RISE, updated.entrance)
        assertTrue(CaptionLook.FILM_CARD.matches(updated))
    }

    @Test fun customTypographyIsNotMisidentifiedAsOneOfTheLooks() {
        val custom = CaptionStyle(size = 1.07f, shadow = false)
        assertNull(CaptionLook.entries.firstOrNull { it.matches(custom) })
        assertNotNull(CaptionLook.entries.firstOrNull { it.matches(CaptionStyle()) })
        assertFalse(CaptionLook.PEARL_CLASSIC.matches(custom))
    }
}
