package au.parmtech.tongcut

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import au.parmtech.tongcut.effects.presets.EffectKernel
import au.parmtech.tongcut.effects.presets.EffectPresetState
import au.parmtech.tongcut.effects.presets.OfflineEffectPresets

/**
 * A15 VIDEO PiP look kernel. Input/output RGB is premultiplied at this point in
 * both preview and export chains; process straight RGB, then restore premultiplication.
 * The IMAGE CPU implementation is the reference; GLES rounding is not byte-identical.
 * HDR is rejected until a color-managed kernel is validated.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoOfflineEffectPreset(state: EffectPresetState) : GlEffect {
    private val kernel = requireNotNull(
        OfflineEffectPresets.find(state.presetId)
    ) { "Unsupported TongCut offline look" }.kernel
    private val strength = state.values["strength"]
        ?: requireNotNull(OfflineEffectPresets.find(state.presetId))
            .parameters.first().defaultValue

    init {
        require(OfflineEffectPresets.validate(state)) {
            "Invalid TongCut offline look parameters"
        }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException(
            IllegalArgumentException("TongCut VIDEO looks currently support SDR footage only")
        )
        return OfflineLookShader(kernel, strength)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class OfflineLookShader(
    kernel: EffectKernel,
    strength: Float
) : BaseGlShaderProgram(false, 1) {
    private val program = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (error: GlUtil.GlException) {
        throw VideoFrameProcessingException(error)
    }

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        program.setFloatUniform("uKernel", when (kernel) {
            EffectKernel.SEA_GLASS -> 0f
            EffectKernel.SUN_PRINT -> 1f
            EffectKernel.PAPER_MONO -> 2f
        })
        program.setFloatUniform("uStrength", strength)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        require(inputWidth > 0 && inputHeight > 0)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            program.delete()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        } finally {
            super.release()
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            varying highp vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform float uKernel;
            uniform float uStrength;
            varying highp vec2 vTexSamplingCoord;
            void main() {
                vec4 sampleColor = texture2D(uTexSampler, vTexSamplingCoord);
                float alpha = sampleColor.a;
                if (alpha <= 0.000001) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, alpha);
                    return;
                }
                vec3 original = clamp(sampleColor.rgb / alpha, 0.0, 1.0);
                vec3 look = original;
                if (uKernel < 0.5) {
                    look = clamp(vec3(
                        original.r * 0.94,
                        original.g * 1.025 + 0.008,
                        original.b * 1.075 + 0.012
                    ), 0.0, 1.0);
                } else if (uKernel < 1.5) {
                    look = clamp(vec3(
                        original.r * 1.055 + 0.012,
                        original.g * 1.015 + 0.004,
                        original.b * 0.91
                    ), 0.0, 1.0);
                } else {
                    float luminance = clamp(
                        dot(original, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0);
                    look = clamp(vec3(luminance * 1.015, luminance, luminance * 0.94),
                        0.0, 1.0);
                }
                vec3 result = mix(original, look, uStrength);
                gl_FragColor = vec4(result * alpha, alpha);
            }
        """
    }
}
