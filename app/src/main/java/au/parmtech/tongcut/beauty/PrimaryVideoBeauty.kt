package au.parmtech.tongcut.beauty

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
 * Manually authored face region in normalized source-frame coordinates.
 *
 * The origin is the top-left of the upright frame. Width and height are the
 * complete ellipse dimensions, independent of output resolution.
 */
data class VideoBeautyRegion(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val width: Float = 0.34f,
    val height: Float = 0.48f
) {
    init {
        require(centerX.isFinite() && centerY.isFinite())
        require(width.isFinite() && height.isFinite())
        require(width in 0.12f..0.85f && height in 0.12f..0.9f)
        require(centerX in width / 2f..(1f - width / 2f))
        require(centerY in height / 2f..(1f - height / 2f))
    }
}

/**
 * Local, non-generative primary-footage retouch settings.
 *
 * A missing region is an intentional no-op. A region is never inferred from
 * image content: callers create/move/resize it explicitly in the editor.
 * Width controls are signed: positive narrows, negative widens. Eye scale is
 * signed: positive enlarges, negative reduces.
 */
data class PrimaryVideoBeauty(
    val region: VideoBeautyRegion? = null,
    val faceWidth: Float = 0f,
    val jawWidth: Float = 0f,
    val noseWidth: Float = 0f,
    val eyeScale: Float = 0f,
    val softening: Float = 0f
) {
    init {
        require(faceWidth.isFinite() && faceWidth in -1f..1f)
        require(jawWidth.isFinite() && jawWidth in -1f..1f)
        require(noseWidth.isFinite() && noseWidth in -1f..1f)
        require(eyeScale.isFinite() && eyeScale in -1f..1f)
        require(softening.isFinite() && softening in 0f..1f)
    }

    val isNoOp: Boolean
        get() = region == null || (
            faceWidth == 0f && jawWidth == 0f && noseWidth == 0f &&
                eyeScale == 0f && softening == 0f
            )

    fun withRegion(region: VideoBeautyRegion): PrimaryVideoBeauty = copy(region = region)
    fun clearRegion(): PrimaryVideoBeauty = copy(region = null)
}

/** Normalized top-left-origin coordinate used by the CPU parity/reference path. */
data class BeautyPoint(val x: Float, val y: Float)

/**
 * Reference mapping for the GPU shader: each output point fetches a source point.
 * The normalized strength limits and profile ellipses match the shader constants.
 * This pure function is exposed so A20 can add deterministic migration/math tests.
 */
object PrimaryVideoBeautyMath {
    fun sourcePositionForOutput(
        outputX: Float,
        outputY: Float,
        settings: PrimaryVideoBeauty
    ): BeautyPoint {
        require(outputX.isFinite() && outputY.isFinite())
        require(outputX in 0f..1f && outputY in 0f..1f)
        val region = settings.region ?: return BeautyPoint(outputX, outputY)
        if (settings.isNoOp) return BeautyPoint(outputX, outputY)

        val halfWidth = region.width * 0.5f
        val halfHeight = region.height * 0.5f
        val localX = (outputX - region.centerX) / halfWidth
        val localY = (outputY - region.centerY) / halfHeight
        val faceWeight = ellipseWeight(localX, localY, 0.55f, 1.2f)

        var sourceX = outputX +
            (outputX - region.centerX) * settings.faceWidth * 0.17f * faceWeight

        val jawWeight = ellipseWeight(localX / 0.9f, (localY - 0.42f) / 0.72f,
            0.45f, 1.15f)
        sourceX += (outputX - region.centerX) * settings.jawWidth * 0.12f * jawWeight

        val noseWeight = ellipseWeight(localX / 0.34f, (localY - 0.12f) / 0.38f,
            0.45f, 1.1f)
        sourceX += (outputX - region.centerX) * settings.noseWidth * 0.08f * noseWeight

        var sourceY = outputY
        if (settings.eyeScale != 0f) {
            for (side in floatArrayOf(-1f, 1f)) {
                val eyeX = region.centerX + side * halfWidth * 0.28f
                val eyeY = region.centerY - halfHeight * 0.2f
                val eyeLocalX = (outputX - eyeX) / (halfWidth * 0.19f)
                val eyeLocalY = (outputY - eyeY) / (halfHeight * 0.15f)
                val eyeWeight = ellipseWeight(eyeLocalX, eyeLocalY, 0.35f, 1.2f)
                val scale = settings.eyeScale * 0.18f * eyeWeight
                sourceX = eyeX + (sourceX - eyeX) * (1f - scale)
                sourceY = eyeY + (sourceY - eyeY) * (1f - scale)
            }
        }
        return BeautyPoint(sourceX.coerceIn(0f, 1f), sourceY.coerceIn(0f, 1f))
    }

    fun regionWeight(x: Float, y: Float, region: VideoBeautyRegion): Float {
        require(x.isFinite() && y.isFinite())
        val localX = (x - region.centerX) / (region.width * 0.5f)
        val localY = (y - region.centerY) / (region.height * 0.5f)
        return ellipseWeight(localX, localY, 0.55f, 1.2f)
    }

    private fun ellipseWeight(x: Float, y: Float, inner: Float, outer: Float): Float {
        val radius = kotlin.math.sqrt(x * x + y * y)
        val t = ((radius - inner) / (outer - inner)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return 1f - smooth
    }
}

/** SDR GPU transform shared by ExoPlayer preview and Media3 Transformer export. */
@androidx.annotation.OptIn(UnstableApi::class)
class PrimaryVideoBeautyEffect(
    private val settings: PrimaryVideoBeauty
) : GlEffect {
    init {
        require(!settings.isNoOp) { "Skip the beauty effect when settings are neutral" }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException(
            IllegalArgumentException("Manual face retouch currently supports SDR footage only")
        )
        return PrimaryVideoBeautyShader(settings)
    }
}

/** Returns null for unset/neutral settings so identity edits remain pixel-identical. */
@androidx.annotation.OptIn(UnstableApi::class)
fun primaryVideoBeautyEffectOrNull(settings: PrimaryVideoBeauty): GlEffect? =
    if (settings.isNoOp) null else PrimaryVideoBeautyEffect(settings)

@androidx.annotation.OptIn(UnstableApi::class)
private class PrimaryVideoBeautyShader(
    settings: PrimaryVideoBeauty
) : BaseGlShaderProgram(false, 1) {
    private val region = requireNotNull(settings.region)
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
        program.setFloatsUniform(
            "uRegion",
            floatArrayOf(region.centerX, region.centerY, region.width, region.height)
        )
        program.setFloatsUniform(
            "uShape",
            floatArrayOf(settings.faceWidth, settings.jawWidth,
                settings.noseWidth, settings.eyeScale)
        )
        program.setFloatUniform("uSoftening", settings.softening)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        require(inputWidth > 0 && inputHeight > 0)
        program.setFloatsUniform("uFrameSize", floatArrayOf(
            inputWidth.toFloat(), inputHeight.toFloat()
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
            uniform highp vec2 uFrameSize;
            uniform vec4 uRegion;
            uniform vec4 uShape;
            uniform float uSoftening;
            varying highp vec2 vTexSamplingCoord;

            float ellipseWeight(vec2 point, float innerRadius, float outerRadius) {
                float radius = length(point);
                return 1.0 - smoothstep(innerRadius, outerRadius, radius);
            }

            vec2 sourcePosition(vec2 outputUv) {
                vec2 point = vec2(outputUv.x, 1.0 - outputUv.y);
                vec2 center = uRegion.xy;
                vec2 halfSize = uRegion.zw * 0.5;
                vec2 local = (point - center) / halfSize;
                float face = ellipseWeight(local, 0.55, 1.2);
                float jaw = ellipseWeight(vec2(local.x / 0.9, (local.y - 0.42) / 0.72),
                    0.45, 1.15);
                float nose = ellipseWeight(vec2(local.x / 0.34, (local.y - 0.12) / 0.38),
                    0.45, 1.1);
                float sourceX = point.x +
                    (point.x - center.x) * uShape.x * 0.17 * face +
                    (point.x - center.x) * uShape.y * 0.12 * jaw +
                    (point.x - center.x) * uShape.z * 0.08 * nose;
                float sourceY = point.y;
                for (int sideIndex = 0; sideIndex < 2; sideIndex++) {
                    float side = sideIndex == 0 ? -1.0 : 1.0;
                    vec2 eyeCenter = center + vec2(side * halfSize.x * 0.28,
                        -halfSize.y * 0.2);
                    vec2 eyeLocal = (point - eyeCenter) /
                        vec2(halfSize.x * 0.19, halfSize.y * 0.15);
                    float eye = ellipseWeight(eyeLocal, 0.35, 1.2);
                    float scale = uShape.w * 0.18 * eye;
                    sourceX = eyeCenter.x + (sourceX - eyeCenter.x) * (1.0 - scale);
                    sourceY = eyeCenter.y + (sourceY - eyeCenter.y) * (1.0 - scale);
                }
                vec2 sourceTopLeft = clamp(vec2(sourceX, sourceY), 0.0, 1.0);
                return vec2(sourceTopLeft.x, 1.0 - sourceTopLeft.y);
            }

            void main() {
                vec2 centerSource = sourcePosition(vTexSamplingCoord);
                vec2 stepSize = 1.75 / uFrameSize;
                vec4 centerPixel = texture2D(uTexSampler, centerSource);
                vec4 color = centerPixel;
                if (uSoftening > 0.0) {
                    vec4 blurred =
                        texture2D(uTexSampler, clamp(centerSource + vec2(stepSize.x, 0.0), 0.0, 1.0)) +
                        texture2D(uTexSampler, clamp(centerSource - vec2(stepSize.x, 0.0), 0.0, 1.0)) +
                        texture2D(uTexSampler, clamp(centerSource + vec2(0.0, stepSize.y), 0.0, 1.0)) +
                        texture2D(uTexSampler, clamp(centerSource - vec2(0.0, stepSize.y), 0.0, 1.0));
                    blurred *= 0.25;
                    vec2 point = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y);
                    vec2 local = (point - uRegion.xy) / (uRegion.zw * 0.5);
                    float face = ellipseWeight(local, 0.55, 1.2);
                    float amount = clamp(uSoftening * face, 0.0, 1.0);
                    color = mix(centerPixel, blurred, amount);
                }
                gl_FragColor = vec4(color.rgb, centerPixel.a);
            }
        """
    }
}
