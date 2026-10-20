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
 * Applied through both Media3 Transformer export and ExoPlayer preview.
 * Alpha-to-TextureView compositing must still pass emulator/phone pixel acceptance.
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
        program.setFloatUniform("uInvertMask", if (settings.invertMask) 1f else 0f)
        program.setFloatUniform("uMaskRotation", settings.maskRotationDegrees)
        program.setFloatsUniform(
            "uMaskCenter", floatArrayOf(settings.maskCenterX, settings.maskCenterY)
        )
        program.setFloatsUniform(
            "uMaskScale", floatArrayOf(settings.maskScaleX, settings.maskScaleY)
        )
        program.setFloatUniform("uMaskPolygonCount", settings.maskPoints.size.toFloat())
        settings.maskPoints.forEachIndexed { index, point ->
            program.setFloatsUniform("uMaskPolygon$index", floatArrayOf(point.x, point.y))
        }
        for (index in settings.maskPoints.size until PhotoMatte.MAX_POLYGON_POINTS) {
            val point = settings.maskPoints.last()
            program.setFloatsUniform("uMaskPolygon$index", floatArrayOf(point.x, point.y))
        }
        program.setFloatUniform("uUseChroma", if (chroma == null) 0f else 1f)
        program.setFloatsUniform("uKeyColor", floatArrayOf(
            ((key ushr 16) and 255) / 255f,
            ((key ushr 8) and 255) / 255f,
            (key and 255) / 255f
        ))
        program.setFloatUniform("uTolerance", chroma?.tolerance ?: 0.18f)
        program.setFloatUniform("uKeyFeather", chroma?.feather ?: 0.12f)
        program.setFloatUniform("uSpill", chroma?.spillSuppression ?: 0f)
        program.setFloatUniform(
            "uKeyDominant", (chroma?.let {
                PhotoMatte.dominantChannel(it.keyArgb)
            } ?: -1).toFloat()
        )
        program.setFloatUniform("uExposure", settings.colorGrade.exposure)
        program.setFloatUniform("uContrast", settings.colorGrade.contrast)
        program.setFloatUniform("uSaturation", settings.colorGrade.saturation)
        program.setFloatUniform("uTemperature", settings.colorGrade.temperature)
        program.setFloatUniform("uTint", settings.colorGrade.tint)
        program.setFloatUniform("uHueDegrees", settings.colorGrade.hueDegrees)
        program.setFloatUniform("uHslSaturation", settings.colorGrade.hslSaturation)
        program.setFloatUniform("uHslLightness", settings.colorGrade.hslLightness)
        program.setFloatUniform("uBlurAmount", settings.blur)
        program.setFloatUniform("uSharpenAmount", settings.sharpen)
        program.setFloatUniform("uGrainAmount", settings.grain)
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
            varying highp vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        const val FRAGMENT_SHADER = """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform highp vec2 uFrameSize;
            uniform float uMaskMode;
            uniform float uMaskFeather;
            uniform float uInvertMask;
            uniform float uMaskRotation;
            uniform vec2 uMaskCenter;
            uniform vec2 uMaskScale;
            uniform float uMaskPolygonCount;
            uniform vec2 uMaskPolygon0;
            uniform vec2 uMaskPolygon1;
            uniform vec2 uMaskPolygon2;
            uniform vec2 uMaskPolygon3;
            uniform vec2 uMaskPolygon4;
            uniform vec2 uMaskPolygon5;
            uniform vec2 uMaskPolygon6;
            uniform vec2 uMaskPolygon7;
            uniform float uUseChroma;
            uniform vec3 uKeyColor;
            uniform float uTolerance;
            uniform float uKeyFeather;
            uniform float uSpill;
            uniform float uKeyDominant;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uHueDegrees;
            uniform float uHslSaturation;
            uniform float uHslLightness;
            uniform float uBlurAmount;
            uniform float uSharpenAmount;
            uniform float uGrainAmount;
            varying highp vec2 vTexSamplingCoord;

            float edge(float lo, float hi, float value) {
                return smoothstep(lo, max(lo + 0.00001, hi), value);
            }
            vec2 polygonVertex(float index) {
                if (index < 0.5) return uMaskPolygon0;
                if (index < 1.5) return uMaskPolygon1;
                if (index < 2.5) return uMaskPolygon2;
                if (index < 3.5) return uMaskPolygon3;
                if (index < 4.5) return uMaskPolygon4;
                if (index < 5.5) return uMaskPolygon5;
                if (index < 6.5) return uMaskPolygon6;
                return uMaskPolygon7;
            }
            float polygonMatte(vec2 point) {
                vec2 radii = uFrameSize * 0.48 * uMaskScale;
                vec2 centered = point - uMaskCenter * uFrameSize * 0.5;
                float angle = radians(uMaskRotation);
                float c = cos(angle);
                float s = sin(angle);
                vec2 rotated = vec2(c * centered.x + s * centered.y,
                    -s * centered.x + c * centered.y);
                vec2 p = rotated / radii;
                bool inside = false;
                float minDistanceSquared = 1.0e20;
                for (int i = 0; i < 8; i++) {
                    if (float(i) < uMaskPolygonCount) {
                        vec2 a = polygonVertex(float(i));
                        float nextIndex = mod(float(i + 1), uMaskPolygonCount);
                        vec2 b = polygonVertex(nextIndex);
                        if ((a.y > p.y) != (b.y > p.y) &&
                            p.x < (b.x - a.x) * (p.y - a.y) /
                                (b.y - a.y) + a.x) inside = !inside;
                        vec2 physicalPoint = p * radii;
                        vec2 physicalA = a * radii;
                        vec2 physicalB = b * radii;
                        vec2 segment = physicalB - physicalA;
                        float segmentLengthSquared = max(dot(segment, segment), 0.000001);
                        float t = clamp(dot(physicalPoint - physicalA, segment) /
                            segmentLengthSquared, 0.0, 1.0);
                        vec2 delta = physicalPoint - (physicalA + t * segment);
                        minDistanceSquared = min(minDistanceSquared, dot(delta, delta));
                    }
                }
                float signedDistance = sqrt(minDistanceSquared);
                if (!inside) signedDistance = -signedDistance;
                if (uMaskFeather <= 0.0)
                    return signedDistance >= 0.0 ? 1.0 : 0.0;
                return edge(-uMaskFeather * min(uFrameSize.x, uFrameSize.y) * 0.5,
                    uMaskFeather * min(uFrameSize.x, uFrameSize.y) * 0.5, signedDistance);
            }
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
            void accumulateBlurSample(vec4 sampleColor, float weight,
                inout vec3 premultiplied, inout float alphaWeight) {
                premultiplied += sampleColor.rgb * sampleColor.a * weight;
                alphaWeight += sampleColor.a * weight;
            }
            vec4 blurredPixel(vec2 coord) {
                if (uBlurAmount <= 0.00001) return texture2D(uTexSampler, coord);
                float radiusPixels = max(1.0, floor(
                    min(uFrameSize.x, uFrameSize.y) * uBlurAmount + 0.5));
                vec2 radius = vec2(radiusPixels / uFrameSize.x,
                    radiusPixels / uFrameSize.y);
                vec3 premultiplied = vec3(0.0);
                float alphaWeight = 0.0;
                accumulateBlurSample(texture2D(uTexSampler, coord), 0.25,
                    premultiplied, alphaWeight);
                accumulateBlurSample(texture2D(uTexSampler,
                    coord + vec2(-radius.x, -radius.y)), 0.0625,
                    premultiplied, alphaWeight);
                accumulateBlurSample(texture2D(uTexSampler,
                    coord + vec2(0.0, -radius.y)), 0.125,
                    premultiplied, alphaWeight);
                accumulateBlurSample(texture2D(uTexSampler,
                    coord + vec2(radius.x, -radius.y)), 0.0625,
                    premultiplied, alphaWeight);
                accumulateBlurSample(texture2D(uTexSampler,
                    coord + vec2(-radius.x, 0.0)), 0.125,
                    premultiplied, alphaWeight);
                accumulateBlurSample(texture2D(uTexSampler,
                    coord + vec2(radius.x, 0.0)), 0.125,
                    premultiplied, alphaWeight);
                accumulateBlurSample(texture2D(uTexSampler,
                    coord + vec2(-radius.x, radius.y)), 0.0625,
                    premultiplied, alphaWeight);
                accumulateBlurSample(texture2D(uTexSampler,
                    coord + vec2(0.0, radius.y)), 0.125,
                    premultiplied, alphaWeight);
                accumulateBlurSample(texture2D(uTexSampler,
                    coord + vec2(radius.x, radius.y)), 0.0625,
                    premultiplied, alphaWeight);
                if (alphaWeight <= 0.00001) return vec4(0.0);
                return vec4(premultiplied / alphaWeight, alphaWeight);
            }
            void main() {
                vec4 pixel = blurredPixel(vTexSamplingCoord);
                if (uSharpenAmount > 0.00001) {
                    vec4 centre = texture2D(uTexSampler, vTexSamplingCoord);
                    vec2 onePixel = vec2(1.0) / uFrameSize;
                    vec4 leftSample = texture2D(uTexSampler,
                        vTexSamplingCoord - vec2(onePixel.x, 0.0));
                    vec4 rightSample = texture2D(uTexSampler,
                        vTexSamplingCoord + vec2(onePixel.x, 0.0));
                    vec4 topSample = texture2D(uTexSampler,
                        vTexSamplingCoord - vec2(0.0, onePixel.y));
                    vec4 bottomSample = texture2D(uTexSampler,
                        vTexSamplingCoord + vec2(0.0, onePixel.y));
                    float neighborAlpha = leftSample.a + rightSample.a +
                        topSample.a + bottomSample.a;
                    vec3 neighbors = centre.rgb;
                    if (neighborAlpha > 0.00001) {
                        neighbors = (leftSample.rgb * leftSample.a +
                            rightSample.rgb * rightSample.a +
                            topSample.rgb * topSample.a +
                            bottomSample.rgb * bottomSample.a) / neighborAlpha;
                    }
                    pixel.rgb = clamp(pixel.rgb + (centre.rgb - neighbors) *
                        uSharpenAmount, 0.0, 1.0);
                    if (centre.a <= 0.00001) pixel.rgb = vec3(0.0);
                }
                if (uGrainAmount > 0.00001 && pixel.a > 0.00001) {
                    highp vec2 pixelCoord = floor(vTexSamplingCoord * uFrameSize);
                    highp vec2 hashCoefficients = vec2(12.9898, 78.233);
                    highp float noise = fract(sin(dot(pixelCoord,
                        hashCoefficients)) * 43758.5453) - 0.5;
                    pixel.rgb = clamp(pixel.rgb + vec3(noise * uGrainAmount * 0.2),
                        0.0, 1.0);
                }
                vec2 point = (vTexSamplingCoord - vec2(0.5)) * uFrameSize;
                float shortSide = min(uFrameSize.x, uFrameSize.y);
                float blur = shortSide * uMaskFeather;
                float matte = 1.0;
                // PhotoMask: NONE=0, CIRCLE=1, ROUNDED_RECT=2, WIPE=3,
                // ELLIPSE=4, DIAMOND=5, POLYGON=6.
                if (uMaskMode > 0.5 && uMaskMode < 1.5) {
                    float inside = shortSide * 0.48 - length(point);
                    matte = edge(-blur * 0.5, blur * 0.5, inside);
                } else if (uMaskMode > 1.5 && uMaskMode < 2.5) {
                    float radius = shortSide * 0.19;
                    vec2 q = abs(point) - (uFrameSize * 0.5 - vec2(radius));
                    float signedDistance = length(max(q, vec2(0.0)))
                        + min(max(q.x, q.y), 0.0) - radius;
                    matte = edge(-blur * 0.5, blur * 0.5, -signedDistance);
                } else if (uMaskMode > 2.5 && uMaskMode < 3.5) {
                    float inside = (uFrameSize.x * 0.5) * (1.0 + uMaskCenter.x) -
                        (vTexSamplingCoord.x * uFrameSize.x);
                    matte = edge(-blur * 0.5, blur * 0.5, inside);
                } else if (uMaskMode > 3.5 && uMaskMode < 5.5) {
                    vec2 radii = uFrameSize * 0.48 * uMaskScale;
                    vec2 centered = point - uMaskCenter * uFrameSize * 0.5;
                    float angle = radians(uMaskRotation);
                    float c = cos(angle);
                    float s = sin(angle);
                    vec2 rotated = vec2(
                        c * centered.x + s * centered.y,
                        -s * centered.x + c * centered.y
                    );
                    vec2 normalized = rotated / radii;
                    float distance = (uMaskMode < 4.5)
                        ? length(normalized)
                        : abs(normalized.x) + abs(normalized.y);
                    float inside = (1.0 - distance) * min(radii.x, radii.y);
                    matte = edge(-blur * 0.5, blur * 0.5, inside);
                } else if (uMaskMode > 5.5 && uMaskMode < 6.5) {
                    matte = polygonMatte(point);
                }
                if (uInvertMask > 0.5 && uMaskMode > 0.5) {
                    matte = 1.0 - matte;
                }
                if (uUseChroma > 0.5) {
                    // Normalize by sqrt(3) as in PhotoMatte.chromaAlpha().
                    float difference = length(pixel.rgb - uKeyColor) * 0.57735026919;
                    matte *= edge(uTolerance, uTolerance + uKeyFeather, difference);
                }
                // GPU video surfaces are premultiplied when composited by
                // the Android window. Keeping green RGB at alpha 0 adds a
                // cyan fringe over a blue underlying video in TextureView.
                // Premultiply the RGB by the actual matte before compositing.
                float keyedAlpha = pixel.a * matte;
                vec3 corrected = pixel.rgb;
                if (uUseChroma > 0.5 && uSpill > 0.0) {
                    if (uKeyDominant > -0.5 && uKeyDominant < 0.5) {
                        corrected.r -= max(0.0,
                            corrected.r - max(corrected.g, corrected.b)) * uSpill;
                    } else if (uKeyDominant >= 0.5 && uKeyDominant < 1.5) {
                        corrected.g -= max(0.0,
                            corrected.g - max(corrected.r, corrected.b)) * uSpill;
                    } else if (uKeyDominant >= 1.5) {
                        corrected.b -= max(0.0,
                            corrected.b - max(corrected.r, corrected.g)) * uSpill;
                    }
                }
                vec3 grade = corrected * exp2(uExposure);
                grade = (grade - vec3(0.5)) * uContrast + vec3(0.5);
                float luminance = dot(grade, vec3(0.2126, 0.7152, 0.0722));
                grade = mix(vec3(luminance), grade, uSaturation);
                grade.r += uTemperature * 0.10;
                grade.b -= uTemperature * 0.10;
                grade.r += uTint * 0.05;
                grade.g -= uTint * 0.10;
                grade.b += uTint * 0.05;
                grade = adjustHsl(grade, uHueDegrees, uHslSaturation, uHslLightness);
                gl_FragColor = vec4(clamp(grade, 0.0, 1.0) * keyedAlpha, keyedAlpha);
            }
        """
    }
}
