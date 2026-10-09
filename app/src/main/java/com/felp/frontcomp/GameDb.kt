package com.felp.frontcomp

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * El catalogo propio de juegos: lo que se sabe de cada juego sin escrapear nada.
 *
 * Se prepara en el PC (tools/catalog) con libretro-database, Wikidata y Wikipedia, ya cruzado,
 * y se publica como paquetes: uno por consola con los datos y otro con las sinopsis. La app baja
 * los de sus consolas a `<datos>/catalog/` y aqui solo busca. Guarda lo que usa el metagame
 * —generos de cada fuente, fecha, desarrollador, editor, serie, fama— y la sinopsis que enseña la
 * interfaz; nada mas. Ver docs/scraper.md.
 *
 * La busqueda va de lo seguro a lo aproximado:
 *  1. Por las claves que da el propio fichero (GameId): exacta.
 *  2. Por el nombre del fichero, cuando es tal cual el de la base: casi igual de buena.
 *  3. Por el titulo, prefiriendo la region que diga el nombre. Esta es la que puede fallar.
 *
 * Un paquete puede servir a varias consolas: el de Mega Drive lo miran tambien los juegos de la
 * carpeta de 32X, y si casa alli, el juego es de Mega Drive aunque este en otra carpeta.
 */
internal object GameDb {

    /**
     * De donde se baja por defecto: el ultimo lanzamiento del repositorio de Ludolog, donde van el
     * catalogo y los temas. Vacio mientras no este publicado; se puede poner otro en los ajustes
     * (Prefs.catalogSource). La copia de prueba (`fresh`) lo baja de una carpeta del aparato.
     */
    val SOURCE: String = BuildConfig.RELEASES

    /** Una fila del catalogo: un juego, o una version de un juego. */
    data class Row(
        val keys: List<String>,
        val name: String,
        /** Los generos tal como los da cada fuente, separados por «|». */
        val genreWd: String,
        val genreLr: String,
        val genreGt: String,
        /** «1994», «1994-03» o «1994-03-19». */
        val date: String,
        val dev: String,
        val pub: String,
        val series: String,
        /** En cuantas Wikipedias tiene articulo: la fama del juego. */
        val fame: Int,
        val qid: String,
        /** La clave de su sinopsis en el paquete de textos: el QID, o «gt:<id>» si es de GameTDB. */
        val tx: String,
        /** De donde sale cada dato de [date], [dev], [pub] y [series]: w (Wikidata), l (libretro), g (GameTDB) o -. */
        val src: String,
        /** Su ID en GameTDB («R3ME01», «BCES00510», «ANKSA»): el de su caratula alli. */
        val gt: String = "",
        /**
         * Como se llama en otras regiones, si es otro nombre: la española de Pokémon Esmeralda
         * tiene su caratula como «Pokemon - Emerald Version (USA, Europe)».
         */
        val alt: List<String> = emptyList(),
        /** Su titulo en Wikidata, si no es el del volcado: «Resident Evil 2» para la Dual Shock Ver. */
        val label: String = "",
    )

    /**
     * Un paquete leido, con lo necesario para buscar en el.
     *
     * Perezoso a proposito. Se guardan las lineas tal cual y cada fila se convierte solo cuando
     * una busqueda la encuentra; y cada indice se hace la primera vez que se usa, leyendo solo
     * su columna. El de PC tiene 124.570 filas: convertirlas todas y hacer los tres indices, con
     * el titulo reducido de cada una, se llevaba unos diez segundos en la consola cada vez que se
     * abria, y casi siempre para buscar un puñado de juegos por su numero de Steam.
     */
    class Pack(val id: String, val version: String, private val lines: List<String>, private val cols: Map<String, Int>) {
        private val cache = arrayOfNulls<Row>(lines.size)
        private val keysAt = cols["keys"] ?: -1
        private val nameAt = cols["name"] ?: -1
        /** El titulo ya reducido, si el paquete lo trae (desde el catalogo del 26-09-2026). */
        private val ntAt = cols["nt"] ?: -1

        val size: Int get() = lines.size

        fun row(i: Int): Row = cache[i] ?: rowOf(lines[i].split('\t'), cols).also { cache[i] = it }

        /** Todas, convertidas. Solo para las pruebas: es justo lo que la pereza evita. */
        val rows: List<Row> get() = lines.indices.map(::row)

        private val byKey: Map<String, Int> by lazy {
            HashMap<String, Int>().also { m ->
                lines.forEachIndexed { i, l -> field(l, keysAt).split(' ').forEach { k -> if (k.isNotEmpty()) m.putIfAbsent(k, i) } }
            }
        }
        private val byName: Map<String, Int> by lazy {
            HashMap<String, Int>().also { m -> lines.forEachIndexed { i, l -> m.putIfAbsent(field(l, nameAt).lowercase(), i) } }
        }
        private val byTitle: Map<String, List<Int>> by lazy {
            HashMap<String, MutableList<Int>>().also { m ->
                lines.forEachIndexed { i, l ->
                    val t = field(l, ntAt).ifEmpty { norm(base(field(l, nameAt))) }
                    m.getOrPut(t) { ArrayList(1) } += i
                }
            }
        }

        fun key(k: String): Row? = byKey[k]?.let(::row)
        fun named(name: String): Row? = byName[name.lowercase()]?.let(::row)
        fun titled(t: String): List<Row>? = byTitle[t]?.map(::row)

        /**
         * Los nombres que casan con lo que se esta escribiendo: todas sus palabras dentro del titulo
         * reducido. Primero los que empiezan asi, y dentro, los mas famosos. Sin etiquetas de region
         * y sin repetir: siete volcados de un juego son un solo nombre para quien lo renombra.
         */
        fun search(query: String, limit: Int): List<String> {
            val words = norm(query).split(' ').filter(String::isNotEmpty)
            if (words.isEmpty()) return emptyList()
            val head = words.joinToString(" ")
            val fameAt = cols["fame"] ?: -1
            class Hit(val name: String, val starts: Boolean, val fame: Int)
            val hits = ArrayList<Hit>()
            for (l in lines) {
                val t = field(l, ntAt).ifEmpty { norm(base(field(l, nameAt))) }
                if (words.all { it in t }) {
                    hits += Hit(base(field(l, nameAt)), t.startsWith(head), field(l, fameAt).toIntOrNull() ?: 0)
                }
            }
            return hits.sortedWith(compareByDescending<Hit> { it.starts }.thenByDescending { it.fame }.thenBy { it.name.length })
                .map { it.name }.distinctBy(String::lowercase).take(limit)
        }
    }

    /** La columna [at] de una linea, sin partirla entera. Vacia si no la tiene. */
    private fun field(line: String, at: Int): String {
        if (at < 0) return ""
        var start = 0
        repeat(at) {
            val tab = line.indexOf('\t', start)
            if (tab < 0) return ""
            start = tab + 1
        }
        val end = line.indexOf('\t', start).let { if (it < 0) line.length else it }
        return line.substring(start, end)
    }

    /** Lo encontrado: la fila, de que paquete, como y si es seguro. */
    data class Hit(val row: Row, val pack: String, val how: String, val exact: Boolean)

    // ------------------------------------------------------------------ leer

    /**
     * Un paquete de juegos: una cabecera «#ludolog-games», los nombres de las columnas y una fila
     * por juego. Por el NOMBRE de la columna y no por su posicion: asi el catalogo puede ganar
     * columnas sin que una app anterior lea lo que no es.
     */
    fun parsePack(lines: Sequence<String>): Pack? {
        val it = lines.iterator()
        if (!it.hasNext()) return null
        val head = it.next().split('\t')
        if (head.firstOrNull() != "#ludolog-games" || !it.hasNext()) return null
        val cols = it.next().split('\t').withIndex().associate { (i, c) -> c to i }
        // Las lineas tal cual: se convierten al encontrarse (ver Pack).
        val lines = ArrayList<String>()
        while (it.hasNext()) {
            val l = it.next()
            if ('\t' in l) lines += l
        }
        return Pack(head.getOrNull(2).orEmpty(), head.getOrNull(3).orEmpty(), lines, cols)
    }

    private fun rowOf(f: List<String>, cols: Map<String, Int>): Row {
        fun col(name: String) = cols[name]?.let { i -> f.getOrNull(i) }.orEmpty()
        return Row(
            keys = col("keys").split(' ').filter(String::isNotEmpty),
            name = col("name"),
            genreWd = col("genre.wd"),
            genreLr = col("genre.lr"),
            genreGt = col("genre.gt"),
            date = col("date"),
            dev = col("dev"),
            pub = col("pub"),
            series = col("series"),
            fame = col("fame").toIntOrNull() ?: 0,
            qid = col("qid"),
            tx = col("tx"),
            src = col("src"),
            gt = col("gt"),
            alt = col("alt").split('|').filter(String::isNotEmpty),
            label = col("label"),
        )
    }

    /** Una sinopsis: de que articulo o ficha sale, el texto, y la fuente (wp Wikipedia, gt GameTDB). */
    data class Text(val title: String, val text: String, val source: String)

    /** Las sinopsis de un paquete, por su clave: el QID, o «gt:<id>» si es de GameTDB. */
    fun parseTexts(lines: Sequence<String>): Map<String, Text> {
        val it = lines.iterator()
        if (!it.hasNext() || !it.next().startsWith("#ludolog-text") || !it.hasNext()) return emptyMap()
        it.next()
        val out = HashMap<String, Text>()
        while (it.hasNext()) {
            val f = it.next().split('\t')
            if (f.size >= 3) out[f[0]] = Text(f[1], f[2], f.getOrNull(3)?.ifEmpty { null } ?: "wp")
        }
        return out
    }

    /** Una linea del indice: un fichero del catalogo y a que consolas sirve. */
    data class Entry(
        val file: String,
        val pack: String,
        val systems: List<String>,
        val kind: String,
        val bytes: Long,
        val sha1: String,
    )

    fun parseIndex(text: String): List<Entry> {
        val lines = text.lines()
        if (lines.firstOrNull()?.startsWith("#ludolog-catalog") != true) return emptyList()
        val cols = lines.getOrNull(1)?.split('\t')?.withIndex()?.associate { (i, c) -> c to i } ?: return emptyList()
        fun List<String>.col(name: String) = cols[name]?.let { i -> getOrNull(i) }.orEmpty()
        return lines.drop(2).filter { it.isNotBlank() }.map { l ->
            val f = l.split('\t')
            Entry(
                file = f.col("file"),
                pack = f.col("pack"),
                systems = f.col("systems").split(',').filter(String::isNotEmpty),
                kind = f.col("kind"),
                bytes = f.col("bytes").toLongOrNull() ?: 0L,
                sha1 = f.col("sha1"),
            )
        }.filter { it.file.isNotEmpty() && '/' !in it.file && '\\' !in it.file }
    }

    // ------------------------------------------------------------------ buscar

    /**
     * El juego de un fichero, en los paquetes que miran su consola (el suyo primero).
     *
     * [keys] son las de GameId; [fileName] el nombre del fichero, que sirve para las otras dos
     * busquedas; [title] el titulo que se le saco al nombre (Detector.describe).
     */
    fun find(packs: List<Pack>, keys: List<String>, fileName: String, title: String): Hit? {
        for (p in packs) for (k in keys) p.key(k)?.let { return Hit(it, p.id, HOW[k.substringBefore(':')] ?: "key", true) }
        val stem = fileName.substringBeforeLast('.', fileName).lowercase()
        for (p in packs) p.named(stem)?.let { return Hit(it, p.id, "name", true) }
        val t = norm(title)
        if (t.isEmpty()) return null
        val want = regionOf(fileName)
        // El disco, si el nombre lo dice: el 2 de Final Fantasy VII no es el 1, aunque el titulo
        // sea el mismo.
        val disc = DISC.find(fileName)?.groupValues?.get(1)
        for (p in packs) {
            val all = p.titled(t) ?: continue
            val cands = if (disc == null) all else all.filter { DISC.find(it.name)?.groupValues?.get(1) == disc }.ifEmpty { all }
            val pick = cands.firstOrNull { want.isNotEmpty() && regionOf(it.name) == want }
                ?: cands.firstOrNull { regionOf(it.name) == "usa" } ?: cands.first()
            return Hit(pick, p.id, "title", false)
        }
        return null
    }

    private val DISC = Regex("""\(Disc (\d+)\)""", RegexOption.IGNORE_CASE)

    private val HOW = mapOf("c" to "crc", "h" to "sha1", "s" to "serial", "i" to "gameid", "r" to "romset", "t" to "titleid", "v" to "steam", "a" to "app")

    // ------------------------------------------------------------------ titulos

    // Sin la «x»: «Metal Slug X» y «Mega Man X» no son el 10.
    private val ROMAN = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")
    private val ARTICLES = setOf("the", "a", "an")
    private val COMBINING = Regex("[\\u0300-\\u036f]")
    private val TAGS = Regex("""\([^)]*\)|\[[^\]]*]""")
    private val APOSTROPHES = Regex("['’`™®©]")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val TAIL = Regex("""\s*[(\[].*$""")

    /**
     * Un titulo reducido a lo que se compara: sin etiquetas, acentos, articulos ni puntuacion,
     * con los romanos en cifras. Tiene que dar EXACTAMENTE lo mismo que `norm` de
     * tools/catalog/lib.mjs, que es con lo que se escribio el catalogo: la prueba GameDbTest lo
     * comprueba con los casos que genera aquel.
     */
    fun norm(s: String): String {
        val t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(COMBINING, "")
            .replace(TAGS, " ").replace("&", " and ").replace(APOSTROPHES, "").replace(NON_ALNUM, " ")
        return t.split(' ').filter(String::isNotEmpty).map { ROMAN[it] ?: it }.filter { it !in ARTICLES }.joinToString(" ")
    }

    /** El titulo sin las etiquetas del final: «Silent Hill 2 (Europe) (En,Fr)» -> «Silent Hill 2». */
    fun base(name: String): String = name.replace(TAIL, "").replace('_', ' ').trim()

    /** La region de un nombre No-Intro o Redump: usa, eur, jpn o nada. */
    fun regionOf(name: String): String {
        val tags = Regex("""\(([^)]*)\)""").findAll(name).joinToString(" ") { it.value }.lowercase()
        return when {
            Regex("usa|ntsc-u").containsMatchIn(tags) -> "usa"
            Regex("europe|spain|france|germany|italy|uk|pal").containsMatchIn(tags) -> "eur"
            Regex("japan|ntsc-j").containsMatchIn(tags) -> "jpn"
            else -> ""
        }
    }

    /**
     * Los idiomas que dice un nombre No-Intro: «(En,Fr,De,Es,It)». Solo los europeos suelen
     * decirlo; uno americano o japones no lo dice y sale vacio.
     */
    fun languagesOf(name: String): List<String> =
        Regex("""\(((?:[A-Z][a-z](?:-[A-Z][a-z])?,)*[A-Z][a-z](?:-[A-Z][a-z])?)\)""").findAll(name)
            .lastOrNull()?.groupValues?.get(1)?.split(',').orEmpty()


    // ------------------------------------------------------------------ en el aparato

    /** Donde viven los paquetes bajados. Se pueden borrar: se vuelven a bajar. */
    val dir: File get() = DataHome.file("catalog")

    /** Lo que hay bajado: el indice local, solo con lo que esta de verdad en la carpeta. */
    fun installed(root: File = dir): List<Entry> =
        runCatching { parseIndex(File(root, INDEX).readText()) }.getOrDefault(emptyList())
            .filter { File(root, it.file).isFile }

    /** Si hay algo bajado. */
    fun available(root: File = dir): Boolean = installed(root).any { it.kind == "games" }

    /** La version del catalogo bajado: la fecha en que se genero. */
    fun version(root: File = dir): String? =
        runCatching { File(root, INDEX).useLines { it.firstOrNull() } }.getOrNull()?.let(::versionOf)

    private fun versionOf(firstLine: String): String? = firstLine.split('\t').getOrNull(2)?.takeIf(String::isNotEmpty)

    private const val INDEX = "index.tsv"

    /** El ultimo indice publicado que se leyo: lo que hay para bajar, se tenga o no. */
    private const val PUBLISHED = "published.tsv"

    private val memo = HashMap<String, Pack?>()

    /** Los paquetes que miran una consola, el suyo primero. Se guardan mientras dure una pasada. */
    /** Nombres del catalogo para el renombrado de un juego de esta consola. Ver Pack.search. */
    fun suggest(systemId: String, query: String, limit: Int = 5): List<String> =
        packsFor(systemId).flatMap { it.search(query, limit) }.distinctBy(String::lowercase).take(limit)

    fun packsFor(systemId: String, root: File = dir): List<Pack> {
        val entries = installed(root).filter { it.kind == "games" && systemId in it.systems }
            .sortedBy { if (it.pack == systemId) 0 else 1 }
        return entries.mapNotNull { e ->
            synchronized(memo) {
                memo.getOrPut(File(root, e.file).path) {
                    runCatching {
                        GZIPInputStream(File(root, e.file).inputStream()).bufferedReader().useLines { parsePack(it) }
                    }.getOrNull()
                }
            }
        }
    }

    /** Las sinopsis de un paquete, o vacio si no las hay. */
    fun textsFor(pack: String, root: File = dir): Map<String, Text> {
        val e = installed(root).firstOrNull { it.kind == "text" && it.pack == pack } ?: return emptyMap()
        return runCatching {
            GZIPInputStream(File(root, e.file).inputStream()).bufferedReader().useLines { parseTexts(it) }
        }.getOrDefault(emptyMap())
    }

    /** Suelta lo leido: los paquetes grandes ocupan megas en memoria. */
    fun forget() = synchronized(memo) { memo.clear() }

    // ------------------------------------------------------------------ bajar

    /** El indice publicado, o el error. Se guarda, para saber sin red que falta. */
    fun remoteIndex(source: String, root: File = dir): Result<List<Entry>> = runCatching {
        require(source.isNotBlank()) { "no catalog source set" }
        val text = String(get(join(source, INDEX)), Charsets.UTF_8)
        val entries = parseIndex(text).ifEmpty { throw IOException("the catalog index is empty") }
        runCatching { root.mkdirs(); File(root, PUBLISHED).writeText(text) }
        entries
    }

    /**
     * Si a alguna de estas consolas le falta algo de lo publicado, o si nunca se miro. Las que no
     * tienen paquete (Android, Steam) no cuentan como que les falte: no lo hay.
     */
    fun missingFor(systems: Set<String>, root: File = dir): Boolean {
        val pub = runCatching { parseIndex(File(root, PUBLISHED).readText()) }.getOrNull() ?: return true
        val have = installed(root).associateBy { it.file }
        return pub.any { e -> e.systems.any { it in systems } && have[e.file]?.sha1 != e.sha1 }
    }

    /**
     * Si el ultimo indice publicado que se leyo trae un paquete de juegos para [systemId]. Nulo si
     * nunca se leyo ninguno: entonces no se sabe.
     */
    fun publishes(systemId: String, root: File = dir): Boolean? {
        val pub = runCatching { parseIndex(File(root, PUBLISHED).readText()) }.getOrNull() ?: return null
        return pub.any { it.kind == "games" && systemId in it.systems }
    }

    /** Lo que ocuparian los paquetes de estas consolas, en bytes. */
    fun sizeFor(entries: List<Entry>, systems: Set<String>): Long =
        entries.filter { e -> e.systems.any { it in systems } }.sumOf { it.bytes }

    /**
     * Baja lo que falte o haya cambiado para estas consolas y deja el indice al dia.
     *
     * Cada fichero va a un `.part`, se comprueba su huella y solo entonces se renombra: una
     * descarga cortada no deja un paquete a medias pasando por bueno. Lo que ya estaba con la
     * misma huella no se vuelve a pedir. El indice local se escribe al final, y lista lo que hay
     * de verdad: si algo falla a mitad, lo bajado hasta ahi sirve y lo demas se pide la
     * proxima vez. Devuelve cuantos ficheros bajo.
     */
    fun update(
        source: String,
        systems: Set<String>,
        root: File = dir,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> = runCatching {
        val indexText = String(get(join(source, INDEX)), Charsets.UTF_8)
        val remote = parseIndex(indexText).ifEmpty { throw IOException("the catalog index is empty") }
        root.mkdirs()
        File(root, PUBLISHED).writeText(indexText)
        val have = installed(root).associateBy { it.file }
        val wanted = remote.filter { e -> e.systems.any { it in systems } }
        val todo = wanted.filter { e -> have[e.file]?.sha1 != e.sha1 }
        val done = ArrayList<Entry>()
        try {
            todo.forEachIndexed { i, e ->
                progress(i, todo.size)
                val bytes = get(join(source, e.file))
                if (sha1(bytes) != e.sha1) throw IOException("${e.file} arrived damaged")
                val part = File(root, e.file + ".part")
                part.writeBytes(bytes)
                val to = File(root, e.file)
                if (!part.renameTo(to)) { part.copyTo(to, overwrite = true); part.delete() }
                done += e
            }
            progress(todo.size, todo.size)
        } finally {
            // Lo que ya estaba y sigue publicado igual, mas lo recien bajado.
            val kept = remote.filter { e -> e in done || have[e.file]?.sha1 == e.sha1 }
            val text = buildString {
                append("#ludolog-catalog\t1\t").append(versionOf(indexText.lineSequence().first()).orEmpty()).append('\n')
                append("file\tpack\tsystems\tkind\tbytes\tsha1\n")
                kept.forEach { append("${it.file}\t${it.pack}\t${it.systems.joinToString(",")}\t${it.kind}\t${it.bytes}\t${it.sha1}\n") }
            }
            val tmp = File(root, "$INDEX.part")
            tmp.writeText(text)
            val index = File(root, INDEX)
            if (!tmp.renameTo(index)) { tmp.copyTo(index, overwrite = true); tmp.delete() }
            forget()
        }
        todo.size
    }

    private fun join(source: String, file: String) = source.trimEnd('/') + "/" + file

    /**
     * Una direccion web, o una carpeta del aparato («file:///sdcard/Download/catalogo», o una
     * ruta a secas): asi se prueba un catalogo recien generado antes de publicarlo, sin servidor.
     */
    internal fun get(url: String): ByteArray {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            val f = File(url.removePrefix("file://").removePrefix("file:"))
            if (!f.isFile) throw IOException("${f.name} is not in ${f.parent}")
            return f.readBytes()
        }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Ludolog")
        }
        try {
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode} for ${url.substringAfterLast('/')}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    internal fun sha1(b: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
}
