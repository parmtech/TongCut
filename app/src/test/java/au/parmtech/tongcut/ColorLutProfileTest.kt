package au.parmtech.tongcut

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ColorLutProfileTest {
    @Test fun originalTongCutPresetsAreBoundedDistinctAndAdjustable() {
        val presets = ColorLutPresets.all
        assertEquals(listOf("TongCut Amber", "TongCut Tidal"), presets.map { it.name })
        assertTrue(presets.all { it.size == 17 && it.entriesArgb.size == 17 * 17 * 17 })
        assertNotEquals(presets[0].entriesArgb, presets[1].entriesArgb)
        assertNotEquals(presets[0].toCube().contentDeepToString(),
            presets[0].copy(strength = 0.35f).toCube().contentDeepToString())
    }

    @Test fun parsesRedFastestIdentityCubeIntoMedia3RgbOrder() {
        val identity = """
            TITLE "Identity check"
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()

        val profile = CubeLutParser.parse("identity.cube", identity)
        val cube = profile.toCube()
        assertEquals("Identity check", profile.name)
        assertEquals(2, profile.size)
        assertEquals(0xff000000.toInt(), cube[0][0][0])
        assertEquals(0xffff0000.toInt(), cube[1][0][0])
        assertEquals(0xff00ff00.toInt(), cube[0][1][0])
        assertEquals(0xff0000ff.toInt(), cube[0][0][1])
        assertEquals(0xffffffff.toInt(), cube[1][1][1])
    }

    @Test fun domainDoesNotRemapLutOutputAndStrengthBlendsWithIdentity() {
        val raw = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 16 16 16
            DOMAIN_MAX 235 235 235
            0.5 0.5 0.5
            0.5 0.5 0.5
            0.5 0.5 0.5
            0.5 0.5 0.5
            0.5 0.5 0.5
            0.5 0.5 0.5
            0.5 0.5 0.5
            0.5 0.5 0.5
        """.trimIndent()
        val parsed = CubeLutParser.parse("range.cube", raw)
        val half = parsed.copy(strength = 0.5f).toCube()
        assertEquals(0xff808080.toInt(), parsed.entriesArgb.first())
        assertEquals(0xff404040.toInt(), half[0][0][0])
        assertEquals(0xffc0c0c0.toInt(), half[1][1][1])
    }

    @Test fun nonDefaultInputDomainResamplesNonlinearCubeOntoCanonicalGrid() {
        val rows = buildString {
            appendLine("LUT_3D_SIZE 3")
            appendLine("DOMAIN_MIN 0.1 0.1 0.1")
            appendLine("DOMAIN_MAX 0.6 0.6 0.6")
            for (blue in 0..2) for (green in 0..2) for (red in 0..2) {
                val redValue = red.toFloat() / 2f
                val greenValue = green.toFloat() / 2f
                val blueValue = blue.toFloat() / 2f
                appendLine("${redValue * redValue} $greenValue $blueValue")
            }
        }
        val profile = CubeLutParser.parse("nonlinear.cube", rows)
        // Canonical input red=0.5 maps to source red=0.8; linear sampling between
        // the source table's 0.5→0.25 and 1.0→1.0 entries gives output≈0.7.
        assertEquals(179, (profile.entriesArgb[1] ushr 16) and 0xff)
    }

    @Test fun rejectsUnsafeOrAmbiguousCubeFiles() {
        val wrongCount = "LUT_3D_SIZE 2\n0 0 0"
        assertFailure("Expected 8 LUT colors") {
            CubeLutParser.parse("short.cube", wrongCount)
        }
        assertFailure("1D LUTs are not supported") {
            CubeLutParser.parse("mixed.cube", "LUT_1D_SIZE 4")
        }
        assertFailure("between 2 and 33") {
            CubeLutParser.parse("huge.cube", "LUT_3D_SIZE 34")
        }
        assertFailure("exceeds 2 MB") {
            CubeLutParser.parse("large.cube", " ".repeat(ColorLutProfile.MAX_TEXT_CHARS + 1))
        }
    }

    @Test fun projectLutCodecRoundTripsAndSafelyRejectsMalformedPayload() {
        val profile = CubeLutParser.parse("warm.cube", """
            TITLE "Warm"
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()).copy(strength = 0.63f)
        val encoded = ColorLutProfileCodec.encode(profile)
        assertEquals(profile, ColorLutProfileCodec.decode(JSONObject(encoded.toString())))
        assertNull(ColorLutProfileCodec.decode(JSONObject().put("size", 17)))
        assertNull(ColorLutProfileCodec.decode(null))
    }

    private fun assertFailure(messagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected LUT rejection containing: $messagePart")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(messagePart))
        }
    }
}
