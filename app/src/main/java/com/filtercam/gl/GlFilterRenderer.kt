package com.filtercam.gl

import android.content.Context
import android.graphics.SurfaceTexture
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
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlFilterRenderer(private val context: Context) : GLSurfaceView.Renderer {
    @Volatile var params = FilterParams(name = "none")
    @Volatile var strength = 1f
    @Volatile var mirror = false
    lateinit var glView: GLSurfaceView
    /** Invoked on the main thread after the GL context was recreated (not on first creation). */
    @Volatile var onSurfaceRecreated: (() -> Unit)? = null
    private var created = false

    private var program: FilterProgram? = null
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var pending: SurfaceRequest? = null
    @Volatile private var rotation = 0
    private var aspect = 3f / 4f
    private val st = FloatArray(16)
    private val rot = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val t0 = System.nanoTime()

    val surfaceProvider = Preview.SurfaceProvider { req ->
        synchronized(this) { pending = req }
        glView.queueEvent { fulfil() }
        glView.requestRender()
    }

    private fun fulfil() {
        val tex = surfaceTexture ?: return
        val req = synchronized(this) { pending.also { pending = null } } ?: return
        tex.setDefaultBufferSize(req.resolution.width, req.resolution.height)
        val surface = Surface(tex)
        val executor = ContextCompat.getMainExecutor(context)
        req.setTransformationInfoListener(executor) { rotation = it.rotationDegrees }
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
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val recreated = created
        created = true
        release()
        program = FilterProgram(oes = true)
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
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        val tex = surfaceTexture ?: return
        tex.updateTexImage()
        tex.getTransformMatrix(st)
        Matrix.setIdentityM(rot, 0)
        Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rot, 0, -rotation.toFloat(), 0f, 0f, 1f)
        if (mirror) Matrix.scaleM(rot, 0, -1f, 1f, 1f)
        Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, st, 0, rot, 0)
        val time = ((System.nanoTime() - t0) / 1_000_000L % 100_000L) / 1000f
        program?.draw(oesTex, texMatrix, params, strength, time, aspect)
    }
}
