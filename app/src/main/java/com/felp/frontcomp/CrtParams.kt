package com.felp.frontcomp

// Separado de Crt.kt sin cambios: es solo datos, y asi lo compila tambien Ludolog Link del PC.
// Sin nada de Android aqui: ver docs/ludolog-link.md.

/** How hard the tube is pushed. Part of the theme, because it is a look, not a feature. */
data class CrtParams(
    /** Barrel distortion. 0 is a flat panel; past ~0.2 it stops looking like glass. */
    val curvature: Float = 0.10f,
    /** Scanline depth. */
    val scanline: Float = 0.35f,
    /** Aperture-grille tint depth. */
    val mask: Float = 0.30f,
    /** Compensates for how much the scanlines and the mask swallow. */
    val gain: Float = 1.30f,
    /**
     * Cuanto se derrama lo claro sobre lo que tiene al lado, DENTRO del cristal.
     *
     * Apagado por defecto, y la razon es de escala: la pantalla mide 33 pixeles de sala de
     * ancho, asi que la floracion de un tubo de verdad cae POR DEBAJO del pixel. Lo unico
     * que se puede dibujar a este tamaño es un desenfoque, y comparados los dos en el
     * aparato, lo que hacia era levantar los bloques oscuros pegados a los claros y comerse
     * el contraste — justo lo que hacia que la imagen se leyera como un video moderno puesto
     * encima de una habitacion de 384 pixeles.
     *
     * Se queda como mando del tema porque en una television que ocupe media pantalla la
     * cuenta cambia y entonces si hay sitio para dibujarla.
     *
     * El derrame que si se ve a este tamaño es el de FUERA: ver [halo].
     */
    val bloom: Float = 0.0f,
    /**
     * Cuanta luz sale FUERA del cristal, sobre el mueble y la pared.
     *
     * Es lo que separa una television encendida de un recorte de video pegado dentro de un
     * mueble. Medido en el aparato antes de existir esto: dentro del tubo la imagen estaba a
     * 70 sobre 255 y el bisel, a un pixel de distancia, a 1. Un salto de setenta a uno en un
     * pixel no lo hace ninguna fuente de luz real; el bisel esta a dos dedos del fosforo y
     * de refilon, que es justo la peor postura para no recibir nada.
     *
     * No es lo mismo que el pase `tv` de la sala: aquel es la luz repartida por la
     * habitacion, horneada, y este es el campo cercano —el propio mueble— que ninguna luz
     * horneada puede dar porque el bisel es coplanar con la pantalla y no la ve.
     *
     * A cero por decision de aspecto, no porque no funcionara: medido, subia el bisel de 1 a
     * 15 sobre 255 y dejaba la pared lejana igual. Se probo a 0.70 y a 0.38 en el aparato y
     * la sala se lee mejor sin el, con el corte seco del cristal. Queda el mando por si al
     * cambiar de escena vuelve a hacer falta. Con esto a cero la capa del video ni siquiera
     * se agranda, asi que no cuesta nada.
     */
    val halo: Float = 0f,
    /**
     * Cuanto color le queda a la señal. 1 es el video tal cual.
     *
     * La television es lo unico con color saturado de un cuarto que es olivas y ambar, y ese
     * desajuste es la mitad de lo que la separa de la escena. Un tubo de los noventa tampoco
     * daba el color de un fichero digital: el fosforo gastado, el vidrio sucio y la señal por
     * antena se comen saturacion antes de llegar al ojo.
     */
    val saturation: Float = 0.80f,
    /**
     * De que color tira la señal.
     *
     * Ambar muy flojo, que es a lo que tira un tubo con el fosforo cansado y lo que acerca la
     * imagen a la paleta de la sala. Es un tinte, no un filtro: se nota comparando, no
     * mirando.
     */
    val tint: androidx.compose.ui.graphics.Color =
        androidx.compose.ui.graphics.Color(1.0f, 0.96f, 0.87f),
) {
    companion object {
        val Off = CrtParams(
            curvature = 0f, scanline = 0f, mask = 0f, gain = 1f, halo = 0f, saturation = 1f,
            tint = androidx.compose.ui.graphics.Color.White,
        )
    }
}
