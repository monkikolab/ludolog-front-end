package com.felp.frontcomp

/**
 * Title matching between a ROM on disk and a thumbnail in a remote set.
 *
 * Pure Kotlin with no I/O so the rules below can be tested exhaustively — matching is
 * where a scraper is actually right or wrong, and it is not something to find out by
 * watching covers land on the wrong games.
 */
object MatchKey {

    /** Articles that No-Intro moves to the end: "The 7th Saga" is filed as "7th Saga, The". */
    private val TRAILING_ARTICLES = listOf(
        "the", "a", "an", "los", "las", "el", "la", "le", "les", "die", "der", "das", "il",
    )

    private val ROMAN = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6",
        "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10",
    )

    // Los patrones se compilan UNA vez, no en cada llamada.
    //
    // of() y regionsOf() se llaman una vez por cada nombre de la lista remota, y esas
    // listas traen decenas de miles de nombres: PlayStation sola pasa de nueve mil.
    // Compilar la expresion dentro de la funcion salia a tres compilaciones por nombre.
    private val BRACKETS = Regex("""[\(\[][^\)\]]*[\)\]]""")
    private val BRACKET_GROUP = Regex("""[\(\[]([^\)\]]*)[\)\]]""")
    private val SPACES = Regex("""\s+""")
    private val SIDE_TAGS =
        listOf("(beta", "(proto", "(demo", "(sample", "(pirate", "(unl")

    /**
     * Collapses a title to the form used for comparison.
     *
     * The steps exist because ROM sets and thumbnail sets disagree in predictable ways:
     * bracketed metadata is present in one and not the other, articles are moved to the
     * end, subtitle separators differ (" - " vs ": "), and sequels are written in roman
     * numerals on one side and arabic on the other.
     */
    fun of(rawName: String): String = words(rawName).joinToString("")

    /**
     * Un nombre de fichero sin su extension, para compararlo como titulo.
     *
     * Aparte y no dentro de [words]: alli se cortaba por el ultimo punto SIEMPRE, tambien en
     * los titulos, que no traen extension. «Super Mario Bros. 3» quedaba en «Super Mario
     * Bros», la clave del primero, y el scraper le ponia esa caratula sin avisar. Solo quien
     * sabe que tiene un nombre de fichero entre manos lo corta.
     */
    fun stem(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    /**
     * Las palabras de un titulo, con las mismas reglas que [of] pero sin juntarlas: para
     * comparar por palabras un nombre escrito a mano, donde puede sobrar o faltar alguna.
     *
     * Un titulo, no un nombre de fichero: la extension se quita antes, con [stem].
     */
    fun words(rawName: String): List<String> {
        var s = BRACKETS.replace(rawName, " ")
        s = s.lowercase()
        s = s.replace('_', ' ').replace(':', ' ').replace('-', ' ')
        // «&» y «and» no cuentan. libretro cambia por «_» los caracteres que no caben en un
        // nombre de fichero, y «&» es uno: «Sonic & Knuckles (World)» alli es «Sonic _
        // Knuckles (World)». Leido «&» como «and», ningun nombre No-Intro con «&» casaba con
        // su caratula; sin ninguno de los dos, «Chip & Dale», «Chip and Dale» y «Chip _ Dale»
        // son la misma clave.
        s = s.replace("&", " ")
        s = SPACES.replace(s, " ").trim()

        // "7th saga, the" -> "the 7th saga", so both spellings land on one key.
        val comma = s.lastIndexOf(", ")
        if (comma > 0) {
            val tail = s.substring(comma + 2).trim()
            if (tail in TRAILING_ARTICLES) s = "$tail ${s.substring(0, comma)}"
        }

        val words = s.split(' ').filter(String::isNotEmpty).map { w ->
            val clean = w.filter { it.isLetterOrDigit() }
            ROMAN[clean] ?: clean
        }
        return words.filter { it.isNotEmpty() && it != "and" }
    }

    /** Region tags found in a No-Intro style name, normalised to the catalog's vocabulary. */
    fun regionsOf(rawName: String): Set<String> {
        val out = linkedSetOf<String>()
        BRACKET_GROUP.findAll(rawName).forEach { m ->
            m.groupValues[1].split(',').forEach { piece ->
                when (SystemDef.key(piece)) {
                    "usa", "us", "na" -> out += "usa"
                    "europe", "eu", "pal" -> out += "europe"
                    "japan", "jp" -> out += "japan"
                    "world", "wor" -> out += "world"
                }
            }
        }
        return out
    }

    /**
     * True for names that are variants nobody wants as the default cover: prototypes,
     * betas, demos and sample discs. They match the title just as well as the real
     * release, so without this they win by being alphabetically earlier.
     */
    fun isSideRelease(rawName: String): Boolean {
        val lower = rawName.lowercase()
        return SIDE_TAGS.any { it in lower }
    }
}

/** One thumbnail available on the remote side. */
data class ThumbEntry(val fileName: String) {
    val key: String = MatchKey.of(MatchKey.stem(fileName))
    val regions: Set<String> = MatchKey.regionsOf(fileName)
    val sideRelease: Boolean = MatchKey.isSideRelease(fileName)
    /** Sus palabras, para [ThumbIndex.near]. Se sacan solo si alguien las pide. */
    val words: Set<String> by lazy { MatchKey.words(MatchKey.stem(fileName)).toSet() }
}

/**
 * The thumbnails available for one system, indexed for lookup.
 */
class ThumbIndex(private val entries: List<ThumbEntry>) {
    val byKey: Map<String, List<ThumbEntry>> = entries.groupBy { it.key }
    val size: Int = entries.size

    /**
     * Picks the best thumbnail for a game.
     *
     * Ranking, in order: the game's own region wins, then USA, World and Europe as the
     * usual English releases, then anything else; and a main release always beats a beta
     * or prototype of the same title.
     *
     * `title` es un titulo: un nombre de fichero se pasa sin su extension, ver [MatchKey.stem].
     */
    fun best(title: String, preferRegion: String? = null): ThumbEntry? =
        rank(byKey[MatchKey.of(title)] ?: return null, preferRegion)

    /**
     * Para un nombre escrito A MANO —el que el usuario le puso al juego—: el titulo con las
     * mismas palabras aunque le sobren o le falten un par.
     *
     * [best] pide el nombre exacto, que es lo justo con el nombre de un fichero: es un nombre de
     * conjunto de volcado, o esta bien o no. Uno escrito a mano casi nunca coincide al pie de la
     * letra —«Pro Evolution Soccer 2007» es, en la coleccion, «Winning Eleven - Pro Evolution
     * Soccer 2007 (USA)»— y exigirlo es no encontrar nada. Pero tolerar sin freno es colocar
     * caratulas equivocadas, que es peor que un hueco. Asi que:
     *
     * - los NUMEROS, iguales: «Silent Hill» no es «Silent Hill 2», ni «PES 2013» el «PES 6»;
     * - uno contiene al otro entero, con dos palabras de diferencia como mucho;
     * - al menos tres palabras en comun: con menos, «Doom» seria «Final Doom»;
     * - y si dos juegos distintos quedan igual de cerca, ninguno: no se elige a ciegas.
     */
    fun near(title: String, preferRegion: String? = null): ThumbEntry? {
        val mine = MatchKey.words(title).toSet()
        if (mine.size < NEAR_MIN_WORDS) return null
        val myNumbers = mine.filterTo(HashSet()) { w -> w.all(Char::isDigit) }
        var closest = Int.MAX_VALUE
        val hits = mutableListOf<ThumbEntry>()
        for (e in entries) {
            val theirs = e.words
            if (theirs.filterTo(HashSet()) { w -> w.all(Char::isDigit) } != myNumbers) continue
            val common = mine.count { it in theirs }
            if (common < NEAR_MIN_WORDS || (common != mine.size && common != theirs.size)) continue
            val apart = (mine.size - common) + (theirs.size - common)
            if (apart > NEAR_MAX_APART) continue
            if (apart < closest) { closest = apart; hits.clear() }
            if (apart == closest) hits += e
        }
        if (hits.isEmpty() || hits.map { it.key }.distinct().size > 1) return null
        return rank(hits, preferRegion)
    }

    /**
     * Para un juego que alguien busca A MANO: lo que podría ser, de más a menos seguro, para
     * que elija él. El bool dice si es el mismo nombre.
     *
     * Aquí no se decide nada, así que las reglas de [near] sobran: una opción de más no pone
     * ninguna carátula equivocada, solo se salta. Primero el mismo nombre, una por región —la
     * japonesa y la americana suelen ser dibujos distintos, y cuál es la buena lo sabe quien
     * tiene el juego—. Detrás, un nombre por título que comparta al menos la mitad de las
     * palabras que importan, los más parecidos primero: así «Pro Evolution Soccer 2013» trae
     * los otros años de la serie, que es lo que alguien querría ver si el suyo no está.
     *
     * La mitad se cuenta sin los números. Con ellos, «Pro Yakyuu Spirits 2013» —béisbol—
     * pasaba por pariente de «Pro Evolution Soccer 2013» con solo «pro» y el año. Los números
     * sí cuentan para el orden: el mismo año va delante.
     */
    fun around(titles: List<String>, limit: Int, preferRegion: String? = null): List<Pair<ThumbEntry, Boolean>> {
        val out = mutableListOf<Pair<ThumbEntry, Boolean>>()
        val taken = HashSet<String>()
        for (t in titles) {
            val key = MatchKey.of(t)
            if (key.isEmpty() || !taken.add(key)) continue
            (byKey[key] ?: continue)
                .groupBy { it.regions.sorted() }.values
                .mapNotNull { rank(it, preferRegion) }
                .sortedWith(order(preferRegion))
                .forEach { out += it to true }
        }
        val asked = titles.map { significant(MatchKey.words(it)) }.filter { it.isNotEmpty() }
        class Hit(val entry: ThumbEntry, val score: Float, val numbers: Boolean)
        val hits = mutableListOf<Hit>()
        for ((key, group) in byKey) {
            if (key in taken) continue
            val theirs = significant(group[0].words)
            var best: Hit? = null
            for (mine in asked) {
                val wanted = mine.count { !isNumber(it) }
                val named = mine.count { !isNumber(it) && it in theirs }
                if (named == 0 || named * 2 < wanted) continue
                val common = mine.count { it in theirs }
                val score = common.toFloat() / (mine.size + theirs.size - common)
                if (best == null || score > best.score) {
                    best = Hit(rank(group, preferRegion) ?: continue, score, numbers(mine) == numbers(theirs))
                }
            }
            best?.let { hits += it }
        }
        hits.sortWith(
            compareByDescending<Hit> { it.score }
                .thenByDescending { it.numbers }
                .thenBy(order(preferRegion)) { it.entry }
        )
        hits.forEach { out += it.entry to false }
        return out.take(limit)
    }

    /** Sin las palabras que casi todos los títulos tienen: que dos compartan «the» no es parecerse. */
    private fun significant(words: Collection<String>): Set<String> = words.filterTo(HashSet()) { it !in FILLER }

    private fun numbers(words: Set<String>): Set<String> = words.filterTo(HashSet(), ::isNumber)

    private fun isNumber(w: String) = w.all(Char::isDigit)

    private fun rank(hits: List<ThumbEntry>, preferRegion: String?): ThumbEntry? =
        if (hits.size == 1) hits[0] else hits.minWithOrNull(order(preferRegion))

    private fun order(preferRegion: String?): Comparator<ThumbEntry> {
        val order = listOfNotNull(preferRegion, "usa", "world", "europe", "japan")
        return compareBy<ThumbEntry> { if (it.sideRelease) 1 else 0 }
            .thenBy { e ->
                val i = order.indexOfFirst { it in e.regions }
                if (i < 0) order.size else i
            }
            .thenBy { it.fileName.length }   // shorter names are the plainer release
    }

    private companion object {
        const val NEAR_MIN_WORDS = 3
        const val NEAR_MAX_APART = 2
        val FILLER = setOf(
            "the", "a", "an", "of", "and", "in", "on", "to", "for", "vs", "no",
            "de", "del", "la", "le", "el", "los", "las", "les", "du", "des", "der", "die", "das", "il",
        )
    }
}
