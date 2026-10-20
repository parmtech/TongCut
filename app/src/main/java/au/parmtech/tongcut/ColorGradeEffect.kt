package au.parmtech.tongcut

import android.content.Context
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
internal class ColorGradeEffect(
    private val grade: ColorGrade,
    private val premultipliedInput: Boolean = false
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException(
            IllegalArgumentException("TongCut color adjustments currently require SDR footage")
        )
        return ColorGradeShader(grade, premultipliedInput)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class ColorGradeShader(grade: ColorGrade, premultipliedInput: Boolean) :
    BaseGlShaderProgram(false, 1) {
    private val program = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (error: GlUtil.GlException) {
        throw VideoFrameProcessingException(error)
    }
    private val curveTextureId = IntArray(1)

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
        program.setFloatUniform("uHslSaturation", grade.hslSaturation)
        program.setFloatUniform("uHslLightness", grade.hslLightness)
        program.setFloatUniform("uPremultipliedInput", if (premultipliedInput) 1f else 0f)
        program.setFloatUniform("uUseCurves", if (grade.curves == ColorCurves()) 0f else 1f)
        grade.hslBands.forEachIndexed { index, band ->
            program.setFloatsUniform("uHslBand$index",
                floatArrayOf(band.hueDegrees, band.saturation, band.lightness))
        }
        GLES20.glGenTextures(1, curveTextureId, 0)
        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTextureId[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE)
            val curvePixels = ByteBuffer.allocateDirect(256 * 4)
                .order(ByteOrder.nativeOrder()).put(grade.curves.rgbaLut()).apply { position(0) }
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, curvePixels)
            GlUtil.checkGlError()
        } catch (failure: Throwable) {
            if (curveTextureId[0] != 0) {
                GLES20.glDeleteTextures(1, curveTextureId, 0)
                curveTextureId[0] = 0
            }
            throw failure
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        require(inputWidth > 0 && inputHeight > 0)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setSamplerTexIdUniform("uCurveLut", curveTextureId[0], 1)
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
            if (curveTextureId[0] != 0) GLES20.glDeleteTextures(1, curveTextureId, 0)
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
            uniform sampler2D uCurveLut;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uHueDegrees;
            uniform float uHslSaturation;
            uniform float uHslLightness;
            uniform float uPremultipliedInput;
            uniform float uUseCurves;
            uniform vec3 uHslBand0;
            uniform vec3 uHslBand1;
            uniform vec3 uHslBand2;
            uniform vec3 uHslBand3;
            uniform vec3 uHslBand4;
            uniform vec3 uHslBand5;
            varying vec2 vTexSamplingCoord;
            vec3 adjustHsl(vec3 color, float degrees, float saturationShift,
                float lightnessShift) {
                if (abs(degrees) <= 0.000001 && abs(saturationShift) <= 0.000001 &&
                    abs(lightnessShift) <= 0.000001)
                    return color;
                color = clamp(color, 0.0, 1.0);
                float maxComponent = max(color.r, max(color.g, color.b));
                float minComponent = min(color.r, min(color.g, color.b));
                float delta = maxComponent - minComponent;
                float lightness = (maxComponent + minComponent) * 0.5;
                float saturation = delta <= 0.000001 ? 0.0 : clamp(
                    delta / max(1.0 - abs(2.0 * lightness - 1.0), 0.000001) + saturationShift,
                    0.0, 1.0);
                float hue;
                if (delta <= 0.000001) hue = 0.0;
                else if (maxComponent == color.r) hue = mod((color.g - color.b) / delta, 6.0);
                else if (maxComponent == color.g) hue = (color.b - color.r) / delta + 2.0;
                else hue = (color.r - color.g) / delta + 4.0;
                hue = mod(hue + degrees / 60.0 + 6.0, 6.0);
                float newLightness = clamp(lightness + lightnessShift * 0.5, 0.0, 1.0);
                float chroma = (1.0 - abs(2.0 * newLightness - 1.0)) * saturation;
                float secondary = chroma * (1.0 - abs(mod(hue, 2.0) - 1.0));
                float offset = newLightness - chroma * 0.5;
                if (hue < 1.0) return vec3(chroma, secondary, 0.0) + offset;
                if (hue < 2.0) return vec3(secondary, chroma, 0.0) + offset;
                if (hue < 3.0) return vec3(0.0, chroma, secondary) + offset;
                if (hue < 4.0) return vec3(0.0, secondary, chroma) + offset;
                if (hue < 5.0) return vec3(secondary, 0.0, chroma) + offset;
                return vec3(chroma, 0.0, secondary) + offset;
            }
            float bandWeight(float hueDegrees, float center) {
                float distance = abs(hueDegrees - center);
                distance = min(distance, 360.0 - distance);
                return max(1.0 - distance / 60.0, 0.0);
            }
            vec3 adjustSelectiveHsl(vec3 color) {
                vec3 bounded = clamp(color, 0.0, 1.0);
                float maximum = max(bounded.r, max(bounded.g, bounded.b));
                float minimum = min(bounded.r, min(bounded.g, bounded.b));
                float delta = maximum - minimum;
                if (delta <= 0.000001) return bounded;
                float hue;
                if (maximum == bounded.r) hue = mod((bounded.g - bounded.b) / delta, 6.0);
                else if (maximum == bounded.g) hue = (bounded.b - bounded.r) / delta + 2.0;
                else hue = (bounded.r - bounded.g) / delta + 4.0;
                float degrees = hue * 60.0;
                vec3 adjustment = uHslBand0 * bandWeight(degrees, 0.0) +
                    uHslBand1 * bandWeight(degrees, 60.0) +
                    uHslBand2 * bandWeight(degrees, 120.0) +
                    uHslBand3 * bandWeight(degrees, 180.0) +
                    uHslBand4 * bandWeight(degrees, 240.0) +
                    uHslBand5 * bandWeight(degrees, 300.0);
                return adjustHsl(bounded, adjustment.x, adjustment.y, adjustment.z);
            }
            void main() {
                vec4 source = texture2D(uTexSampler, vTexSamplingCoord);
                float alpha = source.a;
                vec3 inputColor = source.rgb;
                if (uPremultipliedInput > 0.5 && alpha > 0.00001)
                    inputColor /= alpha;
                vec3 color = inputColor * exp2(uExposure);
                color = (color - vec3(0.5)) * uContrast + vec3(0.5);
                float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
                color = mix(vec3(luminance), color, uSaturation);
                color.r += uTemperature * 0.10;
                color.b -= uTemperature * 0.10;
                color.r += uTint * 0.05;
                color.g -= uTint * 0.10;
                color.b += uTint * 0.05;
                color = adjustHsl(color, uHueDegrees, uHslSaturation, uHslLightness);
                color = adjustSelectiveHsl(color);
                if (uUseCurves > 0.5) {
                    color.r = texture2D(uCurveLut,
                        vec2((color.r * 255.0 + 0.5) / 256.0, 0.5)).r;
                    color.g = texture2D(uCurveLut,
                        vec2((color.g * 255.0 + 0.5) / 256.0, 0.5)).g;
                    color.b = texture2D(uCurveLut,
                        vec2((color.b * 255.0 + 0.5) / 256.0, 0.5)).b;
                }
                float outputAlpha = uPremultipliedInput > 0.5 ? alpha : 1.0;
                gl_FragColor = vec4(clamp(color, 0.0, 1.0) * outputAlpha, alpha);
            }
        """
    }
}
