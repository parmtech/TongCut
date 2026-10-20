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

/** Final-stage vignette that preserves premultiplied alpha from prior effects. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoVignetteEffect(private val amount: Float) : GlEffect {
    init { require(amount.isFinite() && amount in 0f..1f) }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException(
            IllegalArgumentException("TongCut vignette currently requires SDR footage")
        )
        return VignetteShader(amount)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class VignetteShader(amount: Float) : BaseGlShaderProgram(false, 1) {
    private val program = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (error: GlUtil.GlException) {
        throw VideoFrameProcessingException(error)
    }

    init {
        program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.setFloatUniform("uAmount", amount)
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
            uniform float uAmount;
            varying vec2 vTexSamplingCoord;
            void main() {
                vec4 pixel = texture2D(uTexSampler, vTexSamplingCoord);
                vec2 point = vTexSamplingCoord * 2.0 - 1.0;
                float radius = length(point) * 0.70710678;
                float falloff = smoothstep(0.25, 1.0, radius) * uAmount * 0.65;
                gl_FragColor = vec4(pixel.rgb * (1.0 - falloff), pixel.a);
            }
        """
    }
}
