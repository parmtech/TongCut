package au.parmtech.tongcut.effects.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineEffectPresetsTest {
    @Test
    fun catalogHasUniqueOriginalPresetsAndMatchedStillRenderDescriptors() {
        assertEquals(OfflineEffectPresets.all.size, OfflineEffectPresets.all.map { it.id }.distinct().size)
        OfflineEffectPresets.all.forEach { preset ->
            assertTrue(preset.provenance.contains("Original TongCut"))
            assertTrue(preset.render.stillPreviewExportParityDeclared)
            assertEquals(preset.kernel.id, preset.render.previewKernelId)
            assertEquals(preset.kernel.id, preset.render.exportKernelId)
            assertTrue(OfflineEffectPresets.validate(preset.defaults()))
        }
    }

    @Test
    fun configurationRoundTripsAndRejectsUnknownOrOutOfRangeData() {
        val config = OfflineEffectPresets.find("sun-print")!!.defaults()
            .copy(values = mapOf("strength" to 0.37f))
        val decoded = EffectPresetCodec.decode(EffectPresetCodec.encode(config))
        assertEquals(config, decoded)

        assertNull(EffectPresetCodec.decode("""{"schema":99,"presetId":"sun-print","values":{}}"""))
        assertNull(EffectPresetCodec.decode("""{"schema":1,"presetId":"missing","values":{}}"""))
        assertNull(EffectPresetCodec.decode("""{"schema":1,"presetId":"sun-print","values":{"unknown":0.2}}"""))
        assertNull(EffectPresetCodec.decode("""{"schema":1,"presetId":"sun-print","values":{"strength":1.1}}"""))
    }

    @Test
    fun strengthZeroIsIdentityAndReturnsIndependentPixels() {
        val source = intArrayOf(0xff336699.toInt(), 0x8055aa33.toInt(), 0x00010203)
        for (preset in OfflineEffectPresets.all) {
            val zeroStrength = preset.defaults().copy(values = mapOf("strength" to 0f))
            val output = OfflineEffectRenderer.render(source.size, 1, source, zeroStrength)
            assertNotSame(source, output)
            assertTrue(source.contentEquals(output))
        }
    }

    @Test
    fun eachPresetChangesColorButPreservesEveryAlphaByte() {
        val source = intArrayOf(
            0xff336699.toInt(), 0x8055aa33.toInt(), 0x401010e0, 0x00010203
        )
        for (preset in OfflineEffectPresets.all) {
            val output = OfflineEffectRenderer.render(source.size, 1, source, preset.defaults())
            assertFalse(preset.id, source.contentEquals(output))
            for (index in source.indices) {
                assertEquals(source[index] ushr 24, output[index] ushr 24)
            }
        }
    }

    @Test
    fun rendererRejectsOversizedOrUnknownPresetInputBeforeOutputAllocation() {
        val valid = OfflineEffectPresets.find("sea-glass")!!.defaults()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OfflineEffectRenderer.render(2_000, 2_000, intArrayOf(), valid)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OfflineEffectRenderer.render(1, 1, intArrayOf(0xff000000.toInt()), EffectPresetState("unknown", emptyMap()))
        }
    }
}
