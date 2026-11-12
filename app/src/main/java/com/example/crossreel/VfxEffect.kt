package com.example.crossreel

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

class VfxEffect(private val preset: VfxPreset) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return VfxShaderProgram(context, useHdr, preset)
    }
}

class VfxShaderProgram(
    context: Context,
    useHdr: Boolean,
    preset: VfxPreset
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram
    private var firstPtsUs = -1L

    init {
        try {
            val fragmentShaderPath = when (preset) {
                VfxPreset.OLD_FILM -> "old_film_shader.glsl"
                VfxPreset.TV_GLITCH -> "tv_glitch_shader.glsl"
                else -> throw IllegalArgumentException("No shader for preset: $preset")
            }
            glProgram = GlProgram(context, "vertex_shader.glsl", fragmentShaderPath)
            
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        return androidx.media3.common.util.Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (firstPtsUs < 0L) firstPtsUs = presentationTimeUs
            val relativeTimeSec = (presentationTimeUs - firstPtsUs) / 1_000_000f

            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uTime", relativeTimeSec)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }
}
