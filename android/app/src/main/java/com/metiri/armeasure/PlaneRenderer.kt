package com.metiri.armeasure

import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class PlaneRenderer {

    private class PlaneData(
        val color: FloatArray,
        val vertexBuffer: FloatBuffer,
        val vertexCount: Int,
    )

    private val mvp = FloatArray(16)
    private val view = FloatArray(16)
    private val projection = FloatArray(16)
    private val model = FloatArray(16)
    private var lastReportedCount = -1

    private var program = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var positionAttribute = 0

    fun createOnGlThread() {
        program = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        checkGlError("plane init")
    }

    fun onDrawFrame(frame: Frame, session: Session, onPlaneFound: (Int) -> Unit) {
        val allTracking = session.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.polygon.limit() >= 9 }
        val tracked = allTracking.filter { it.extentX * it.extentZ >= MIN_PLANE_AREA }
        if (tracked.size != lastReportedCount) {
            if (allTracking.size != tracked.size) {
                Log.d(TAG, "filtered ${allTracking.size} → ${tracked.size} planes (extent≥$MIN_PLANE_AREA m²)")
            }
            lastReportedCount = tracked.size
            onPlaneFound(tracked.size)
        }

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(projection, 0, 0.1f, 100.0f)

        GLES20.glUseProgram(program)
        // Blend is scoped to the plane pass: the camera background is drawn
        // with blending disabled (see BackgroundRenderer), so the plane fill
        // alpha is re-enabled here and left on for the overlay pass after us.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        for (plane in tracked) {
            val data = buildData(plane)
            plane.centerPose.toMatrix(model, 0)
            Matrix.multiplyMM(mvp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0)
            GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
            GLES20.glUniform4fv(colorUniform, 1, data.color, 0)
            GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 0, data.vertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, data.vertexCount)
        }
        GLES20.glDisableVertexAttribArray(positionAttribute)
        checkGlError("plane draw")
    }

    private fun buildData(plane: Plane): PlaneData {
        val polygon = plane.polygon
        polygon.rewind()
        val n = polygon.remaining() / 3
        val pts = FloatArray(n * 3)
        polygon.get(pts)

        val triCount = (n - 2) * 3
        val verts = FloatArray(triCount * 3)
        var o = 0
        for (i in 1 until n - 1) {
            verts[o++] = pts[0]; verts[o++] = pts[1]; verts[o++] = pts[2]
            verts[o++] = pts[i * 3]; verts[o++] = pts[i * 3 + 1]; verts[o++] = pts[i * 3 + 2]
            verts[o++] = pts[(i + 1) * 3]; verts[o++] = pts[(i + 1) * 3 + 1]; verts[o++] = pts[(i + 1) * 3 + 2]
        }
        val buffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(verts); position(0) }

        val color = when (plane.type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> floatArrayOf(0.29f, 0.86f, 0.73f, 0.18f)
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> floatArrayOf(0.86f, 0.29f, 0.52f, 0.18f)
            else -> floatArrayOf(0.55f, 0.35f, 0.90f, 0.18f)
        }
        return PlaneData(color, buffer, triCount)
    }

    companion object {
        private const val TAG = "ArMeasure.Planes"
        /** Minimum plane area in m² (≈800 cm² ≈ 28×28 cm). */
        private const val MIN_PLANE_AREA = 0.08f
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