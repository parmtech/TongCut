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

/** Same bounded SDR color transform for the ExoPlayer preview and Transformer output. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ColorGradeEffect(private val grade: ColorGrade) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException(
            IllegalArgumentException("TongCut color adjustments currently require SDR footage")
        )
        return ColorGradeShader(grade)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class ColorGradeShader(grade: ColorGrade) : BaseGlShaderProgram(false, 1) {
    private val program = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (error: GlUtil.GlException) {
        throw VideoFrameProcessingException(error)
    }

    init {
        program.setBufferAttribute(
            "aFramePosition", GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        program.setFloatUniform("uExposure", grade.exposure)
        program.setFloatUniform("uContrast", grade.contrast)
        program.setFloatUniform("uSaturation", grade.saturation)
        program.setFloatUniform("uTemperature", grade.temperature)
        program.setFloatUniform("uTint", grade.tint)
        program.setFloatUniform("uHueDegrees", grade.hueDegrees)
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
            uniform float uExposure;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uHueDegrees;
            varying vec2 vTexSamplingCoord;
            vec3 shiftHue(vec3 color, float degrees) {
                float high = max(color.r, max(color.g, color.b));
                float low = min(color.r, min(color.g, color.b));
                float chroma = high - low;
                if (chroma <= 0.000001 || degrees == 0.0) return color;
                float hue;
                if (high == color.r) hue = mod((color.g - color.b) / chroma, 6.0);
                else if (high == color.g) hue = (color.b - color.r) / chroma + 2.0;
                else hue = (color.r - color.g) / chroma + 4.0;
                hue = mod(hue + degrees / 60.0, 6.0);
                float secondary = chroma * (1.0 - abs(mod(hue, 2.0) - 1.0));
                float offset = high - chroma;
                if (hue < 1.0) return vec3(high, secondary + offset, offset);
                if (hue < 2.0) return vec3(secondary + offset, high, offset);
                if (hue < 3.0) return vec3(offset, high, secondary + offset);
                if (hue < 4.0) return vec3(offset, secondary + offset, high);
                if (hue < 5.0) return vec3(secondary + offset, offset, high);
                return vec3(high, offset, secondary + offset);
            }
            void main() {
                vec4 source = texture2D(uTexSampler, vTexSamplingCoord);
                vec3 color = source.rgb * exp2(uExposure);
                color = (color - vec3(0.5)) * uContrast + vec3(0.5);
                float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
                color = mix(vec3(luminance), color, uSaturation);
                color.r += uTemperature * 0.10;
                color.b -= uTemperature * 0.10;
                color.r += uTint * 0.05;
                color.g -= uTint * 0.10;
                color.b += uTint * 0.05;
                color = shiftHue(color, uHueDegrees);
                gl_FragColor = vec4(clamp(color, 0.0, 1.0), source.a);
            }
        """
    }
}
