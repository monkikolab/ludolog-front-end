package com.felp.frontcomp

import java.io.File

/**
 * La ficha de un juego: que es, sacado de su contenido (GameId), y lo que el catalogo sabe de
 * el (GameDb). Reemplaza a las listas de ES-DE, que eran lo unico que decia genero, año y
 * estudio, y es lo que leera el metagame.
 *
 * Todo en texto, clave=valor, para poder leerla y corregirla a mano:
 *
 *     how      como se identifico: crc, sha1, serial, gameid, romset, name, title o none
 *     exact    1 si es seguro (por el contenido o por el nombre exacto de la base)
 *     keys     las claves del fichero, para no volver a leerlo
 *     name     su nombre canonico (No-Intro, Redump, FBNeo...)
 *     sys      la consola de verdad, si no es la de la carpeta
 *     g.wd     generos de Wikidata, tal cual, separados por «|»
 *     g.lr     genero de libretro, tal cual
 *     g.gt     generos de GameTDB (Nintendo y PS3), tal cual
 *     date     «1994», «1994-03» o «1994-03-19»: la primera salida
 *     dev pub series fame qid src
 *     syn      la sinopsis; syn.from, el articulo de Wikipedia o la ficha de GameTDB de la que sale,
 *              y syn.src cual de las dos (wp o gt)
 *     gt       su ID en GameTDB, el de su caratula alli
 *     alt      como se llama en otras regiones, separados por «|», y label su titulo en Wikidata
 *              si es otro: los nombres con que buscar su arte si el suyo no lo tiene
 *     cat      la version del catalogo con que se lleno
 *     my.*     lo que se corrija a mano: no lo pisa nada
 */
internal data class Dossier(
    val path: String,
    val size: Long,
    val mtime: Long,
    val fields: Map<String, String>,
) {
    operator fun get(key: String): String? = fields[key]?.takeIf(String::isNotEmpty)

    val keys: List<String> get() = get("keys")?.split(' ')?.filter(String::isNotEmpty).orEmpty()
    val exact: Boolean get() = get("exact") == "1"
    val name: String? get() = get("name")
    val realSystem: String? get() = get("sys")

    /** Los generos de todas las fuentes, sin repetir: Wikidata primero, que es el mas fino; luego GameTDB y libretro. */
    val genres: List<String>
        get() = (get("my.genre") ?: listOfNotNull(get("g.wd"), get("g.gt"), get("g.lr")).joinToString("|"))
            .split('|').map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

    /** El genero puesto a mano, si lo hay: uno o varios, separados por «|». */
    val ownGenres: List<String> get() = get("my.genre")?.split('|')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    /** Los del catalogo, sin lo puesto a mano: lo que dice «Automatic» al elegir. */
    val catalogGenres: List<String>
        get() = listOfNotNull(get("g.wd"), get("g.gt"), get("g.lr")).joinToString("|")
            .split('|').map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

    val year: String? get() = (get("my.date") ?: get("date"))?.take(4)
    val developer: String? get() = get("my.dev") ?: get("dev")
    val synopsis: String? get() = get("syn")

    /** Su ID en GameTDB. */
    val gametdb: String? get() = get("gt")

    /** Los otros nombres con que buscar su arte: los de otras regiones y, al final, el de Wikidata. */
    val otherNames: List<String>
        get() = (get("alt")?.split('|').orEmpty() + listOfNotNull(get("label"))).filter(String::isNotEmpty)
}

internal object Dossiers {

    private const val HEADER = "ludolog-dossiers 1"

    /** Una por consola, en la carpeta de datos: es de quien juega y viaja con ella. */
    val dir: File get() = DataHome.file("dossiers")

    private fun fileOf(systemId: String) = File(dir, "$systemId.tsv")

    /** Lo leido, por consola y por ruta. Se guarda mientras viva el proceso. */
    private val memo = HashMap<String, MutableMap<String, Dossier>>()

    /** Las de una consola. */
    fun of(systemId: String): Map<String, Dossier> = synchronized(memo) {
        memo.getOrPut(systemId) {
            runCatching { fileOf(systemId).bufferedReader().useLines { parse(it) } }
                .getOrDefault(emptyMap()).toMutableMap()
        }
    }

    fun get(game: Game): Dossier? = of(game.systemId)[game.path]

    /**
     * Olvida lo leido. Ludolog no lo usa: su carpeta de datos es una. Ludolog Link en el PC lee
     * las de varias consolas en el mismo proceso, y al pasar de una a otra no debe quedarse con
     * las fichas de la anterior. Ver docs/ludolog-link.md.
     */
    fun forget() = synchronized(memo) { memo.clear() }

    /**
     * Lo corregido a mano de un juego («my.genre»). Se queda en su ficha y no lo pisa nada: ni
     * volver a leer el fichero ni un catalogo nuevo (ver refresh). Vacio lo quita.
     *
     * Si el juego aun no tiene ficha, se crea solo con eso, sin tamaño ni fecha: la siguiente
     * pasada la da por cambiada, lee el fichero y la llena conservando lo puesto a mano.
     */
    fun setOwn(game: Game, key: String, value: String?) = setOwn(game.systemId, game.path, key, value)

    /** Lo mismo por consola y ruta: lo que llega de otra consola por Ludolog Link (ver LinkEdits). */
    fun setOwn(systemId: String, path: String, key: String, value: String?) {
        require(key.startsWith("my.")) { "solo lo de a mano: $key" }
        val all = of(systemId).toMutableMap()
        val d = all[path] ?: Dossier(path, 0L, 0L, emptyMap())
        val f = LinkedHashMap(d.fields)
        if (value.isNullOrBlank()) f.remove(key) else f[key] = value.trim()
        all[path] = d.copy(fields = f)
        save(systemId, all)
    }

    /**
     * Un ROM renombrado desde Ludolog Link: su ficha pasa a la ruta nueva, con lo puesto a mano.
     * Devuelve la consola en la que estaba, o nulo si no tenia ficha. Tamaño y fecha no cambian
     * al renombrar, asi que la siguiente pasada no la da por cambiada. Ver LinkBridge.
     */
    fun moved(from: String, to: String): String? {
        val systems = dir.listFiles { f -> f.name.endsWith(".tsv") }?.map { it.name.removeSuffix(".tsv") }.orEmpty()
        for (systemId in systems) {
            val all = of(systemId)
            val d = all[from] ?: continue
            val next = all.toMutableMap()
            next.remove(from)
            next[to] = d.copy(path = to)
            save(systemId, next)
            return systemId
        }
        return null
    }

    /** Guarda las de una consola: primero a un `.part`, y luego se renombra. */
    fun save(systemId: String, all: Map<String, Dossier>) {
        synchronized(memo) { memo[systemId] = all.toMutableMap() }
        runCatching {
            dir.mkdirs()
            val to = fileOf(systemId)
            val tmp = File(dir, "$systemId.tsv.part")
            tmp.writeText(format(all.values))
            if (!tmp.renameTo(to)) { tmp.copyTo(to, overwrite = true); tmp.delete() }
        }
    }

    fun parse(lines: Sequence<String>): Map<String, Dossier> {
        val out = HashMap<String, Dossier>()
        val it = lines.iterator()
        if (!it.hasNext() || it.next() != HEADER) return out
        while (it.hasNext()) {
            val f = it.next().split('\t')
            if (f.size < 3) continue
            val fields = LinkedHashMap<String, String>()
            for (kv in f.drop(3)) {
                val eq = kv.indexOf('=')
                if (eq > 0) fields[kv.substring(0, eq)] = kv.substring(eq + 1)
            }
            out[f[0]] = Dossier(f[0], f[1].toLongOrNull() ?: -1, f[2].toLongOrNull() ?: -1, fields)
        }
        return out
    }

    fun format(all: Collection<Dossier>): String = buildString {
        append(HEADER).append('\n')
        for (d in all.sortedBy { it.path }) {
            append(clean(d.path)).append('\t').append(d.size).append('\t').append(d.mtime)
            // Las claves, aunque esten vacias: «keys=» dice que el fichero ya se leyo y no dio
            // nada (un perfil de DoomForge). Sin ella, esos se volvian a leer en cada arranque.
            for ((k, v) in d.fields) if (v.isNotEmpty() || k == "keys") append('\t').append(k).append('=').append(clean(v))
            append('\n')
        }
    }

    /** Un valor en una linea: ni tabuladores ni saltos. */
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /**
     * Como se leen los paquetes. Se sube cada vez que la app empieza a usar algo nuevo de ellos,
     * para que las fichas ya hechas se vuelvan a llenar. 2: generos y sinopsis de GameTDB. 3: los
     * nombres y el ID de GameTDB para el arte.
     */
    private const val READS = 3

    /**
     * Donde mas buscar, por titulo, lo que el paquete de una consola no tiene. Un juego de Android
     * que en Google Play no lleva el paquete apuntado suele estar tambien en Steam: Warframe. El
     * genero casi nunca cambia de una plataforma a otra, y por titulo no cuenta como seguro.
     */
    internal val ALSO_LOOK = mapOf("android" to listOf("pc"))

    /**
     * Como se leen las claves de un fichero. Se sube cuando GameId aprende a leer algo que antes
     * no daba nada: 2, los accesos `.steam`. Solo se releen los que se quedaron sin clave; los que
     * ya la tienen no cambian, y releer una biblioteca entera son minutos.
     */
    private const val KEYS = 2

    private fun stale(d: Dossier): Boolean = d["keys"] == null && d.fields["kr"] != KEYS.toString()

    /** Lo que se rellena del catalogo: al volver a buscar se quita y se pone de nuevo. */
    private val FROM_CATALOG = listOf(
        "how", "exact", "name", "sys", "g.wd", "g.lr", "g.gt", "date", "dev", "pub", "series", "fame", "qid", "src",
        "syn", "syn.from", "syn.src", "gt", "alt", "label", "cat",
    )

    // ------------------------------------------------------------------ la pasada

    /** Lo que hizo una pasada: juegos leidos por dentro, identificados y encontrados. */
    data class Report(val read: Int, val found: Int, val exact: Int, val total: Int)

    /**
     * Pone al dia las fichas de estos juegos.
     *
     * Primero lo que cuesta: leer por dentro cada fichero nuevo o cambiado (el CRC de un
     * cartucho, la cabecera de un disco). Lo que ya tenia sus claves con el mismo tamaño y fecha
     * no se vuelve a leer. Despues, si hay catalogo, se busca cada uno que no se haya buscado con
     * esta version del catalogo; eso es solo buscar en memoria.
     *
     * Corre fuera del hilo de la pantalla y puede tardar la primera vez: una biblioteca de
     * cartuchos se lee entera. Las siguientes solo miran lo nuevo.
     */
    fun refresh(
        games: List<Game>,
        useCatalog: Boolean,
        /**
         * Si se mira en disco cada fichero que ya tiene ficha, por si cambio. Al relanzar no:
         * son tres consultas por juego a la tarjeta, y con miles de juegos se van segundos en
         * cada arranque para no encontrar nada. Lo hace el repaso pedido a mano.
         */
        thorough: Boolean = true,
        progress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): Report {
        // La version del catalogo y la de como se lee: una app que aprende a usar una columna
        // nueva (los generos de GameTDB, en la 2) vuelve a buscar las fichas aunque el catalogo
        // sea el mismo. Sin la segunda parte, lo nuevo no se veia hasta el catalogo siguiente.
        val version = if (useCatalog) GameDb.version()?.let { "$it+$READS" } else null
        var read = 0
        var found = 0
        var exact = 0
        var done = 0
        for ((systemId, list) in games.groupBy { it.systemId }) {
            val all = of(systemId).toMutableMap()
            var changed = false
            // Solo si alguna ficha hay que buscarla: abrir un paquete es descomprimirlo y leer
            // miles de filas, y en un arranque normal ninguna ficha ha cambiado. Abriendolos
            // siempre, el contador de arriba tardaba segundos en cada arranque sin hacer nada.
            // Y los de las consolas donde buscar por titulo lo que la suya no tenga: ver ALSO_LOOK.
            val packs by lazy {
                if (version == null) emptyList()
                else (listOf(systemId) + ALSO_LOOK[systemId].orEmpty()).flatMap { GameDb.packsFor(it) }
            }
            val texts = HashMap<String, Map<String, GameDb.Text>>()
            for (game in list) {
                val at = done++
                var d = all[game.path]
                // Un juego de Android no tiene fichero que leer: su clave es el paquete.
                val pkg = game.appPackage
                if (pkg != null) {
                    if (d == null || "keys" !in d.fields) {
                        val kept = d?.fields?.filterKeys { it.startsWith("my.") }.orEmpty()
                        d = Dossier(game.path, 0L, 0L, LinkedHashMap(kept).apply { put("keys", "a:${pkg.lowercase()}") })
                        changed = true
                    }
                } else if (thorough || d == null || "keys" !in d.fields || stale(d)) {
                    val file = dataFile(game) ?: continue
                    val size = file.length()
                    val mtime = file.lastModified()
                    if (d == null || d.size != size || d.mtime != mtime || "keys" !in d.fields || stale(d)) {
                        // El contador solo cuando hay trabajo: en un arranque normal no lo hay, y
                        // ensenarlo cada vez era ruido en la barra de arriba.
                        progress(at, games.size, game.title)
                        val keys = GameId.of(file)
                        // Lo escrito a mano se queda; lo demas se rehace con el fichero nuevo.
                        val kept = d?.fields?.filterKeys { it.startsWith("my.") }.orEmpty()
                        d = Dossier(game.path, size, mtime, LinkedHashMap(kept).apply { put("keys", keys.joinToString(" ")); put("kr", KEYS.toString()) })
                        read++
                        changed = true
                    }
                }
                if (version != null && d["cat"] != version) {
                    // Sin paquete para su consola (PICO-8, Steam) se apunta igual que ya se miro:
                    // si no, se volvia a mirar en cada arranque. Si un catalogo nuevo la trae, la
                    // version cambia y se mira otra vez.
                    d = if (packs.isNotEmpty()) {
                        progress(at, games.size, game.title)
                        resolve(d, game, packs, version, texts)
                    } else d.copy(fields = d.fields + ("cat" to version))
                    changed = true
                }
                if (d["name"] != null) found++
                if (d.exact) exact++
                all[game.path] = d
            }
            if (changed) save(systemId, all)
        }
        GameDb.forget()
        return Report(read, found, exact, games.size)
    }

    /** Lo que dice el catalogo de un juego, puesto en su ficha. */
    private fun resolve(
        d: Dossier,
        game: Game,
        packs: List<GameDb.Pack>,
        version: String,
        texts: MutableMap<String, Map<String, GameDb.Text>>,
    ): Dossier {
        val f = LinkedHashMap(d.fields)
        FROM_CATALOG.forEach(f::remove)
        f["cat"] = version
        val hit = GameDb.find(packs, d.keys, File(game.path).name, game.title)
        if (hit == null) {
            f["how"] = "none"
            return d.copy(fields = f)
        }
        val r = hit.row
        f["how"] = hit.how
        f["exact"] = if (hit.exact) "1" else "0"
        f["name"] = r.name
        // Que es de otra consola solo se cree si es seguro: por el titulo, un juego de Game Boy
        // Color se parece a uno de Game Boy con el mismo nombre, y no por eso lo es.
        if (hit.pack != game.systemId && hit.exact) f["sys"] = hit.pack
        f["g.wd"] = r.genreWd
        f["g.lr"] = r.genreLr
        f["g.gt"] = r.genreGt
        f["date"] = r.date
        f["dev"] = r.dev
        f["pub"] = r.pub
        f["series"] = r.series
        if (r.fame > 0) f["fame"] = r.fame.toString()
        f["qid"] = r.qid
        f["src"] = r.src
        f["gt"] = r.gt
        f["alt"] = r.alt.joinToString("|")
        f["label"] = r.label
        // La sinopsis por su clave: la de Wikipedia va por el QID; la de GameTDB, por «gt:<id>».
        val key = r.tx.ifEmpty { r.qid }
        if (key.isNotEmpty()) {
            val t = texts.getOrPut(hit.pack) { GameDb.textsFor(hit.pack) }[key]
            if (t != null) { f["syn"] = t.text; f["syn.from"] = t.title; f["syn.src"] = t.source }
        }
        // Los vacios fuera, salvo «keys»: vacia dice que el fichero ya se leyo (ver format).
        return d.copy(fields = f.filter { (k, v) -> v.isNotEmpty() || k == "keys" })
    }

    /**
     * El fichero que se lee por dentro: el propio juego, o el primer disco de una lista .m3u.
     */
    private fun dataFile(game: Game): File? {
        val f = File(game.path)
        if (!f.isFile) return null
        if (Catalog.extensionOf(f.name) != "m3u") return f
        return runCatching {
            f.readLines().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?.let { File(f.parentFile, it) }?.takeIf { it.isFile }
        }.getOrNull() ?: f
    }
}
