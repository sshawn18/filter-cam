package com.filtercam.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.filtercam.filter.FilterParams
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** Picks an RGBA8888 ES3 config that MediaCodec input surfaces accept (EGL_RECORDABLE_ANDROID), falling back to a plain one. */
class RecordableConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        fun pick(recordable: Boolean): EGLConfig? {
            val attrs = ArrayList<Int>().apply {
                addAll(listOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_RENDERABLE_TYPE, 0x40))
                if (recordable) addAll(listOf(0x3142, 1))
                add(EGL10.EGL_NONE)
            }.toIntArray()
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            return if (egl.eglChooseConfig(display, attrs, cfgs, 1, n) && n[0] > 0) cfgs[0] else null
        }
        return pick(true) ?: pick(false) ?: error("No EGL config")
    }
}

class GlFilterRenderer(private val context: Context) : GLSurfaceView.Renderer {
    @Volatile var params = FilterParams(name = "none")
    @Volatile var strength = 1f
    lateinit var glView: GLSurfaceView
    /** Invoked on the main thread after the GL context was recreated (not on first creation). */
    @Volatile var onSurfaceRecreated: (() -> Unit)? = null
    private var created = false
    @Volatile private var shaderInitFailed = false

    private var program: FilterProgram? = null
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var pending: SurfaceRequest? = null
    private var viewW = 1
    private var viewH = 1
    private var aspect = 3f / 4f
    private val st = FloatArray(16)
    private val crop = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val t0 = System.nanoTime()

    // --- video recording: a second EGL window surface (the encoder's input) drawn after the screen ---
    private class RecTarget(val surface: Surface, val w: Int, val h: Int)
    @Volatile private var recTarget: RecTarget? = null
    private var recEgl: EGLSurface = EGL14.EGL_NO_SURFACE

    /** Starts mirroring every drawn frame into [surface] (a MediaCodec input surface) at [w] x [h]. Any thread. */
    fun startRecording(surface: Surface, w: Int, h: Int) {
        recTarget = RecTarget(surface, w, h)
        glView.requestRender()
    }

    /** Stops mirroring; [onStopped] runs on the GL thread after the encoder surface is detached. */
    fun stopRecording(onStopped: () -> Unit) {
        glView.queueEvent {
            recTarget = null
            if (recEgl != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(EGL14.eglGetCurrentDisplay(), recEgl)
                recEgl = EGL14.EGL_NO_SURFACE
            }
            onStopped()
        }
        glView.requestRender()
    }

    val surfaceProvider = Preview.SurfaceProvider { req ->
        synchronized(this) { pending = req }
        glView.queueEvent { fulfil() }
        glView.requestRender()
    }

    private fun fulfil() {
        val tex = surfaceTexture
        if (program == null || tex == null) {
            if (shaderInitFailed) {
                synchronized(this) { pending.also { pending = null } }?.willNotProvideSurface()
            }
            return
        }
        val req = synchronized(this) { pending.also { pending = null } } ?: return
        tex.setDefaultBufferSize(req.resolution.width, req.resolution.height)
        val surface = Surface(tex)
        val executor = ContextCompat.getMainExecutor(context)
        req.provideSurface(surface, executor) { surface.release() }
    }

    /** Releases GL resources. Call on the GL thread only. */
    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (oesTex != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
        oesTex = 0
        program?.release()
        program = null
        recEgl = EGL14.EGL_NO_SURFACE // surface died with the old context
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        shaderInitFailed = false
        val recreated = created
        created = true
        release()
        try {
            program = FilterProgram(oes = true)
        } catch (e: Exception) {
            shaderInitFailed = true
            android.util.Log.e("FilterCam", "shader init failed", e)
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Filters unsupported on this device: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        oesTex = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTex).also { it.setOnFrameAvailableListener { glView.requestRender() } }
        fulfil()
        if (recreated) onSurfaceRecreated?.let { cb -> Handler(Looper.getMainLooper()).post { cb() } }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        val tex = surfaceTexture ?: return
        tex.updateTexImage()
        tex.getTransformMatrix(st)
        // The camera image is upright 3:4 after st (st already holds sensor rotation + front mirror).
        // Center-crop it to the frame aspect (the view is sized to the frame): C is applied to the quad coords first.
        val srcAspect = 3f / 4f
        val sx = if (aspect < srcAspect) aspect / srcAspect else 1f
        val sy = if (aspect > srcAspect) srcAspect / aspect else 1f
        Matrix.setIdentityM(crop, 0)
        Matrix.translateM(crop, 0, 0.5f, 0.5f, 0f)
        Matrix.scaleM(crop, 0, sx, sy, 1f)
        Matrix.translateM(crop, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, st, 0, crop, 0)
        val time = ((System.nanoTime() - t0) / 1_000_000L % 100_000L) / 1000f
        program?.draw(oesTex, texMatrix, params, strength, time, aspect)
        recTarget?.let { drawToEncoder(it, time) }
    }

    private fun drawToEncoder(target: RecTarget, time: Float) {
        val dpy = EGL14.eglGetCurrentDisplay()
        val ctx = EGL14.eglGetCurrentContext()
        val prevDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val prevRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        try {
            if (recEgl == EGL14.EGL_NO_SURFACE) {
                val cfgId = IntArray(1)
                EGL14.eglQueryContext(dpy, ctx, EGL14.EGL_CONFIG_ID, cfgId, 0)
                val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val n = IntArray(1)
                EGL14.eglChooseConfig(dpy, intArrayOf(EGL14.EGL_CONFIG_ID, cfgId[0], EGL14.EGL_NONE), 0, cfgs, 0, 1, n, 0)
                recEgl = EGL14.eglCreateWindowSurface(dpy, cfgs[0], target.surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (recEgl == EGL14.EGL_NO_SURFACE) { recTarget = null; return }
            }
            EGL14.eglMakeCurrent(dpy, recEgl, recEgl, ctx)
            GLES30.glViewport(0, 0, target.w, target.h)
            program?.draw(oesTex, texMatrix, params, strength, time, target.w.toFloat() / target.h)
            EGLExt.eglPresentationTimeANDROID(dpy, recEgl, System.nanoTime())
            EGL14.eglSwapBuffers(dpy, recEgl)
        } finally {
            EGL14.eglMakeCurrent(dpy, prevDraw, prevRead, ctx)
            GLES30.glViewport(0, 0, viewW, viewH)
        }
    }
}
