package com.metiri.armeasure

import android.opengl.GLES20
import android.util.Log

/** Shared GL helpers for the Phase 2 debug renderers. */
internal fun createGlProgram(vertexSrc: String, fragmentSrc: String): Int {
    val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
    val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
    if (vs == 0 || fs == 0) return 0
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vs)
    GLES20.glAttachShader(program, fs)
    GLES20.glLinkProgram(program)
    val ok = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
    if (ok[0] == 0) {
        Log.e("ArMeasure.GL", "program link failed: ${GLES20.glGetProgramInfoLog(program)}")
        GLES20.glDeleteProgram(program)
        return 0
    }
    return program
}

private fun compileShader(type: Int, src: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, src)
    GLES20.glCompileShader(shader)
    val ok = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
    if (ok[0] == 0) {
        Log.e("ArMeasure.GL", "shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        GLES20.glDeleteShader(shader)
        return 0
    }
    return shader
}

internal fun checkGlError(op: String) {
    val error = GLES20.glGetError()
    if (error != GLES20.GL_NO_ERROR) {
        Log.e("ArMeasure.GL", "$op: glError 0x${Integer.toHexString(error)}")
    }
}