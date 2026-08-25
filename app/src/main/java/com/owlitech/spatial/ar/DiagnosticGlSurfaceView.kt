package com.owlitech.spatial.ar

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.SurfaceHolder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DiagnosticGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), DiagnosticSurfacePort {
    private var controller: ArDiagnosticSessionController? = null
    private var surfaceLifecycleResumed = true
    private val diagnosticRenderer = DiagnosticRenderer(
        currentDisplayRotation = { display?.rotation ?: 0 },
        controller = { controller },
    )

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(diagnosticRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun attachController(controller: ArDiagnosticSessionController) {
        check(this.controller == null) { "A diagnostic controller is already attached." }
        this.controller = controller
    }

    override fun resumeSurface() {
        if (surfaceLifecycleResumed) return
        surfaceLifecycleResumed = true
        onResume()
    }

    override fun pauseSurface() {
        if (!surfaceLifecycleResumed) return
        surfaceLifecycleResumed = false
        // Stop controller eligibility before GLSurfaceView.onPause() waits for the render thread.
        // The EGL context may survive, so the context-owned camera texture remains valid.
        controller?.onRenderSurfaceUnavailable()
        onPause()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        controller?.onRenderSurfaceUnavailable()
        super.surfaceDestroyed(holder)
    }
}

private class DiagnosticRenderer(
    private val currentDisplayRotation: () -> Int,
    private val controller: () -> ArDiagnosticSessionController?,
) : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        // GLSurfaceView invokes this callback for renderer start / EGL-context recreation.
        controller()?.onSurfaceCreated(textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // This also runs when a preserved context receives a new EGL window surface on foreground.
        controller()?.onSurfaceChanged(currentDisplayRotation(), width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        controller()?.onDisplayRotationChanged(currentDisplayRotation())
        controller()?.updateFrame()
    }
}
