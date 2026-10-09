package com.felp.frontcomp

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * La tuberia de OpenGL que va de un descodificador a un codificador.
 *
 * Existe porque no hay forma de llevar la imagen de uno al otro sin pasar por aqui. El
 * descodificador entrega fotogramas en el formato que quiere el fabricante del aparato
 * —hay una docena de disposiciones de YUV distintas y ninguna es obligatoria—, asi que
 * leerlos a mano seria escribir un conversor por telefono. Dibujarlos en una textura y que
 * la tarjeta grafica los vuelva a leer funciona en todos, y ademas es donde el reescalado
 * sale gratis: la textura se pinta en un cuadro mas pequeno y ya esta.
 */

/** La ventana de EGL que escribe en la superficie de entrada del codificador. */
internal class InputSurface(private val surface: Surface) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
        val configs = arrayOfNulls<EGLConfig>(1)
        // EGL_RECORDABLE_ANDROID, el 0x3142: sin el, el controlador puede elegir un formato
        // que el codificador de video no sabe leer, y salen fotogramas en verde.
        val spec = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1,
            EGL14.EGL_NONE,
        )
        val found = IntArray(1)
        EGL14.eglChooseConfig(display, spec, 0, configs, 0, 1, found, 0)
        // Sin formato grabable, sin contexto o sin ventana: se suelta lo abierto antes de rendirse.
        // La superficie del codificador se quedaba sin soltar (revision del 09-10-2026).
        val config = configs[0]
        if (found[0] < 1 || config == null) giveUp("no recordable EGL config")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) giveUp("no EGL context")
        window = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (window == EGL14.EGL_NO_SURFACE) giveUp("no EGL window")
    }

    private fun giveUp(why: String): Nothing {
        release()
        error(why)
    }

    fun makeCurrent() = EGL14.eglMakeCurrent(display, window, window, context)

    /** El sello de tiempo viaja con el fotograma; sin el, el codificador los ordena mal. */
    fun setPresentationTime(nanos: Long) =
        android.opengl.EGLExt.eglPresentationTimeANDROID(display, window, nanos)

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, window)

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, window)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        window = EGL14.EGL_NO_SURFACE
        runCatching { surface.release() }
    }
}

/**
 * La superficie donde el descodificador deja cada fotograma, ya convertido en textura.
 *
 * Es una textura externa y no una normal: la imagen sigue viviendo en la memoria del
 * descodificador y el sombreador la lee de ahi. Por eso declara `samplerExternalOES`.
 */
internal class OutputSurface {
    private var textureId = 0
    private lateinit var texture: SurfaceTexture
    lateinit var surface: Surface
        private set

    private val lock = Object()
    private var ready = false
    private val matrix = FloatArray(16)
    private var program = 0

    /** Con [callbacks], el hilo por donde llegan los avisos de fotograma: ver VideoScale.frames. */
    fun setUp(callbacks: android.os.Handler) {
        textureId = newTexture()
        texture = SurfaceTexture(textureId)
        texture.setOnFrameAvailableListener({
            synchronized(lock) { ready = true; lock.notifyAll() }
        }, callbacks)
        surface = Surface(texture)
        program = buildProgram()
    }

    /** Espera al siguiente fotograma. Falso si no llego, y entonces se abandona la conversion. */
    fun awaitFrame(timeoutMs: Long = 2_500): Boolean {
        synchronized(lock) {
            val until = System.currentTimeMillis() + timeoutMs
            while (!ready) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                runCatching { lock.wait(left) }
            }
            ready = false
        }
        texture.updateTexImage()
        texture.getTransformMatrix(matrix)
        return true
    }

    /** Pinta el fotograma en todo el cuadro de destino, que es donde ocurre el reescalado. */
    fun draw(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val pos = GLES20.glGetAttribLocation(program, "aPos")
        val uv = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glUniformMatrix4fv(
            GLES20.glGetUniformLocation(program, "uSt"), 1, false, matrix, 0,
        )

        QUAD.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(pos)
        QUAD.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, QUAD)
        GLES20.glEnableVertexAttribArray(uv)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { texture.release() }
    }

    private fun newTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        // Lineal y con los bordes sujetos: al encoger, lineal promedia en vez de saltarse
        // pixeles, y sujetar el borde evita la franja que asomaria del otro lado.
        val t = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glTexParameteri(t, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(t, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(t, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(t, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            return id
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(p)
        return p
    }

    private companion object {
        val QUAD: java.nio.FloatBuffer = ByteBuffer
            .allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(
                    floatArrayOf(
                        -1f, -1f, 0f, 0f,
                        1f, -1f, 1f, 0f,
                        -1f, 1f, 0f, 1f,
                        1f, 1f, 1f, 1f,
                    ),
                )
                position(0)
            }

        const val VERTEX =
            "attribute vec4 aPos;\n" +
            "attribute vec4 aUv;\n" +
            "uniform mat4 uSt;\n" +
            "varying vec2 vUv;\n" +
            "void main() { gl_Position = aPos; vUv = (uSt * aUv).xy; }\n"

        const val FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() { gl_FragColor = texture2D(sTex, vUv); }\n"
    }
}
