package com.felp.frontcomp

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/*
 * El video de partida del Mainframe, como lo pintaria un monitor de fosforo.
 *
 * Tres cosas, en la GPU y dentro del propio reproductor:
 *
 * - ESTELA. Un fosforo no se apaga al instante: lo que se mueve deja un rastro que se va
 *   apagando, como en una pantalla de radar. Para eso hace falta el fotograma de antes, y
 *   eso solo lo tiene la cadena de efectos del reproductor; un efecto de capa de Compose ve
 *   cada fotograma suelto.
 * - TRAMADO. La luz se reduce a cuatro tonos con una trama ordenada, la de las pantallas de
 *   un bit: de cerca son puntos, de lejos es una imagen.
 * - BARRIDO. Al sintonizar, un haz dibuja la imagen de arriba abajo en vez de aparecer de golpe.
 *
 * Sale en grises: el color lo pone el filtro de fosforo del panel, que es el mismo que tiñe
 * todo lo demas del tema con su acento. Asi un cambio de acento en los ajustes llega tambien
 * aqui sin que este fichero sepa de colores.
 */
@UnstableApi
internal class PhosphorFx(
    /** Cuantos tonos quedan: cuatro es una pantalla de dos bits, que aun deja leer la imagen. */
    private val levels: Int = 4,
    /** Lo que queda de la luz de un fotograma en el siguiente. A treinta por segundo, 0,82 es
     *  un tercio de segundo de estela: se ve el rastro sin que un juego rapido se emborrone. */
    private val decay: Float = 0.82f,
    /** Lo que tarda el haz en bajar la primera vez, en segundos de video. */
    private val sweepSeconds: Float = 0.7f,
) : GlEffect {
    /**
     * Cuenta los videos: sube al poner uno nuevo en el mismo reproductor. El programa lo mira en
     * cada fotograma y, al verlo cambiar, vuelve a bajar el haz y borra la estela del anterior.
     * Asi el efecto se pone una vez y no en cada juego: cambiarlo con el video en marcha
     * reconstruia la cadena de la GPU y el video tardaba cuatro segundos en salir.
     */
    private val tuned = java.util.concurrent.atomic.AtomicInteger()

    /** Llamese al poner otro video en el reproductor. */
    fun retune() { tuned.incrementAndGet() }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(levels, decay, sweepSeconds, tuned)
}

@UnstableApi
private class Program(
    private val levels: Int,
    private val decay: Float,
    private val sweepSeconds: Float,
    private val tuned: java.util.concurrent.atomic.AtomicInteger,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ false, /* texturePoolCapacity= */ 1) {

    private val accumulate = GlProgram(VERTEX, ACCUMULATE).also { it.quad() }
    private val present = GlProgram(VERTEX, PRESENT).also { it.quad() }

    // Dos texturas de luz acumulada que se turnan: una se lee (la de antes) y en la otra se
    // escribe (la de ahora). Leer y escribir la misma en la misma pasada no esta permitido.
    private val light = IntArray(2)
    private val frames = IntArray(2)
    private var current = 0
    private var width = 0
    private var height = 0

    /** El primer instante de este video, para el barrido. Null hasta el primer fotograma. */
    private var firstUs: Long? = null
    /** Una vez bajado el haz no vuelve a bajar: un video en bucle no se resintoniza. */
    private var swept = false
    /** El ultimo video que vio este programa. Ver PhosphorFx.retune. */
    private var seen = tuned.get()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        if (inputWidth != width || inputHeight != height) {
            freeLight()
            width = inputWidth
            height = inputHeight
            for (i in 0..1) {
                light[i] = GlUtil.createTexture(width, height, /* useHighPrecisionColorComponents= */ false)
                frames[i] = GlUtil.createFboForTexture(light[i])
                // Empieza a oscuras: sin esto, la primera lectura de la textura de antes es
                // basura de la memoria de video.
                GlUtil.focusFramebufferUsingCurrentContext(frames[i], width, height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }
        }
        return Size(width, height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // Donde espera el resultado la clase base: se apunta ahora, porque la primera pasada
        // escribe en otro sitio y hay que volver aqui para la segunda.
        val out = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, out, 0)

        // Otro video en el mismo reproductor: el haz baja otra vez y la estela del anterior se va.
        val t = tuned.get()
        if (t != seen) {
            seen = t
            firstUs = null
            swept = false
            clearLight()
            GlUtil.focusFramebufferUsingCurrentContext(out[0], width, height)
        }

        // 1. La luz de ahora: la del fotograma o lo que queda de la de antes, lo que sea mas.
        val before = current
        val now = 1 - current
        GlUtil.focusFramebufferUsingCurrentContext(frames[now], width, height)
        accumulate.use()
        accumulate.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        accumulate.setSamplerTexIdUniform("uPrevSampler", light[before], 1)
        accumulate.setFloatUniform("uDecay", decay)
        accumulate.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        current = now

        // 2. Esa luz, tramada y destapada por el haz, a donde la espera el reproductor.
        GlUtil.focusFramebufferUsingCurrentContext(out[0], width, height)
        present.use()
        present.setSamplerTexIdUniform("uLightSampler", light[now], 0)
        present.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
        present.setFloatUniform("uLevels", levels.toFloat())
        present.setFloatUniform("uSweep", sweep(presentationTimeUs))
        present.setFloatUniform("uLift", LIFT)
        present.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Por donde va el haz, de cero (arriba) a uno (abajo, ya dibujado todo). */
    private fun sweep(us: Long): Float {
        if (swept) return 1f
        // Un tiempo que va hacia atras es que empezo otro video. El primer fotograma despues
        // de retune() puede ser aun del anterior, con su tiempo grande, y los del nuevo empiezan
        // cerca de cero: el haz se quedaba arriba, con el panel negro, hasta que el video nuevo
        // alcanzara el tiempo del viejo —cuarenta segundos despues de ver uno cuarenta—.
        val first = firstUs?.takeIf { it <= us } ?: us.also { firstUs = it }
        val t = ((us - first) / 1_000_000f / sweepSeconds).coerceIn(0f, 1f)
        if (t >= 1f) swept = true
        return t
    }

    override fun flush() {
        super.flush()
        // Un salto en el video no deja estela de lo que habia antes del salto.
        clearLight()
    }

    private fun clearLight() {
        for (i in 0..1) {
            if (frames[i] == 0) continue
            GlUtil.focusFramebufferUsingCurrentContext(frames[i], width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
    }

    override fun release() {
        super.release()
        accumulate.delete()
        present.delete()
        freeLight()
    }

    private fun freeLight() {
        for (i in 0..1) {
            if (frames[i] != 0) GlUtil.deleteFbo(frames[i])
            if (light[i] != 0) GlUtil.deleteTexture(light[i])
            frames[i] = 0
            light[i] = 0
        }
    }

    private companion object {
        /**
         * La curva con que se levantan las sombras antes de tramar. Con cuatro tonos, todo lo
         * que no llega a un sexto de luz se queda en negro, y medio catalogo son juegos oscuros:
         * Castlevania salia como puntos sueltos sobre negro. Con 0,55 un gris al diez por ciento
         * sube al veintiocho y ya tiene trama.
         */
        const val LIFT = 0.55f

        /** El rectangulo que cubre el fotograma entero, en las coordenadas de la GPU. */
        fun GlProgram.quad() = setBufferAttribute(
            "aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )

        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        // La luz de cada punto: la del fotograma, o lo que queda de la de antes si era mas. Es
        // lo que hace un fosforo: brilla con lo que le llega y, cuando deja de llegarle, se
        // apaga poco a poco.
        const val ACCUMULATE = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform sampler2D uPrevSampler;
            uniform float uDecay;
            varying vec2 vTexSamplingCoord;
            void main() {
              vec3 c = texture2D(uTexSampler, vTexSamplingCoord).rgb;
              float l = dot(c, vec3(0.299, 0.587, 0.114));
              float p = texture2D(uPrevSampler, vTexSamplingCoord).r * uDecay;
              float a = max(l, p);
              gl_FragColor = vec4(a, a, a, 1.0);
            }
        """

        // La trama ordenada de cuatro por cuatro (Bayer), sacada con aritmetica porque esta
        // version del lenguaje de la GPU no tiene operaciones de bits. Y el haz: por debajo de
        // el, todavia negro; en el, una raya que brilla.
        const val PRESENT = """
            precision mediump float;
            uniform sampler2D uLightSampler;
            uniform vec2 uSize;
            uniform float uLevels;
            uniform float uSweep;
            uniform float uLift;
            varying vec2 vTexSamplingCoord;
            float bayer2(vec2 a) { a = floor(a); return fract(a.x / 2.0 + a.y * a.y * 0.75); }
            float bayer4(vec2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }
            void main() {
              float a = pow(texture2D(uLightSampler, vTexSamplingCoord).r, uLift);
              float n = uLevels - 1.0;
              float q = floor(a * n + bayer4(vTexSamplingCoord * uSize)) / n;
              float y = 1.0 - vTexSamplingCoord.y;
              float shown = step(y, uSweep);
              float beam = (1.0 - smoothstep(0.0, 0.02, abs(y - uSweep))) * (1.0 - step(1.0, uSweep));
              float o = max(min(q, 1.0) * shown, beam);
              gl_FragColor = vec4(o, o, o, 1.0);
            }
        """
    }
}
