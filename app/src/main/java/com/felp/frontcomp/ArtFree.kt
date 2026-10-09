package com.felp.frontcomp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/*
 * Las fuentes que no piden cuenta y aciertan igual.
 *
 * Las dos de aqui identifican el juego por un numero, no por su nombre: el de Steam lo
 * lleva dentro el propio fichero, y el de GameTDB viene en la ficha del catalogo. Un numero
 * no se parece a otro numero, asi que estas dos no pueden traer la caratula equivocada, que
 * es el unico error de un scraper que de verdad molesta.
 *
 * Hubo una tercera, la de Switch por el identificador de titulo del nombre del fichero, en
 * tinfoil.media; se quito el 09-10-2026 porque daba 503 a todo. GameTDB cubre Switch.
 */

/* ----------------------------------------------------------------------------- Steam */

/**
 * La caratula de una entrada de Steam.
 *
 * Un fichero .steam guarda dentro el numero de la aplicacion, y con ese numero la imagen
 * esta en una direccion fija. Cuando el fichero no lo trae se le pregunta a la tienda por
 * el nombre, y ahi si hay que desconfiar: quien pregunta por «Darkwood» recibe «Darkwood 2»
 * de primero. Por eso solo vale la respuesta cuyo nombre es el mismo titulo y ninguna otra.
 */
internal class SteamSource : CoverSource {
    override val id = "steam"
    override val name = "Steam"

    override fun ready(prefs: Prefs) = true
    override fun applies(sys: SystemDef) = sys.id in PC_SYSTEMS

    override suspend fun cover(sys: SystemDef, game: Game, name: String?): ByteArray? =
        withContext(Dispatchers.IO) {
            val app = appIdOf(game) ?: return@withContext null
            SHAPES.firstNotNullOfOrNull { shape -> httpBytes("$CDN/$app/$shape") }
        }

    private fun appIdOf(game: Game): String? = inside(game) ?: search(game)

    /** Lo que el propio fichero dice de si mismo, que es un numero suelto o un enlace steam://. */
    private fun inside(game: Game): String? {
        val f = File(game.path)
        if (!f.isFile || f.length() > 8192) return null
        val text = runCatching { f.readText() }.getOrNull()?.trim().orEmpty()
        if (text.length in 1..8 && text.all(Char::isDigit)) return text
        return Regex("""(?:rungameid/|steam/apps/|appid[=:\s]+)(\d{3,8})""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
    }

    /**
     * La tienda, por nombre, y solo si contesta con el mismo titulo.
     *
     * Una secuela se llama casi igual que el original y sale antes en los resultados. Sin
     * esta regla, la biblioteca acaba con la caratula de la segunda parte sobre la primera
     * y nadie se entera hasta que la mira de cerca.
     */
    private fun search(game: Game): String? {
        val term = game.title.ifEmpty { game.fileName.substringBeforeLast('.') }
        val text = httpBytes(
            "https://store.steampowered.com/api/storesearch/?term=${enc(term)}&cc=us&l=en", 2,
        )?.toString(Charsets.UTF_8) ?: return null
        val items = runCatching { JSONObject(text).optJSONArray("items") }.getOrNull() ?: return null
        val mine = MatchKey.of(term)
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (MatchKey.of(item.optString("name")) != mine) continue
            return item.optInt("id").takeIf { it > 0 }?.toString()
        }
        return null
    }

    private companion object {
        val PC_SYSTEMS = setOf("pc", "steam", "windows", "dos")
        const val CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps"
        // De mas alta a mas baja: la vertical de la biblioteca es la que parece una caratula.
        val SHAPES = listOf("library_600x900_2x.jpg", "library_600x900.jpg", "header.jpg")
    }
}

/* --------------------------------------------------------------------------- GameTDB */

/**
 * Las caratulas de GameTDB, por el ID que la ficha trae del catalogo: GameCube, Wii, Wii U, DS,
 * 3DS, PS3 y Switch.
 *
 * Por un numero y no por el nombre, asi que no se equivoca de juego. Va detras de libretro, que
 * tiene las caratulas mas grandes, y tapa sus huecos: los juegos de Switch, que libretro no
 * tiene, y los de PS3 con nombre de otra region. Sin ficha, o con una sin ID, no pregunta nada.
 */
internal class GameTdbSource : CoverSource {
    override val id = "gametdb"
    override val name = "GameTDB"

    override fun ready(prefs: Prefs) = true
    override fun applies(sys: SystemDef) = sys.id in PLATFORM

    override suspend fun cover(sys: SystemDef, game: Game, name: String?): ByteArray? {
        val id = Dossiers.get(game)?.gametdb ?: return null
        val platform = PLATFORM[sys.id] ?: return null
        return withContext(Dispatchers.IO) { urls(platform, id).firstNotNullOfOrNull { httpBytes(it) } }
    }

    companion object {
        /** Donde guarda GameTDB cada consola: GameCube va con las de Wii. */
        private val PLATFORM = mapOf(
            "gamecube" to "wii", "wii" to "wii", "wiiu" to "wiiu", "nds" to "ds", "3ds" to "3ds",
            "ps3" to "ps3", "switch" to "switch",
        )

        /**
         * Las direcciones donde puede estar, por orden: la de su region y despues las de las
         * regiones que mas tienen. Las de Wii solo existen pequeñas y en PNG; las demas, medianas
         * y en JPEG.
         */
        fun urls(platform: String, id: String): List<String> {
            val (dir, ext) = if (platform == "wii") "cover" to "png" else "coverM" to "jpg"
            return regions(platform, id).map { "https://art.gametdb.com/$platform/$dir/$it/$id.$ext" }
        }

        /**
         * La region va en el ID: la cuarta letra en los de Nintendo («R3ME01», E de Estados
         * Unidos) y la tercera en los seriales de Sony («BCES00510», E de Europa). Los de Switch
         * no la llevan.
         */
        private fun regions(platform: String, id: String): List<String> {
            val own = when (platform) {
                "switch" -> null
                "ps3" -> SONY[id.getOrNull(2)?.uppercaseChar()]
                else -> NINTENDO[id.getOrNull(3)?.uppercaseChar()]
            }
            return listOfNotNull(own, "US", "EN", "JA").distinct()
        }

        private val SONY = mapOf('U' to "US", 'E' to "EN", 'J' to "JA", 'A' to "ZH", 'H' to "ZH", 'K' to "KO")
        private val NINTENDO = mapOf(
            'E' to "US", 'P' to "EN", 'J' to "JA", 'K' to "KO", 'D' to "DE", 'F' to "FR", 'S' to "ES",
            'I' to "IT", 'H' to "NL", 'U' to "AU", 'R' to "RU", 'W' to "ZH", 'C' to "ZH",
            'X' to "EN", 'Y' to "EN", 'Z' to "EN", 'L' to "EN", 'M' to "EN", 'V' to "EN",
        )
    }
}

/* --------------------------------------------------------------------------- comunes */

internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/**
 * Una descarga, o null.
 *
 * El minimo de bytes no es cosmetico: varios de estos servidores contestan a lo que no
 * tienen con un 200 y una pagina de error diminuta, y esa pagina guardada como .png es
 * exactamente el hueco que el scraper cree haber rellenado.
 */
internal fun httpBytes(url: String, minBytes: Int = 2_000): ByteArray? = runCatching {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 25_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Ludolog")
    }
    if (c.responseCode != HttpURLConnection.HTTP_OK) {
        c.disconnect()
        null
    } else {
        c.inputStream.use { it.readBytes() }.takeIf { it.size >= minBytes }
    }
}.getOrNull()
