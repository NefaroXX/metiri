package com.metiri.armeasure

import android.app.Activity
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Session
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView renderer for Phase 2: camera background passthrough + debug
 * plane overlay. Session access is lazy (the session may be created after the
 * GL thread starts, since the capability flow is async) — texture/display
 * setup is deferred until the session is available.
 */
class ArRenderer(
    private val activity: Activity,
    private val sessionProvider: () -> Session?,
    private val onPlaneFound: (Int) -> Unit,
) : GLSurfaceView.Renderer {

    private val background = BackgroundRenderer()
    private val planes = PlaneRenderer()
    private var textureId = -1
    private var textureNameSet = false
    private var displaySet = false
    private var viewWidth = 0
    private var viewHeight = 0

    @Suppress("DEPRECATION")
    private val displayRotation: Int
        get() = activity.windowManager.defaultDisplay.rotation

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        textureId = createOesTexture()
        background.textureId = textureId
        background.createOnGlThread()
        planes.createOnGlThread()
        checkGlError("surface created")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        displaySet = false
    }

    override fun onDrawFrame(gl: GL10?) {
        val session = sessionProvider()
        if (session == null) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            return
        }
        try {
            if (!textureNameSet) {
                session.setCameraTextureName(textureId)
                textureNameSet = true
            }
            if (!displaySet) {
                session.setDisplayGeometry(displayRotation, viewWidth, viewHeight)
                displaySet = true
            }
            val frame = session.update()
            background.draw(frame)
            planes.onDrawFrame(frame, session, onPlaneFound)
        } catch (t: Throwable) {
            Log.e("ArMeasure.Renderer", "frame update failed", t)
        }
    }

    private fun createOesTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }
}