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
 * Standalone Media3 Effect that can be used by BOTH ExoPlayer.setVideoEffects
 * and Transformer EditedMediaItem.setEffects, after the A12/A04 owners approve
 * shared preview/export wiring.
 *
 * It does not infer landmarks or persist project data. Its caller must supply
 * source-frame observations and an exact output-presentation-time -> SOURCE-time
 * converter. In clips with trim, reverse or speed ramps, using presentation
 * time directly is wrong. Return null for a gap or an unsupported time map;
 * the output then remains unchanged for that frame.
 *
 * A different source clip must get a DIFFERENT effect instance and sample plan.
 * No camera uploads or native model dependencies.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class FaceReshapeVideoEffect(
    private val params: FaceReshapeParams,
    private val observations: FaceReshapeFramePlan.Prepared,
    private val sourceTimeAtPresentationUs: (Long) -> Long?
) : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean
    ): GlShaderProgram {
        if (useHdr) {
            throw VideoFrameProcessingException(
                IllegalArgumentException(
                    "Face reshape GPU prototype is SDR-only; HDR needs validation"
                )
            )
        }
        return FaceReshapeVideoShader(params, observations, sourceTimeAtPresentationUs)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class FaceReshapeVideoShader(
    private val params: FaceReshapeParams,
    private val observations: FaceReshapeFramePlan.Prepared,
    private val sourceTimeAtPresentationUs: (Long) -> Long?
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
        program.setFloatUniform("uEnabled", 0f)
        program.setFloatUniform("uEyeStrength", params.eyeEnlarge)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        require(inputWidth >= 2 && inputHeight >= 2) {
            "Face warp needs decoded frames with width and height >= 2"
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val sourceTimeUs = sourceTimeAtPresentationUs(presentationTimeUs)
            val landmarks = if (
                params.isNeutral || sourceTimeUs == null || sourceTimeUs < 0L
            ) null else observations.at(sourceTimeUs)

            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uEnabled", if (landmarks != null) 1f else 0f)
            if (landmarks != null) {
                val geometry = FaceReshapeGpuGeometry.from(landmarks, params)
                for (index in 0 until 7) {
                    val pin = geometry.pins[index]
                    program.setFloatsUniform("uPin$index", pin.coordinates())
                    program.setFloatsUniform("uRadius$index", pin.radii())
                }
                for (index in 0 until 2) {
                    program.setFloatsUniform(
                        "uEye$index",
                        geometry.eyes[index].coordinates()
                    )
                }
            }
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        } catch (error: IllegalArgumentException) {
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

        /**
         * Seven independent inverse displacement anchors:
         * 0,1 cheeks; 2,3 jaw; 4,5 nose; 6 chin. The Y axis is converted
         * from GL bottom-left to the same top-left space used by CPU warp.
         * Each pin's radius is >= .025, so no shader division by zero.
         *
         * The shader samples the original texture ONCE. GPU's hardware linear
         * sampler may interpolate straight-alpha RGB differently from our
         * alpha-safe CPU path, so transparent-edge parity remains a test gate.
         */
        const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform float uEnabled;
            uniform float uEyeStrength;

            uniform vec4 uPin0;
            uniform vec4 uPin1;
            uniform vec4 uPin2;
            uniform vec4 uPin3;
            uniform vec4 uPin4;
            uniform vec4 uPin5;
            uniform vec4 uPin6;
            uniform vec2 uRadius0;
            uniform vec2 uRadius1;
            uniform vec2 uRadius2;
            uniform vec2 uRadius3;
            uniform vec2 uRadius4;
            uniform vec2 uRadius5;
            uniform vec2 uRadius6;

            uniform vec4 uEye0;
            uniform vec4 uEye1;

            varying highp vec2 vTexSamplingCoord;

            float influence(vec2 normalizedDelta) {
                float distanceSquared = dot(normalizedDelta, normalizedDelta);
                if (distanceSquared >= 1.0) return 0.0;
                float t = clamp(1.0 - distanceSquared, 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }

            vec2 shiftPin(vec2 point, vec4 pin, vec2 radii) {
                vec2 normalizedDelta = (point - pin.xy) / radii;
                return pin.zw * influence(normalizedDelta);
            }

            vec2 shiftEye(vec2 point, vec4 eye) {
                vec2 relative = point - eye.xy;
                float weight = influence(relative / eye.zw);
                float factor = 1.0 /
                    (1.0 + 0.40 * uEyeStrength * weight);
                return relative * (factor - 1.0);
            }

            void main() {
                if (uEnabled < 0.5) {
                    gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
                    return;
                }

                // Use top-left coordinates for both GPU and CPU geometry.
                vec2 topLeft = vec2(
                    vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y
                );
                vec2 sourcePoint = topLeft;
                sourcePoint += shiftPin(topLeft, uPin0, uRadius0);
                sourcePoint += shiftPin(topLeft, uPin1, uRadius1);
                sourcePoint += shiftPin(topLeft, uPin2, uRadius2);
                sourcePoint += shiftPin(topLeft, uPin3, uRadius3);
                sourcePoint += shiftPin(topLeft, uPin4, uRadius4);
                sourcePoint += shiftPin(topLeft, uPin5, uRadius5);
                sourcePoint += shiftPin(topLeft, uPin6, uRadius6);
                if (uEyeStrength > 0.0) {
                    sourcePoint += shiftEye(topLeft, uEye0);
                    sourcePoint += shiftEye(topLeft, uEye1);
                }
                sourcePoint = clamp(sourcePoint, vec2(0.0), vec2(1.0));
                gl_FragColor = texture2D(
                    uTexSampler, vec2(sourcePoint.x, 1.0 - sourcePoint.y)
                );
            }
        """
    }
}
