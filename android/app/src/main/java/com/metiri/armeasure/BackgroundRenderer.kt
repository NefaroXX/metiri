package com.metiri.armeasure

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Renders the ARCore camera feed as a fullscreen textured quad backed by an
 * OES external texture (the standard hello_ar_java background approach).
 */
class BackgroundRenderer {

    /** Texture id created on the GL thread; set by ArRenderer. */
    var textureId: Int = -1

    private val quadVertices = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)
    private val quadIndices = shortArrayOf(0, 1, 2, 0, 2, 3)
    private val uvs = FloatArray(8)
    private var uvsLogged = false

    private val vertexBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(quadVertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(quadVertices); position(0) }
    private val indexBuffer: ShortBuffer =
        ByteBuffer.allocateDirect(quadIndices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            .apply { put(quadIndices); position(0) }
    private val uvBuffer: FloatBuffer =
        ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var positionAttribute = 0
    private var texCoordAttribute = 0
    private var textureUniform = 0

    fun createOnGlThread() {
        program = createGlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "sTexture")
        checkGlError("background init")
    }

    fun draw(frame: Frame) {
        // The camera quad must NEVER be drawn with blending: the OES camera
        // texture's alpha channel is 0 on some GPUs (e.g. Mali on the A20), so
        // src*0 + dst*1 would leave only the dark clear color. Blend is
        // managed per-pass by the render loop (planes/markers re-enable it).
        GLES20.glDisable(GLES20.GL_BLEND)

        // ARCore fills in the texture coordinates for the current frame: the
        // INPUT must be the quad's NDC corners, otherwise the transform maps
        // (0,0) to the texture center and the whole screen samples one texel
        // (flat dark/blue field — the "no camera stream" bug on every device).
        uvs[0] = -1f; uvs[1] = -1f
        uvs[2] = 1f; uvs[3] = -1f
        uvs[4] = 1f; uvs[5] = 1f
        uvs[6] = -1f; uvs[7] = 1f
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            uvs,
            Coordinates2d.TEXTURE_NORMALIZED,
            uvs
        )
        // Diagnostic: degenerate UVs (all 0) would mean the quad samples a
        // single texel and the feed looks like a flat field. Log once.
        if (!uvsLogged) {
            uvsLogged = true
            Log.i("ArMeasure.Background", "first-frame uvs: ${uvs.joinToString()} (texture $textureId)")
        }
        uvBuffer.clear()
        uvBuffer.put(uvs)
        uvBuffer.position(0)

        GLES20.glUseProgram(program)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (textureId >= 0) GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordAttribute)
        GLES20.glVertexAttribPointer(texCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, quadIndices.size, GLES20.GL_UNSIGNED_SHORT, indexBuffer)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(texCoordAttribute)
        checkGlError("background draw")
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = vec4(texture2D(sTexture, v_TexCoord).rgb, 1.0);
            }
        """
    }
}