package com.felp.frontcomp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ScreenScraper, la base de datos de la comunidad.
 *
 * Es la unica de las tres que cataloga recreativas de verdad y la que mas caratulas
 * regionales tiene. A cambio pide dos parejas de credenciales, no una: la cuenta de quien
 * la usa y otra de desarrollador. Eso no es un capricho de este programa, es como esta
 * hecha su interfaz: una peticion sin la de desarrollador contesta «Verifier vos
 * identifiants developpeur» y un 403, se ponga la cuenta de usuario que se ponga. La
 * segunda se pide en su propio foro y la dan a quien escribe un programa.
 *
 * De una en una y sin prisa: una cuenta gratuita tiene un solo hilo, y mandarle cuatro
 * peticiones a la vez es la forma de que cierre la puerta a las cuatro.
 */
internal class ScreenScraperSource : CoverSource {
    override val id = "screenscraper"
    override val name = "ScreenScraper"

    private var user = ""
    private var pass = ""
    private var devId = ""
    private var devPass = ""

    private val oneAtATime = Mutex()
    private var lastAt = 0L

    /**
     * Lo ultimo que contesto el servicio cuando no fue un juego.
     *
     * Sus negativas son una linea de texto en frances, no un JSON, y dicen exactamente que
     * falta: la pareja de desarrollador, la cuenta, o el cupo del dia. Guardarla es la
     * diferencia entre un informe que dice «no encontrado» y uno que dice que hay que
     * arreglar en la pantalla de ajustes.
     */
    @Volatile
    private var lastError: String? = null

    /**
     * Cuantas negativas seguidas lleva.
     *
     * Con una credencial mal puesta fallan las cincuenta y ocho igual, de una en una y con
     * un segundo de espera cada una. Al tercer no seguido se deja de preguntar: el fallo ya
     * esta diagnosticado y lo demas es hacer esperar a quien mira la pantalla.
     */
    @Volatile
    private var refusals = 0

    override fun ready(prefs: Prefs): Boolean {
        user = prefs.credential("ss.user")
        pass = prefs.credential("ss.pass")
        devId = prefs.credential("ss.devid")
        devPass = prefs.credential("ss.devpass")
        return listOf(user, pass, devId, devPass).all(String::isNotEmpty)
    }

    override fun applies(sys: SystemDef) = platformOf(sys.id) != null

    /** Lo que el servicio dijo la ultima vez que dijo que no, tal cual, para el informe. */
    override fun why(sys: SystemDef): String? = lastError

    override suspend fun cover(sys: SystemDef, game: Game): ByteArray? {
        val platform = platformOf(sys.id) ?: return null
        if (refusals >= GIVE_UP) return null
        val text = ask(platform, game.fileName) ?: return null
        val jeu = runCatching {
            JSONObject(text).optJSONObject("response")?.optJSONObject("jeu")
        }.getOrNull() ?: return null
        val url = boxArt(jeu, game.region) ?: return null
        return withContext(Dispatchers.IO) { httpBytes(url) }
    }

    /** Una peticion cada vez y con un segundo entre ellas, que es lo que admite una cuenta libre. */
    private suspend fun ask(platform: Int, romName: String): String? = oneAtATime.withLock {
        val wait = lastAt + 1_000L - System.currentTimeMillis()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val url = "https://api.screenscraper.fr/api2/jeuInfos.php" +
                "?devid=${enc(devId)}&devpassword=${enc(devPass)}&softname=ludolog&output=json" +
                "&ssid=${enc(user)}&sspassword=${enc(pass)}" +
                "&systemeid=$platform&romtype=rom&romnom=${enc(romName)}"
            runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "Ludolog")
                }
                val code = c.responseCode
                if (code == HttpURLConnection.HTTP_OK) {
                    refusals = 0
                    lastError = null
                    c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                } else {
                    // Lo que no es 200 es una linea en frances diciendo que falta, no un JSON.
                    // Un 404 aparte: ese es «ese juego no lo tengo», una respuesta legitima
                    // que no dice nada malo de las credenciales y no debe frenar la pasada.
                    val said = c.errorStream?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8)?.trim()?.lineSequence()?.firstOrNull()
                    if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                        refusals = 0
                    } else {
                        refusals++
                        lastError = said?.takeIf(String::isNotEmpty)
                            ?: "the service answered $code and said nothing"
                    }
                    null
                }
            }.getOrNull()
        }
    }

    /**
     * Cual de las imagenes del juego es la caratula que queremos.
     *
     * Guardan muchas por juego —cajas, cartuchos, capturas, videos— y varias versiones de
     * cada una por region. Se prefiere la de la region del propio volcado, luego la
     * mundial, y solo entonces cualquiera: poner la caja japonesa sobre un volcado europeo
     * no esta mal del todo, pero se nota en una estanteria entera.
     */
    private fun boxArt(jeu: JSONObject, region: String?): String? {
        val medias = jeu.optJSONArray("medias") ?: return null
        val boxes = (0 until medias.length()).mapNotNull { medias.optJSONObject(it) }
            .filter { it.optString("type") == "box-2D" && it.optString("url").isNotEmpty() }
        if (boxes.isEmpty()) return null
        val mine = regionOf(region)
        return (boxes.firstOrNull { it.optString("region") == mine }
            ?: boxes.firstOrNull { it.optString("region") == "wor" }
            ?: boxes.first()).optString("url")
    }

    /** Como escriben ellos las regiones que el nombre del fichero trae en ingles. */
    private fun regionOf(region: String?): String = when {
        region == null -> "wor"
        region.contains("usa", true) || region.contains("ntsc-u", true) -> "us"
        region.contains("euro", true) || region.contains("pal", true) -> "eu"
        region.contains("japan", true) || region.contains("jp", true) -> "jp"
        region.contains("spain", true) -> "sp"
        region.contains("world", true) -> "wor"
        else -> "wor"
    }

    private fun platformOf(systemId: String): Int? = PLATFORMS[systemId.lowercase()]

    /**
     * Como numera ScreenScraper cada consola.
     *
     * Son sus numeros, no los de nadie mas, y van en la peticion. Una consola que no este
     * en esta tabla se salta esta fuente entera en vez de preguntar por el numero
     * equivocado, porque preguntar por el equivocado es como llegan las caratulas de otro
     * juego que se llama parecido en otra maquina.
     */
    private companion object {
        /** Negativas seguidas tras las cuales se deja de insistir. */
        const val GIVE_UP = 3

        val PLATFORMS: Map<String, Int> = mapOf(
            "megadrive" to 1, "genesis" to 1, "mastersystem" to 2, "nes" to 3, "famicom" to 3,
            "snes" to 4, "sfc" to 4, "gb" to 9, "gbc" to 10, "virtualboy" to 11, "gba" to 12,
            "gamecube" to 13, "n64" to 14, "nds" to 15, "wii" to 16, "3ds" to 17, "wiiu" to 18,
            "sega32x" to 19, "segacd" to 20, "megacd" to 20, "gamegear" to 21, "saturn" to 22,
            "dreamcast" to 23, "ngp" to 25, "atari2600" to 26, "atarijaguar" to 27,
            "atarilynx" to 28, "3do" to 29, "pcengine" to 31, "atari5200" to 40,
            "atari7800" to 41, "atarist" to 42, "wonderswan" to 45, "wonderswancolor" to 46,
            "colecovision" to 48, "psx" to 57, "ps2" to 58, "ps3" to 59, "psp" to 61,
            "amiga" to 64, "amstradcpc" to 65, "c64" to 66, "arcade" to 75, "mame" to 75,
            "zxspectrum" to 76, "ngpc" to 82, "vectrex" to 102, "fds" to 106, "sg1000" to 109,
            "msx" to 113, "intellivision" to 115, "neogeo" to 142, "psvita" to 62,
            "switch" to 225, "dos" to 135, "pc" to 138, "xbox" to 32, "xbox360" to 33,
        )
    }
}
