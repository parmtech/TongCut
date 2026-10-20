package au.parmtech.tongcut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TongCutSoundEffectsTest {
    @Test fun generatedWaveIsMonoPcm16WithBoundedKnownDuration() {
        for (effect in TongCutSoundEffect.entries) {
            val wav = TongCutSoundEffectGenerator.wavBytes(effect)
            val sampleFrames = TongCutSoundEffectGenerator.SAMPLE_RATE * effect.durationMs / 1_000L
            assertEquals(44L + sampleFrames * 2L, wav.size.toLong())
            assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            assertEquals("fmt ", wav.copyOfRange(12, 16).toString(Charsets.US_ASCII))
            assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
            assertEquals(1, unsignedShort(wav, 20)) // PCM format
            assertEquals(1, unsignedShort(wav, 22)) // mono
            assertEquals(16, unsignedShort(wav, 34))
            assertTrue(wav.size <= 512 * 1024)
        }
    }

    @Test fun effectGenerationIsDeterministicAndEachOriginalPresetDiffers() {
        val tick = TongCutSoundEffectGenerator.wavBytes(TongCutSoundEffect.TICK)
        assertArrayEquals(tick, TongCutSoundEffectGenerator.wavBytes(TongCutSoundEffect.TICK))
        assertNotEquals(tick.toList(),
            TongCutSoundEffectGenerator.wavBytes(TongCutSoundEffect.SWIPE).toList())
        assertNotEquals(tick.toList(),
            TongCutSoundEffectGenerator.wavBytes(TongCutSoundEffect.RISE).toList())
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
