package com.metiri.armeasure

import android.opengl.GLES20
import android.opengl.Matrix
import uniffi.core.Point3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Phase 3 measurement line renderer: draws a GL_LINES segment between two
 * AR-space (world) points using the camera view/projection matrices, following
 * [PlaneRenderer]'s shader/pose conventions. The endpoints are already in world
 * coordinates, so the model matrix is identity.
 */
class LineRenderer {

    private val mvp = FloatArray(16)
    private val model = FloatArray(16)

    private var program = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var positionAttribute = 0

    fun createOnGlThread() {
        program = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        checkGlError("line init")
    }

    fun drawLine(
        a: Point3,
        b: Point3,
        view: FloatArray,
        projection: FloatArray,
        color: FloatArray,
    ) {
        val vertices = floatArrayOf(a.x, a.y, a.z, b.x, b.y, b.z)
        val vertexBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(vertices); position(0) }

        Matrix.setIdentityM(model, 0)
        Matrix.multiplyMM(mvp, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0)

        GLES20.glUseProgram(program)
        GLES20.glLineWidth(4.0f)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
        GLES20.glUniform4fv(colorUniform, 1, color, 0)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        checkGlError("line draw")
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_Mvp * a_Position;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}