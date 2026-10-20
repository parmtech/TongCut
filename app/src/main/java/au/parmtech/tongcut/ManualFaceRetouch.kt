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
import org.json.JSONObject

/**
 * Deterministic, manually placed local face-region controls. Positive width values slim;
 * negative values widen. Feature locations are fixed relative positions, not detected landmarks.
 */
internal data class ManualFaceRetouch(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.42f,
    val radiusX: Float = 0.23f,
    val radiusY: Float = 0.30f,
    val faceWidth: Float = 0f,
    val jawWidth: Float = 0f,
    val noseWidth: Float = 0f,
    val eyeScale: Float = 0f,
    val skinSoftening: Float = 0f
) {
    init {
        require(radiusX.isFinite() && radiusX in 0.08f..0.48f)
        require(radiusY.isFinite() && radiusY in 0.08f..0.48f)
        // Keep a small frame guard so bounded warps never need a fold-causing edge clamp.
        require(centerX.isFinite() && centerX in
            (radiusX + FRAME_GUARD)..(1f - radiusX - FRAME_GUARD))
        require(centerY.isFinite() && centerY in
            (radiusY + FRAME_GUARD)..(1f - radiusY - FRAME_GUARD))
        require(faceWidth.isFinite() && faceWidth in -0.25f..0.25f)
        require(jawWidth.isFinite() && jawWidth in -0.25f..0.25f)
        require(noseWidth.isFinite() && noseWidth in -0.08f..0.08f)
        require(eyeScale.isFinite() && eyeScale in 0f..0.35f)
        require(skinSoftening.isFinite() && skinSoftening in 0f..1f)
    }

    val isActive: Boolean
        get() = faceWidth != 0f || jawWidth != 0f || noseWidth != 0f ||
            eyeScale != 0f || skinSoftening != 0f

    companion object {
        const val FRAME_GUARD = 0.002f
    }
}

/** Original deterministic local starting points; users can refine every value afterward. */
internal enum class ManualFaceRetouchPreset(
    val label: String,
    private val faceWidth: Float,
    private val jawWidth: Float,
    private val noseWidth: Float,
    private val eyeScale: Float,
    private val skinSoftening: Float
) {
    NEUTRAL("Neutral", 0f, 0f, 0f, 0f, 0f),
    NATURAL("Natural", 0.05f, 0.035f, 0f, 0.04f, 0.12f),
    SCULPT("Sculpt", 0.13f, 0.10f, 0.025f, 0.08f, 0.10f),
    SOFT_FOCUS("Soft focus", 0f, 0f, 0f, 0f, 0.48f),
    OPEN_EYES("Open eyes", 0f, 0f, 0f, 0.16f, 0.08f),
    FULLER("Fuller", -0.12f, -0.05f, 0f, 0.03f, 0.08f);

    /** Preset changes are deliberately limited to the five authored local controls. */
    fun applyTo(current: ManualFaceRetouch): ManualFaceRetouch = current.copy(
        faceWidth = faceWidth,
        jawWidth = jawWidth,
        noseWidth = noseWidth,
        eyeScale = eyeScale,
        skinSoftening = skinSoftening
    )
}

/** Tolerant per-field project persistence; unknown or malformed values revert to neutral. */
internal object ManualFaceRetouchCodec {
    fun encode(settings: ManualFaceRetouch): JSONObject = JSONObject().apply {
        put("centerX", settings.centerX.toDouble())
        put("centerY", settings.centerY.toDouble())
        put("radiusX", settings.radiusX.toDouble())
        put("radiusY", settings.radiusY.toDouble())
        put("faceWidth", settings.faceWidth.toDouble())
        put("jawWidth", settings.jawWidth.toDouble())
        put("noseWidth", settings.noseWidth.toDouble())
        put("eyeScale", settings.eyeScale.toDouble())
        put("skinSoftening", settings.skinSoftening.toDouble())
    }

    fun decode(root: JSONObject?): ManualFaceRetouch {
        if (root == null) return ManualFaceRetouch()
        val defaults = ManualFaceRetouch()
        fun bounded(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float {
            val value = root.optDouble(key, Double.NaN).toFloat()
            return if (value.isFinite() && value in range) value else fallback
        }
        val radiusX = bounded("radiusX", defaults.radiusX, 0.08f..0.48f)
        val radiusY = bounded("radiusY", defaults.radiusY, 0.08f..0.48f)
        val centerX = bounded("centerX", defaults.centerX, 0f..1f)
            .coerceIn(radiusX + ManualFaceRetouch.FRAME_GUARD,
                1f - radiusX - ManualFaceRetouch.FRAME_GUARD)
        val centerY = bounded("centerY", defaults.centerY, 0f..1f)
            .coerceIn(radiusY + ManualFaceRetouch.FRAME_GUARD,
                1f - radiusY - ManualFaceRetouch.FRAME_GUARD)
        return ManualFaceRetouch(
            centerX = centerX,
            centerY = centerY,
            radiusX = radiusX,
            radiusY = radiusY,
            faceWidth = bounded("faceWidth", 0f, -0.25f..0.25f),
            jawWidth = bounded("jawWidth", 0f, -0.25f..0.25f),
            noseWidth = bounded("noseWidth", 0f, -0.08f..0.08f),
            eyeScale = bounded("eyeScale", 0f, 0f..0.35f),
            skinSoftening = bounded("skinSoftening", 0f, 0f..1f)
        )
    }
}

/** Local face reshape for primary SDR footage and manually edited secondary VIDEO/IMAGE layers. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ManualFaceRetouchEffect(
    private val settings: ManualFaceRetouch
) : GlEffect {
    init { require(settings.isActive) }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException(
            IllegalArgumentException("Manual face retouch currently supports SDR video only.")
        )
        return ManualFaceRetouchShader(settings)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class ManualFaceRetouchShader(
    settings: ManualFaceRetouch
) : BaseGlShaderProgram(false, 1) {
    private val precisionQualifier = fragmentFloatPrecision()
    private val program = try {
        GlProgram(
            VERTEX_SHADER.replace("PRECISION_QUALIFIER", precisionQualifier),
            FRAGMENT_SHADER.replace("PRECISION_QUALIFIER", precisionQualifier)
        )
    } catch (error: GlUtil.GlException) {
        throw VideoFrameProcessingException(error)
    }

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        program.setFloatsUniform("uCenter", floatArrayOf(settings.centerX, settings.centerY))
        program.setFloatsUniform("uRadius", floatArrayOf(settings.radiusX, settings.radiusY))
        program.setFloatUniform("uFaceWidth", settings.faceWidth)
        program.setFloatUniform("uJawWidth", settings.jawWidth)
        program.setFloatUniform("uNoseWidth", settings.noseWidth)
        program.setFloatUniform("uEyeScale", settings.eyeScale)
        program.setFloatUniform("uSkinSoftening", settings.skinSoftening)
    }

    private fun fragmentFloatPrecision(): String = runCatching {
        val range = IntArray(2)
        val precision = IntArray(1)
        GLES20.glGetShaderPrecisionFormat(
            GLES20.GL_FRAGMENT_SHADER,
            GLES20.GL_HIGH_FLOAT,
            range,
            0,
            precision,
            0
        )
        if (precision[0] > 0) "highp" else "mediump"
    }.getOrDefault("mediump")

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        require(inputWidth > 0 && inputHeight > 0)
        program.setFloatsUniform("uFrameSize", floatArrayOf(
            inputWidth.toFloat(), inputHeight.toFloat()
        ))
        program.setFloatsUniform("uTexel", floatArrayOf(
            1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()
        ))
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
            precision highp float;
            attribute vec4 aFramePosition;
            varying PRECISION_QUALIFIER vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        const val FRAGMENT_SHADER = """
            #version 100
            precision PRECISION_QUALIFIER float;
            uniform sampler2D uTexSampler;
            uniform vec2 uCenter;
            uniform vec2 uRadius;
            uniform vec2 uFrameSize;
            uniform vec2 uTexel;
            uniform float uFaceWidth;
            uniform float uJawWidth;
            uniform float uNoseWidth;
            uniform float uEyeScale;
            uniform float uSkinSoftening;
            varying PRECISION_QUALIFIER vec2 vTexSamplingCoord;

            float faceRegionFalloff(vec2 local) {
                return 1.0 - smoothstep(0.68, 1.0, length(local));
            }

            float verticalFalloff(float y) {
                return 1.0 - smoothstep(0.72, 1.0, abs(y));
            }

            // C1 bump, zero outside [-1,1]; |h'(t)| <= 1, so bounded x warps stay injective.
            float shapeBump(float t) {
                if (abs(t) >= 1.0) return 0.0;
                float edge = 1.0 - t * t;
                return t * edge * edge;
            }

            // Positive radial enlargement is injective for a nonnegative, nonincreasing profile.
            vec2 enlargeEye(vec2 uv, vec2 eyeCenter) {
                vec2 eyeRadiusPx = uRadius * uFrameSize * vec2(0.22, 0.15);
                vec2 offsetPx = (uv - eyeCenter) * uFrameSize;
                float radialDistance = length(offsetPx / eyeRadiusPx);
                float influence = 1.0 - smoothstep(0.0, 1.0, radialDistance);
                float scale = 1.0 + uEyeScale * influence;
                return eyeCenter + (offsetPx / scale) / uFrameSize;
            }

            float edgeWeight(vec4 center, vec4 sampleColor) {
                vec4 delta = sampleColor - center;
                return 0.12 * exp(-dot(delta, delta) * 45.0);
            }

            void main() {
                vec2 uv = vTexSamplingCoord;
                vec2 pixelRadius = uRadius * uFrameSize;
                vec2 local = ((uv - uCenter) * uFrameSize) / pixelRadius;
                float faceY = verticalFalloff(local.y);
                float jawY = faceY * smoothstep(-0.12, 0.72, local.y);

                // Separable inverse mapping: x' = x + radiusX * strength(y) * h(x/radiusX).
                // The clamped combined strength is at most 0.5, so dx'/dx is at least 0.5.
                float contour = clamp(
                    uFaceWidth * faceY + uJawWidth * jawY, -0.5, 0.5
                );
                vec2 sourceUv = uv;
                sourceUv.x += pixelRadius.x * contour * shapeBump(local.x);

                // Nose uses h(3*x), and |noseWidth| <= 0.08 keeps dx'/dx >= 0.76.
                vec2 noseLocal = ((sourceUv - uCenter) * uFrameSize) / pixelRadius;
                float noseY = 1.0 - smoothstep(0.08, 0.45, abs(noseLocal.y - 0.08));
                sourceUv.x += pixelRadius.x * uNoseWidth * noseY *
                    shapeBump(3.0 * noseLocal.x);

                vec2 leftEye = uCenter + vec2(-0.29 * uRadius.x, -0.21 * uRadius.y);
                vec2 rightEye = uCenter + vec2(0.29 * uRadius.x, -0.21 * uRadius.y);
                sourceUv = enlargeEye(sourceUv, leftEye);
                sourceUv = enlargeEye(sourceUv, rightEye);
                // Every supported profile stays within the validated oval/frame bounds.

                vec4 center = texture2D(uTexSampler, sourceUv);
                vec4 right = texture2D(uTexSampler,
                    clamp(sourceUv + vec2(uTexel.x, 0.0), vec2(0.0), vec2(1.0)));
                vec4 left = texture2D(uTexSampler,
                    clamp(sourceUv - vec2(uTexel.x, 0.0), vec2(0.0), vec2(1.0)));
                vec4 down = texture2D(uTexSampler,
                    clamp(sourceUv + vec2(0.0, uTexel.y), vec2(0.0), vec2(1.0)));
                vec4 up = texture2D(uTexSampler,
                    clamp(sourceUv - vec2(0.0, uTexel.y), vec2(0.0), vec2(1.0)));
                float wr = edgeWeight(center, right);
                float wl = edgeWeight(center, left);
                float wd = edgeWeight(center, down);
                float wu = edgeWeight(center, up);
                vec4 softened = (center + right * wr + left * wl + down * wd + up * wu) /
                    (1.0 + wr + wl + wd + wu);
                float ovalAmount = faceRegionFalloff(local) * uSkinSoftening * 0.34;
                gl_FragColor = mix(center, softened, ovalAmount);
            }
        """
    }
}
