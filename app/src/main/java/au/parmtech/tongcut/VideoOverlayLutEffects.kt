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

/**
 * Builds the VIDEO-overlay LUT chain around Media3's SDR LUT effect.
 *
 * TongCut's video matte shader emits premultiplied RGB. Media3's color-LUT shader
 * samples RGB as-is and copies alpha unchanged, so feed it straight RGB and restore
 * premultiplication afterward. This keeps transparent matte edges from being
 * interpreted as darker source colors and preserves the matte alpha.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal object VideoOverlayLutEffects {
    fun forLayer(
        projectProfile: ColorLutProfile?,
        settings: PhotoEffects
    ): List<GlEffect> {
        if (!settings.applyColorLut) return emptyList()
        val profile = projectProfile ?: return emptyList()
        val effectiveStrength =
            (profile.strength * settings.colorLutStrength).coerceIn(0f, 1f)
        if (effectiveStrength <= 0f) return emptyList()

        val effectiveProfile = profile.copy(strength = effectiveStrength)
        return listOf(
            VideoOverlayLutAlphaEffect(premultiply = false),
            effectiveProfile.toMedia3Effect(),
            VideoOverlayLutAlphaEffect(premultiply = true)
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class VideoOverlayLutAlphaEffect(private val premultiply: Boolean) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) {
            throw VideoFrameProcessingException(
                IllegalArgumentException("VIDEO overlay LUTs currently support SDR input only")
            )
        }
        return AlphaConventionShader(premultiply)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class AlphaConventionShader(private val premultiply: Boolean) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ false, /* texturePoolCapacity = */ 1) {
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
        program.setFloatUniform("uPremultiply", if (premultiply) 1f else 0f)
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
            uniform float uPremultiply;
            varying highp vec2 vTexSamplingCoord;
            void main() {
                vec4 inputColor = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 rgb = inputColor.rgb;
                if (uPremultiply > 0.5) {
                    rgb *= inputColor.a;
                } else if (inputColor.a > 0.00001) {
                    rgb /= inputColor.a;
                } else {
                    rgb = vec3(0.0);
                }
                gl_FragColor = vec4(rgb, inputColor.a);
            }
        """
    }
}
