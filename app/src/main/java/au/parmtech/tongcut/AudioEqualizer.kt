package au.parmtech.tongcut

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** User-authored three-band peaking EQ. Values are bounded decibels, neutral at zero. */
data class AudioEqualizer(
    val bassDb: Float = 0f,
    val midDb: Float = 0f,
    val trebleDb: Float = 0f
) {
    init {
        require(bassDb.isFinite() && bassDb in -12f..12f)
        require(midDb.isFinite() && midDb in -12f..12f)
        require(trebleDb.isFinite() && trebleDb in -12f..12f)
    }

    val isNeutral: Boolean get() = bassDb == 0f && midDb == 0f && trebleDb == 0f
}

/** Small RBJ peaking-filter chain, shared by Media3 preview and Transformer PCM export. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioEqualizerProcessor(
    private val settings: AudioEqualizer,
    private val stereoBalance: Float = 0f,
    private val monoFoldDown: Boolean = false,
    private val rumbleReduction: Boolean = false
) : BaseAudioProcessor() {
    private data class Coefficients(val b0: Float, val b1: Float, val b2: Float,
                                   val a1: Float, val a2: Float)
    private lateinit var coefficients: List<Coefficients>
    private var z1 = FloatArray(0)
    private var z2 = FloatArray(0)
    private var rumbleCoefficients: Coefficients? = null
    private var rumbleZ1 = FloatArray(0)
    private var rumbleZ2 = FloatArray(0)
    private var channels = 0
    private var inputChannels = 0
    private var frameSamples = FloatArray(0)
    // The separated peaking bands do not stack their full gain at one frequency.
    // Reserve headroom for the strongest band, then contain residual inter-band
    // overshoot with the output limiter instead of attenuating by all three gains.
    private val headroom = 10.0.pow(-maxOf(
        settings.bassDb, settings.midDb, settings.trebleDb, 0f
    ).toDouble() / 20.0).toFloat()

    init {
        require(stereoBalance.isFinite() && stereoBalance in -1f..1f)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat)
        : AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputChannels = inputAudioFormat.channelCount
        require(inputChannels in 1..8 && inputAudioFormat.sampleRate in 8_000..192_000)
        channels = if (monoFoldDown && inputChannels == 2) 1 else inputChannels
        frameSamples = FloatArray(channels)
        val sampleRate = inputAudioFormat.sampleRate.toFloat()
        rumbleCoefficients = if (rumbleReduction) highPass(sampleRate, 80f) else null
        rumbleZ1 = FloatArray(channels)
        rumbleZ2 = FloatArray(channels)
        coefficients = listOf(
            peaking(sampleRate, 100f, settings.bassDb),
            peaking(sampleRate, 1_000f, settings.midDb),
            peaking(sampleRate, 8_000f.coerceAtMost(sampleRate * 0.42f), settings.trebleDb)
        )
        z1 = FloatArray(coefficients.size * channels)
        z2 = FloatArray(coefficients.size * channels)
        return if (channels == inputChannels) inputAudioFormat else
            AudioProcessor.AudioFormat(sampleRate.toInt(), channels, inputAudioFormat.encoding)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        val sampleBytes = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val inputFrameBytes = inputChannels * sampleBytes
        require(bytes % inputFrameBytes == 0) { "PCM buffer splits an interleaved sample frame" }
        val outputBytes = bytes / inputFrameBytes * channels * sampleBytes
        val output = replaceOutputBuffer(outputBytes).order(ByteOrder.nativeOrder())
        val input = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        while (input.hasRemaining()) {
            if (channels == 1 && inputChannels == 2) {
                val left = if (sampleBytes == 4) input.getFloat()
                    else input.getShort().toFloat() / 32768f
                val right = if (sampleBytes == 4) input.getFloat()
                    else input.getShort().toFloat() / 32768f
                frameSamples[0] = (left + right) * 0.5f
            } else {
                for (channel in 0 until inputChannels) {
                    frameSamples[channel] = if (sampleBytes == 4) input.getFloat()
                        else input.getShort().toFloat() / 32768f
                }
            }
            for (channel in 0 until channels) {
                var value = frameSamples[channel]
                rumbleCoefficients?.let { c ->
                    val next = c.b0 * value + rumbleZ1[channel]
                    rumbleZ1[channel] = c.b1 * value - c.a1 * next + rumbleZ2[channel]
                    rumbleZ2[channel] = c.b2 * value - c.a2 * next
                    value = next
                }
                for (band in coefficients.indices) {
                    val state = band * channels + channel
                    val c = coefficients[band]
                    val next = c.b0 * value + z1[state]
                    z1[state] = c.b1 * value - c.a1 * next + z2[state]
                    z2[state] = c.b2 * value - c.a2 * next
                    value = next
                }
                val balanceGain = when {
                    channels >= 2 && channel == 0 && stereoBalance > 0f -> 1f - stereoBalance
                    channels >= 2 && channel == 1 && stereoBalance < 0f -> 1f + stereoBalance
                    else -> 1f
                }
                value = (value * headroom * balanceGain).coerceIn(-1f, 1f)
                if (sampleBytes == 4) output.putFloat(value)
                else output.putShort((value * 32768f).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        z1.fill(0f)
        z2.fill(0f)
        rumbleZ1.fill(0f)
        rumbleZ2.fill(0f)
    }

    override fun onReset() {
        coefficients = emptyList()
        z1 = FloatArray(0)
        z2 = FloatArray(0)
        rumbleCoefficients = null
        rumbleZ1 = FloatArray(0)
        rumbleZ2 = FloatArray(0)
        channels = 0
        inputChannels = 0
        frameSamples = FloatArray(0)
    }

    private fun highPass(sampleRate: Float, frequency: Float): Coefficients {
        val f = frequency.coerceIn(10f, sampleRate * 0.20f)
        val omega = (2.0 * PI * f / sampleRate).toFloat()
        val cosOmega = cos(omega)
        val alpha = sin(omega) / (2f * 0.70710677f)
        val a0 = 1f + alpha
        return Coefficients(
            (1f + cosOmega) / (2f * a0),
            -(1f + cosOmega) / a0,
            (1f + cosOmega) / (2f * a0),
            (-2f * cosOmega) / a0,
            (1f - alpha) / a0
        )
    }

    private fun peaking(sampleRate: Float, frequency: Float, gainDb: Float): Coefficients {
        if (gainDb == 0f) return Coefficients(1f, 0f, 0f, 0f, 0f)
        val f = frequency.coerceIn(20f, sampleRate * 0.45f)
        val a = 10.0.pow(gainDb.toDouble() / 40.0).toFloat()
        val omega = (2.0 * PI * f / sampleRate).toFloat()
        val alpha = sin(omega) / (2f * 0.70710677f)
        val cosOmega = cos(omega)
        val a0 = 1f + alpha / a
        return Coefficients(
            (1f + alpha * a) / a0,
            (-2f * cosOmega) / a0,
            (1f - alpha * a) / a0,
            (-2f * cosOmega) / a0,
            (1f - alpha / a) / a0
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class AudioEqualizerRenderersFactory(
    context: Context,
    private val settings: AudioEqualizer,
    private val stereoBalance: Float = 0f,
    private val monoFoldDown: Boolean = false,
    private val rumbleReduction: Boolean = false
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
        .setAudioProcessors(if (settings.isNeutral && stereoBalance == 0f &&
            !monoFoldDown && !rumbleReduction)
            emptyArray<AudioProcessor>()
            else arrayOf(AudioEqualizerProcessor(
                settings, stereoBalance, monoFoldDown, rumbleReduction)))
        .build()
}
