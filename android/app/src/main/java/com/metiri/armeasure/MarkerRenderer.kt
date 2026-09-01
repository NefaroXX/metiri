package com.metiri.armeasure

import android.opengl.GLES20
import android.opengl.Matrix
import uniffi.core.Point3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Phase 3 marker renderer: draws a screen-space reticle (4 GL_LINES segments
 * with a central gap) centered on the NDC projection of a world point, so tap
 * points and the live finger-follow preview are visible over the camera/plane
 * overlay. Only the center is projected with the camera matrices; the arms are
 * emitted directly in NDC units, so the marker is pixel-sized regardless of
 * distance to the hit point.
 */
class MarkerRenderer {

    private var program = 0
    private var positionAttribute = 0
    private var colorUniform = 0

    private val clip = FloatArray(4)

    fun createOnGlThread() {
        program = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        checkGlError("marker init")
    }

    /**
     * Draws an 8-vertex reticle (4 line segments) centered on the projected
     * position of [world]. [viewHeightPx] converts the pixel offsets to NDC
     * units (NDC spans 2 units across the viewport height, so k = 2 / height).
     */
    fun drawMarker(
        world: Point3,
        view: FloatArray,
        projection: FloatArray,
        color: FloatArray,
        outerPx: Float,
        gapPx: Float,
        viewHeightPx: Int,
    ) {
        // Project the world point to NDC; skip points behind the camera.
        val inV = floatArrayOf(world.x, world.y, world.z, 1f)
        Matrix.multiplyMV(clip, 0, view, 0, inV, 0)
        Matrix.multiplyMV(clip, 0, projection, 0, clip, 0)
        if (clip[3] <= 0f) return
        val cx = clip[0] / clip[3]
        val cy = clip[1] / clip[3]

        val k = 2f / viewHeightPx
        val outer = outerPx * k
        val gap = gapPx * k

        // 8 vertices = 4 GL_LINES segments: two horizontal arms and two
        // vertical arms, each split by a central gap around the center point.
        val vertices = floatArrayOf(
            cx - outer, cy, cx - gap, cy,
            cx + gap, cy, cx + outer, cy,
            cx, cy - outer, cx, cy - gap,
            cx, cy + gap, cx, cy + outer,
        )
        val vertexBuffer: FloatBuffer =
            ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(vertices); position(0) }

        GLES20.glUseProgram(program)
        // The marker pass is blended (alpha 1 anyway) and draws with depth
        // testing off so it always shows over the camera/plane overlay.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glLineWidth(2.0f)
        GLES20.glUniform4fv(colorUniform, 1, color, 0)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 8)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        checkGlError("marker draw")
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 a_Position;
            void main() {
                gl_Position = vec4(a_Position, 0.0, 1.0);
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