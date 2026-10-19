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
import kotlin.math.min

/**
 * A P3 VIDEO PiP alpha-matte applied *before* Media3's multi-input compositor.
 * Based on the same physical-pixel mask and RGB Euclidean key math as PhotoMatte.
 * HDR is explicitly unsupported until a colour-managed HDR shader is validated.
 *
 * This path is export-only until a matching ExoPlayer GL preview is implemented:
 * the editor surfaces a preview limitation so users do not assume parity.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoMatteEffect(private val settings: PhotoEffects) : GlEffect {
    override fun toGlShaderProgram(
        context: Context, useHdr: Boolean
    ): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException(
            IllegalArgumentException("Video chroma masks require SDR source footage")
        )
        return MatteShader(settings)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class MatteShader(
    settings: PhotoEffects
) : BaseGlShaderProgram(false, 1) {
    private val program: GlProgram = try {
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
        val chroma = settings.chroma
        val key = chroma?.keyArgb ?: 0xFF00FF00.toInt()
        program.setFloatUniform("uMaskMode", settings.mask.ordinal.toFloat())
        program.setFloatUniform("uMaskFeather", settings.feather)
        program.setFloatUniform("uUseChroma", if (chroma == null) 0f else 1f)
        program.setFloatsUniform("uKeyColor", floatArrayOf(
            ((key ushr 16) and 255) / 255f,
            ((key ushr 8) and 255) / 255f,
            (key and 255) / 255f
        ))
        program.setFloatUniform("uTolerance", chroma?.tolerance ?: 0.18f)
        program.setFloatUniform("uKeyFeather", chroma?.feather ?: 0.12f)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        require(inputWidth > 0 && inputHeight > 0)
        program.setFloatsUniform(
            "uFrameSize", floatArrayOf(inputWidth.toFloat(), inputHeight.toFloat())
        )
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
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        const val FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec2 uFrameSize;
            uniform float uMaskMode;
            uniform float uMaskFeather;
            uniform float uUseChroma;
            uniform vec3 uKeyColor;
            uniform float uTolerance;
            uniform float uKeyFeather;
            varying vec2 vTexSamplingCoord;

            float edge(float lo, float hi, float value) {
                return smoothstep(lo, max(lo + 0.00001, hi), value);
            }
            void main() {
                vec4 pixel = texture2D(uTexSampler, vTexSamplingCoord);
                vec2 point = (vTexSamplingCoord - vec2(0.5)) * uFrameSize;
                float shortSide = min(uFrameSize.x, uFrameSize.y);
                float blur = shortSide * uMaskFeather;
                float matte = 1.0;
                // PhotoMask: NONE=0, CIRCLE=1, ROUNDED_RECT=2, LINEAR_WIPE=3.
                if (uMaskMode > 0.5 && uMaskMode < 1.5) {
                    float inside = shortSide * 0.48 - length(point);
                    matte = edge(-blur * 0.5, blur * 0.5, inside);
                } else if (uMaskMode > 1.5 && uMaskMode < 2.5) {
                    float radius = shortSide * 0.19;
                    vec2 q = abs(point) - (uFrameSize * 0.5 - vec2(radius));
                    float signedDistance = length(max(q, vec2(0.0)))
                        + min(max(q.x, q.y), 0.0) - radius;
                    matte = edge(-blur * 0.5, blur * 0.5, -signedDistance);
                } else if (uMaskMode > 2.5) {
                    float inside = uFrameSize.x * 0.5 -
                        (vTexSamplingCoord.x * uFrameSize.x);
                    matte = edge(-blur * 0.5, blur * 0.5, inside);
                }
                if (uUseChroma > 0.5) {
                    // Normalize by sqrt(3) as in PhotoMatte.chromaAlpha().
                    float difference = length(pixel.rgb - uKeyColor) * 0.57735026919;
                    matte *= edge(uTolerance, uTolerance + uKeyFeather, difference);
                }
                gl_FragColor = vec4(pixel.rgb, pixel.a * matte);
            }
        """
    }
}
