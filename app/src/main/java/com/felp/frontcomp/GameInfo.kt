package com.felp.frontcomp

import java.io.File

/**
 * Lo que se sabe de un juego mas alla de su nombre: genero, año, quien lo hizo y lo que ocupa.
 *
 * Lo principal sale de la ficha propia de cada juego (Dossiers), que llena el catalogo de
 * Ludolog. Si el usuario tiene ademas las listas `gamelist.xml` de ES-DE en
 * `<unidad>/ES-DE/gamelists/<consola>/`, tapan lo que la ficha no sepa. Son de ES-DE y no se
 * tocan: solo se leen, una vez por lista, y se guardan en memoria mientras viva el proceso.
 *
 * Y lo que nadie sabe lo dice el propio fichero: cuanto pesa, en que formato viene y de que
 * region es. Con eso hay siempre algo que contar, aunque no haya catalogo ni lista ninguna.
 */
internal object GameInfo {

    /** Lo que trae una entrada de la lista. Vacio si la lista no lo dice. */
    data class Meta(
        val genre: String? = null,
        val year: String? = null,
        val developer: String? = null,
        val players: String? = null,
    )

    /** Las listas ya leidas, por ruta del fichero: ruta relativa del juego -> lo que dice. */
    private val lists = HashMap<String, Map<String, Meta>>()

    /** Donde ES-DE guarda sus listas, en cada unidad montada. */
    private fun listRoots(): List<File> =
        DataHome.volumes().map { File(it, "ES-DE/gamelists") }.filter { it.isDirectory }

    /**
     * Lo que la lista de ES-DE dice de este juego, o nulo.
     *
     * La lista va por consola y nombra cada juego por su ruta RELATIVA a la carpeta de esa
     * consola —`./Mega Man X6.img`, `./Final Fantasy VII/disc1.chd`—, asi que se sube por las
     * carpetas del juego hasta dar con una que tenga lista, y desde ahi se escribe la ruta.
     */
    fun meta(game: Game): Meta? {
        if (game.path.isEmpty()) return null
        val file = File(game.path)
        val roots = listRoots()
        var dir = file.parentFile
        var depth = 0
        while (dir != null && depth < 4) {
            for (root in roots) {
                val list = File(root, "${dir.name}/gamelist.xml")
                if (!list.isFile) continue
                val rel = ("./" + file.relativeTo(dir).path.replace('\\', '/')).lowercase()
                val known = entries(list)
                // Y si no esta con su nombre exacto, sin la extension: el juego que ES-DE
                // conocio como .bin y que despues se paso a .chd sigue siendo el mismo juego.
                return known[rel] ?: known["~" + stem(rel)]
            }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    private fun entries(list: File): Map<String, Meta> = synchronized(lists) {
        lists.getOrPut(list.path) { runCatching { parse(list.readText()) }.getOrDefault(emptyMap()) }
    }

    /**
     * La lista, a mano y no con un lector de XML.
     *
     * Un `gamelist.xml` de ES-DE no es XML valido: puede llevar DOS raices —un
     * `<alternativeEmulator>` delante del `<gameList>`— y un lector estricto se para en la
     * segunda. La forma de cada entrada es plana y fija, asi que se cortan los `<game>` y se
     * sacan las etiquetas que interesan.
     */
    private fun parse(text: String): Map<String, Meta> {
        val out = HashMap<String, Meta>()
        for (m in GAME.findAll(text)) {
            val body = m.groupValues[1]
            fun tag(name: String): String? =
                Regex("<$name>([^<]*)</$name>").find(body)?.groupValues?.get(1)
                    ?.let(::unescape)?.trim()?.takeIf(String::isNotEmpty)
            val path = tag("path")?.lowercase() ?: continue
            val meta = Meta(
                genre = tag("genre"),
                year = tag("releasedate")?.take(4)?.takeIf { it.all(Char::isDigit) },
                developer = tag("developer"),
                players = tag("players"),
            )
            out[path] = meta
            out.putIfAbsent("~" + stem(path), meta)
        }
        return out
    }

    /** La ruta sin la extension del fichero, si la tiene: la de la carpeta no cuenta. */
    private fun stem(path: String): String {
        val dot = path.lastIndexOf('.')
        return if (dot > path.lastIndexOf('/')) path.substring(0, dot) else path
    }

    // Con atributos o sin ellos: las listas que vienen de RetroPie, Batocera o Skraper
    // escriben `<game id="…" source="…">`, y ES-DE deja esas entradas como estaban. Con
    // `<game>` a secas se saltaban, y esos juegos salian sin genero, año ni estudio.
    private val GAME = Regex("""<game\b[^>]*>(.*?)</game>""", RegexOption.DOT_MATCHES_ALL)

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    /**
     * Lo que ocupa el juego en la tarjeta, contando todos sus discos.
     *
     * Un `.cue` pesa unos bytes: lo que ocupa son los `.bin` que nombra. Un `.m3u` es una lista de
     * discos. En los dos casos se suman los ficheros de verdad, que es lo que el usuario quiere
     * saber cuando mira cuanto pesa un juego.
     */
    fun bytes(game: Game): Long? {
        val f = File(game.path)
        if (!f.isFile) return null
        val ext = f.extension.lowercase()
        val parts = when (ext) {
            "cue" -> runCatching {
                f.readLines().mapNotNull { CUE_FILE.find(it)?.groupValues?.get(1) }
                    .map { File(f.parentFile, it) }
            }.getOrDefault(emptyList())
            "m3u" -> runCatching {
                f.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { File(f.parentFile, it) }
            }.getOrDefault(emptyList())
            else -> emptyList()
        }.filter(File::isFile)
        return if (parts.isEmpty()) f.length() else parts.sumOf { (if (it.extension.lowercase() == "cue") bytes(Game(it.path, game.systemId, game.title)) else null) ?: it.length() }
    }

    private val CUE_FILE = Regex("""^\s*FILE\s+"([^"]+)"""", RegexOption.IGNORE_CASE)

    /**
     * Como mucho cuatro cosas que contar de un juego, rotulo y valor.
     *
     * Primero la ficha propia (Dossiers, llenada con el catalogo): genero, año y quien lo hizo.
     * La lista de ES-DE solo tapa lo que la ficha no sepa, si el usuario la tiene: Ludolog ya no
     * depende de ella. Y siempre lo que pesa. Si nadie dice algo, el hueco lo llena lo que dice
     * el fichero —formato, region—; cuatro como tope, porque son lineas que salen de una imagen
     * y con mas se tapan entre si.
     */
    fun facts(game: Game): List<Pair<String, String>> {
        if (game.appPackage != null) return emptyList()
        val own = runCatching { Dossiers.get(game) }.getOrNull()
        val genre = own?.genres?.let(Genres::label)
        val year = own?.year
        val dev = own?.developer?.substringBefore('|')
        val meta = if (genre == null || year == null || dev == null) runCatching { meta(game) }.getOrNull() else null
        val out = mutableListOf<Pair<String, String>>()
        (genre ?: meta?.genre)?.let { out += "GENRE" to short(it) }
        (year ?: meta?.year)?.let { out += "YEAR" to it }
        (dev ?: meta?.developer)?.let { out += "DEV" to short(it) }
        bytes(game)?.let { out += "SIZE" to size(it) }
        if (out.size < 4) File(game.path).extension.takeIf { it.isNotEmpty() }
            ?.let { out += "FORMAT" to it.uppercase() }
        if (out.size < 4) game.region?.let { out += "REGION" to it.uppercase() }
        return out.take(4)
    }

    /**
     * Lo que cabe al lado de una linea: un rotulo largo se corta con puntos, y por una palabra
     * entera si la hay. Dieciocho es lo que entra en la columna del Mainframe con la letra
     * encogida lo que se deja encoger; cortando a mitad de palabra salia «Action, Adventu…»,
     * y por la palabra sale «Konami Computer…».
     */
    private fun short(s: String, max: Int = 18): String {
        if (s.length <= max) return s
        val cut = s.take(max - 1)
        // Si el corte cae dentro de una palabra, hasta el espacio de antes, si deja algo.
        val whole = if (s[max - 1].isLetterOrDigit()) cut.substringBeforeLast(' ', cut) else cut
        val kept = if (whole.length >= max / 2) whole else cut
        return kept.trimEnd(' ', ',', ';', '/', '-') + "…"
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%d KB".format((bytes / 1024).coerceAtLeast(1))
    }
}
