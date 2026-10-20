package au.parmtech.tongcut

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** TongCut-owned offline tones. These are synthesized, not bundled third-party recordings. */
enum class TongCutSoundEffect(
    val title: String,
    val fileName: String,
    val durationMs: Long,
    internal val seed: Long
) {
    TICK("Tick", "tick.wav", 180L, 0x5449434BL),
    SWIPE("Swipe", "swipe.wav", 760L, 0x5357495045L),
    RISE("Rise", "rise.wav", 1_600L, 0x52495345L)
}

data class GeneratedSoundEffect(val file: File, val durationMs: Long)

/** Bounded deterministic PCM generator for a small, reusable offline SFX shelf. */
object TongCutSoundEffectGenerator {
    const val SAMPLE_RATE = 44_100
    private const val CHANNEL_COUNT = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val MAX_WAV_BYTES = 512 * 1024

    @Synchronized
    fun writeTo(filesDir: File, effect: TongCutSoundEffect): GeneratedSoundEffect {
        val directory = File(filesDir, "tongcut-sfx")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create TongCut sound-effects folder")
        }
        val target = File(directory, effect.fileName)
        val expectedBytes = wavSize(effect)
        if (target.isFile && target.length() == expectedBytes.toLong() && hasWaveHeader(target)) {
            return GeneratedSoundEffect(target, effect.durationMs)
        }

        val temporary = File.createTempFile("sfx_", ".tmp", directory)
        try {
            val bytes = wavBytes(effect)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Unable to replace the cached TongCut sound effect")
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Unable to publish the generated TongCut sound effect")
            }
            return GeneratedSoundEffect(target, effect.durationMs)
        } finally {
            temporary.delete()
        }
    }

    /** Internal deterministic representation used by JVM regression tests. */
    internal fun wavBytes(effect: TongCutSoundEffect): ByteArray {
        val frameCount = frameCount(effect)
        val dataBytes = frameCount * BYTES_PER_SAMPLE
        val totalBytes = 44 + dataBytes
        val output = ByteArray(totalBytes)
        writeAscii(output, 0, "RIFF")
        writeIntLe(output, 4, totalBytes - 8)
        writeAscii(output, 8, "WAVE")
        writeAscii(output, 12, "fmt ")
        writeIntLe(output, 16, 16)
        writeShortLe(output, 20, 1) // PCM
        writeShortLe(output, 22, CHANNEL_COUNT)
        writeIntLe(output, 24, SAMPLE_RATE)
        writeIntLe(output, 28, SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE)
        writeShortLe(output, 32, CHANNEL_COUNT * BYTES_PER_SAMPLE)
        writeShortLe(output, 34, BYTES_PER_SAMPLE * 8)
        writeAscii(output, 36, "data")
        writeIntLe(output, 40, dataBytes)

        val random = Random(effect.seed)
        var filteredNoise = 0.0
        val durationSeconds = effect.durationMs / 1_000.0
        for (frame in 0 until frameCount) {
            val time = frame.toDouble() / SAMPLE_RATE
            val position = frame.toDouble() / (frameCount - 1).coerceAtLeast(1)
            val sample = when (effect) {
                TongCutSoundEffect.TICK -> {
                    val noise = random.nextDouble() * 2.0 - 1.0
                    val transient = 0.66 * sin(2.0 * PI * 1_650.0 * time) +
                        0.24 * sin(2.0 * PI * 2_750.0 * time) + 0.10 * noise
                    transient * exp(-time * 27.0)
                }
                TongCutSoundEffect.SWIPE -> {
                    val noise = random.nextDouble() * 2.0 - 1.0
                    filteredNoise += 0.18 * (noise - filteredNoise)
                    filteredNoise * sin(PI * position).pow(1.35) * 2.0
                }
                TongCutSoundEffect.RISE -> {
                    val frequencySlope = 640.0 / durationSeconds
                    val phase = 2.0 * PI * (145.0 * time + 0.5 * frequencySlope * time * time)
                    sin(phase) * sin(PI * position) * 0.68
                }
            }
            val pcm = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt()
            val offset = 44 + frame * BYTES_PER_SAMPLE
            output[offset] = (pcm and 0xFF).toByte()
            output[offset + 1] = ((pcm ushr 8) and 0xFF).toByte()
        }
        return output
    }

    private fun wavSize(effect: TongCutSoundEffect): Int =
        44 + frameCount(effect) * BYTES_PER_SAMPLE

    private fun frameCount(effect: TongCutSoundEffect): Int {
        val frames = SAMPLE_RATE.toLong() * effect.durationMs / 1_000L
        require(frames in 1L..((MAX_WAV_BYTES - 44) / BYTES_PER_SAMPLE).toLong()) {
            "Generated sound effect exceeds the size limit"
        }
        return frames.toInt()
    }

    private fun hasWaveHeader(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(12)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count <= 0) break
                offset += count
            }
            offset == header.size &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    }.getOrDefault(false)

    private fun writeAscii(output: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(output, offset)
    }

    private fun writeIntLe(output: ByteArray, offset: Int, value: Int) {
        for (index in 0..3) output[offset + index] = (value ushr (index * 8)).toByte()
    }

    private fun writeShortLe(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
