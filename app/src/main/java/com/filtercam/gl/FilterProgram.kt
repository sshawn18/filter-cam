package com.filtercam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.filtercam.filter.FilterParams
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FilterProgram(private val oes: Boolean) {
    private val program: Int
    private val uniforms = HashMap<String, Int>()
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, Shaders.vertexSource())
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, Shaders.fragmentSource(oes))
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "Link failed: " + GLES30.glGetProgramInfoLog(program) }
    }

    fun draw(textureId: Int, texMatrix: FloatArray, p: FilterParams, strength: Float, timeSec: Float, aspect: Float) {
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(u("uTex"), 0)
        GLES30.glUniformMatrix4fv(u("uTexMatrix"), 1, false, texMatrix, 0)
        // 2x2 linear part of the tex matrix (column-major mat4 -> mat2)
        GLES30.glUniformMatrix2fv(u("uUvLin"), 1, false,
            floatArrayOf(texMatrix[0], texMatrix[1], texMatrix[4], texMatrix[5]), 0)
        GLES30.glUniform1f(u("uTime"), timeSec)
        GLES30.glUniform1f(u("uAspect"), aspect)
        GLES30.glUniform1f(u("uExposure"), p.exposure)
        GLES30.glUniform1f(u("uTemperature"), p.temperature)
        GLES30.glUniform1f(u("uTint"), p.tint)
        GLES30.glUniform1f(u("uContrast"), p.contrast)
        GLES30.glUniform1f(u("uBlackLift"), p.blackLift)
        GLES30.glUniform3f(u("uShadowColor"), p.shadowColor[0], p.shadowColor[1], p.shadowColor[2])
        GLES30.glUniform1f(u("uShadowAmount"), p.shadowAmount)
        GLES30.glUniform3f(u("uHighlightColor"), p.highlightColor[0], p.highlightColor[1], p.highlightColor[2])
        GLES30.glUniform1f(u("uHighlightAmount"), p.highlightAmount)
        GLES30.glUniform1f(u("uSaturation"), p.saturation)
        GLES30.glUniform1f(u("uGreenDeepen"), p.greenDeepen)
        GLES30.glUniform1f(u("uCyanShift"), p.cyanShift)
        GLES30.glUniform1f(u("uSharpness"), p.sharpness)
        GLES30.glUniform1f(u("uClarity"), p.clarity)
        GLES30.glUniform1f(u("uRolloff"), p.highlightRolloff)
        GLES30.glUniform1f(u("uSkinProtect"), p.skinProtect)
        GLES30.glUniform1f(u("uHalation"), p.halation)
        GLES30.glUniform3f(u("uHalationColor"), p.halationColor[0], p.halationColor[1], p.halationColor[2])
        GLES30.glUniform1f(u("uHalationThreshold"), p.halationThreshold)
        GLES30.glUniform1f(u("uGrain"), p.grain)
        GLES30.glUniform1f(u("uVignette"), p.vignette)
        GLES30.glUniform1f(u("uStrength"), strength)
        val pos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glEnableVertexAttribArray(pos)
        GLES30.glVertexAttribPointer(pos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(pos)
    }

    fun release() = GLES30.glDeleteProgram(program)

    private fun u(name: String) = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES30.GL_TRUE) { "Shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }
}
