package com.felp.frontcomp

import android.database.Cursor

/**
 * Lo que el cuaderno sabe decir, leido de una vez.
 *
 * El SQL es el de RetroCompanion palabra por palabra, no por copiar sino porque las cifras
 * tienen que ser LAS MISMAS: un cuaderno de alli traido aqui, o de aqui llevado alli, debe
 * dar el mismo total. Cada consulta lleva escrito al lado por que cuenta lo que cuenta.
 */
internal class LogStats(
    private val book: Logbook,
    /** Para leer cada partida con la consola por su id de aqui: ver [source]. Sin el, tal cual. */
    private val catalog: Catalog? = CatalogLoader.current,
    /** Para leer cada partida con el nombre de hoy de su juego: ver [source] y GameNames. */
    private val names: GameNames? = GameNames.current,
) {

    private companion object {
        /** Ventana de partidas recientes por juego. Ver rates(). */
        const val RECENT = 10

        /** Donde nombran las consultas la tabla de partidas o la de medidas. */
        val TABLE = Regex("""\b(FROM|JOIN)\s+(sessions|samples)\b""")

        /**
         * Un juego, para contarlos: su nombre Y su consola.
         *
         * Por el nombre solo, el Tetris de Game Boy y el de NES eran uno —las horas de los dos
         * juntas, las partidas mezcladas, la cabecera de uno de ellos—, y lo mismo cualquier
         * juego que salio en varias maquinas. Por eso tambien los rankings agrupan por las dos
         * cosas y un juego se abre con su consola: ver summary() y sessionsOf().
         */
        const val GAME_KEY = "(COALESCE(display_name, title, '?') || char(31) || COALESCE(system, '?'))"
    }

    /**
     * De donde leen las consultas: la tabla tal cual, o la misma con la consola traducida al
     * id del catalogo.
     *
     * Un cuaderno traido de RetroCompanion apunta la consola por el nombre de su carpeta de
     * ROMs, y no siempre es el id de aqui: los juegos de PC llegaban como «steam», que aqui es
     * «pc». Sin traducir, la tarjeta de Kingdom Hearts III decia «Not played yet» con media
     * hora apuntada, y el Companion enseñaba una consola STEAM que no esta en la lista —y las
     * partidas nuevas, apuntadas como «pc», habrian salido en otra fila—. Se traduce al leer y
     * el cuaderno no se toca: sigue diciendo lo que apunto quien lo escribio.
     *
     * Y con las partidas de las otras consolas, si el cuaderno las trae enganchadas (ver
     * Logbook.withOthers): cada una con su numero corrido (ver Logbook.OTHER_OFFSET), y sin las
     * que ya esten en otro cuaderno —la misma consola a la misma hora—, que se cuentan una vez.
     *
     * Y con el nombre de HOY de cada juego, no con el que tenia al jugarse: ver GameNames. Otra
     * vez al leer, y sin tocar lo apuntado.
     *
     * Una subconsulta y no una vista: una vista temporal vive en una conexion, y el cuaderno
     * del servicio que mide las partidas es el mismo que escribe.
     */
    private val source: String by lazy {
        val db = book.readableDatabase
        val others = book.others
        val schemas = listOf("main") + others.map { it.schema }
        val raw = if (catalog == null) emptyList() else schemas.flatMap { s ->
            db.rawQuery("SELECT DISTINCT system FROM $s.sessions WHERE system IS NOT NULL", null)
                .use { c -> c.rows { it.getString(0) } }
        }.distinct()
        val renames = raw.mapNotNull { r -> catalog?.canonicalId(r)?.takeIf { it != r }?.let { r to it } }
        val retitled = names?.let { retitled(schemas, it) }.orEmpty()
        if (retitled.isNotEmpty()) fillRetitle(retitled)
        if (renames.isEmpty() && others.isEmpty() && retitled.isEmpty()) return@lazy "sessions"
        val cols = columns("main", "sessions")
        val case = renames.joinToString(" ", "CASE system ", " ELSE system END AS system") { (from, to) ->
            "WHEN '${esc(from)}' THEN '${esc(to)}'"
        }
        fun pick(alias: String, have: Set<String>, offset: Long) = cols.joinToString(", ") { col ->
            when {
                col !in have -> "NULL AS $col"
                col == "id" && offset > 0 -> "id + $offset AS id"
                col == "system" && renames.isNotEmpty() -> case
                // Buscado por clave en la tabla temporal y no escrito en la consulta: con un CASE
                // de una linea por juego, cada fila las recorria todas. Medido con 50.000
                // partidas y 2.000 juegos renombrados: 2,6 s por consulta con el CASE, 0,13 asi.
                col == "display_name" && retitled.isNotEmpty() ->
                    "COALESCE((SELECT r.name FROM temp.retitle r WHERE r.system = $alias.system " +
                        "AND r.title = $alias.title), display_name) AS display_name"
                else -> col
            }
        }
        val parts = mutableListOf("SELECT ${pick("s0", cols.toSet(), 0)} FROM main.sessions s0")
        val before = mutableListOf("main")
        for (o in others) {
            val seen = before.joinToString(" OR ") { s ->
                "EXISTS (SELECT 1 FROM $s.sessions x WHERE x.device = o.device AND x.started_at = o.started_at)"
            }
            parts += "SELECT ${pick("o", columns(o.schema, "sessions").toSet(), o.offset)} " +
                "FROM ${o.schema}.sessions o WHERE NOT ($seen)"
            before += o.schema
        }
        parts.joinToString(" UNION ALL ", "(", ")")
    }

    /**
     * Los juegos cuyo nombre de hoy no es el apuntado: la consola y el fichero tal como se
     * apuntaron, y el nombre que les toca. Solo esos, y uno por juego: es una lista por juego y
     * no por partida, asi que con miles de partidas sigue siendo corta.
     *
     * Las referencias salen de la tabla `names` de todos los cuadernos (ver Logbook.remember). Un
     * cuaderno sin ella —uno de antes, o de RetroCompanion— simplemente no aporta ninguna.
     */
    private fun retitled(schemas: List<String>, names: GameNames): List<Triple<String, String, String>> {
        val db = book.readableDatabase
        return schemas.flatMap { s ->
            db.rawQuery(
                "SELECT DISTINCT system, title, display_name FROM $s.sessions WHERE system IS NOT NULL AND title IS NOT NULL",
                null,
            ).use { c -> c.rows { Triple(it.getString(0), it.getString(1), if (it.isNull(2)) null else it.getString(2)) } }
                .mapNotNull { (system, title, recorded) ->
                    val today = names.resolve(catalog?.canonicalId(system) ?: system, fileKey(title), recorded, refs)
                    if (today != null && today != recorded) Triple(system, title, today) else null
                }
        }.distinctBy { (system, title, _) -> system to title }
    }

    /**
     * Las referencias de todos los cuadernos enganchados: la tabla `names` de cada uno (ver
     * Logbook.remember), con el genero si la suya ya lo tiene. Una vez por lectura.
     */
    private val refs: List<Logbook.NameRef> by lazy {
        val db = book.readableDatabase
        (listOf("main") + book.others.map { it.schema }).flatMap { s ->
            // Preguntado antes, y no dejado fallar: SQLite apunta en el log cada consulta a una
            // tabla que no existe, y el cuaderno de otra consola sin ella se lee muchas veces.
            val cols = db.rawQuery("PRAGMA $s.table_info(names)", null).use { c -> c.rows { it.getString(1) } }
            if (cols.isEmpty()) return@flatMap emptyList()
            val genre = if ("genre" in cols) "genre" else "NULL"
            val done = if ("done_at" in cols) "done_at" else "NULL"
            runCatching {
                db.rawQuery("SELECT system, file, name, identity, changed_at, $genre, $done FROM $s.names", null).use { c ->
                    c.rows {
                        Logbook.NameRef(
                            it.getString(0), it.getString(1), it.getString(2),
                            if (it.isNull(3)) null else it.getString(3), it.getLong(4),
                            if (it.isNull(5)) null else it.getString(5),
                            if (it.isNull(6)) null else it.getLong(6),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    /**
     * Cuando se marco terminado cada juego, en cualquier consola: una fecha por juego. Con las de
     * esta biblioteca que aun no llegaron al cuaderno (un juego terminado sin jugarlo aqui).
     */
    fun completions(local: List<Long> = emptyList()): List<Long> =
        (refs.filter { it.doneAt != null }.distinctBy { it.system to it.file }.mapNotNull { it.doneAt } + local).distinct()

    /** Las misiones cumplidas en cualquier consola: cual y cuando. */
    fun missionsDone(): List<Pair<String, Long>> {
        val db = book.readableDatabase
        return (listOf("main") + book.others.map { it.schema }).flatMap { s ->
            val has = db.rawQuery("SELECT 1 FROM $s.sqlite_master WHERE type = 'table' AND name = 'missions_done'", null).use { it.moveToFirst() }
            if (!has) emptyList()
            else db.rawQuery("SELECT id, done_at FROM $s.missions_done", null).use { c -> c.rows { it.getString(0) to it.getLong(1) } }
        }.distinct()
            // Solo las que existen: una mision que se quita del codigo —la de prueba, o una que
            // se retire— deja su fila en el cuaderno, que no se borra, y no debe seguir contando
            // para los logros ni para la experiencia.
            .filter { (id, _) -> Missions.byId(id) != null }
    }

    /**
     * Los generos de un juego del Companion, en nombres propios: lo que el metagame reparte entre
     * sus vertices. Vacio si no se sabe ninguno, que es experiencia neutra. Ver GameNames.genres.
     *
     * Por el fichero y la consola de una de sus partidas: el nombre que se ve puede ser el de
     * hoy, y es el fichero lo que lo une a su ficha, aqui o en la otra consola.
     */
    fun genresOf(title: String, system: String): List<String> {
        val n = names ?: return emptyList()
        val file = q(
            """
            SELECT title FROM sessions
            WHERE COALESCE(display_name, title, '?') = '${esc(title)}' AND COALESCE(system, '?') = '${esc(system)}'
              AND title IS NOT NULL
            ORDER BY started_at DESC LIMIT 1
            """,
        ) { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return emptyList()
        return n.genres(system, fileKey(file), refs, ::catalogGenres)
    }

    /**
     * Una partida con todo lo que miran el personaje, los logros y las misiones: cuando, cuanto,
     * donde, de que genero, y lo que dijeron la bateria, el calor y los fotogramas.
     */
    data class Play(
        val session: Metagame.Session,
        val device: String,
        val system: String,
        /** El fichero, como lo compara fileKey. */
        val file: String,
        val emulator: String?,
        val batteryEndPct: Int?,
        val charged: Boolean,
        val tempMaxC: Float?,
        val fpsMean: Float?,
        val powerMeanW: Float?,
        /** La bateria que se gasto, en baterias enteras de su consola: 0,25 es un cuarto. */
        val charges: Float? = null,
        /** Los ciclos de carga que llevaba la bateria al acabar, segun el aparato. */
        val cycles: Int? = null,
    ) {
        /**
         * Lo que le saco a la bateria, en vatios-hora. Con el cargador puesto, nada: la corriente
         * que se mide entonces es la de la carga, no la del juego, y en una consola de pruebas salian partidas
         * de 33 W que eran el cargador llenando la bateria.
         */
        val energyWh: Double get() = if (charged) 0.0 else (powerMeanW ?: 0f) * session.durationMs / 3_600_000.0
    }

    /** Todas las partidas terminadas, de todas las consolas enganchadas, de la mas vieja a la mas nueva. */
    fun plays(): List<Play> {
        val n = names
        val cache = HashMap<String, List<String>>()
        val full = fullExpr()
        return q(
            """
            SELECT id, system, title, started_at, duration_ms, device, package_name, battery_end_pct,
                   charged, temp_max_c, fps_mean, power_mean_w,
                   CASE WHEN charge_used_uah IS NOT NULL AND $full > 0 THEN charge_used_uah * 1.0 / $full END,
                   battery_cycles
            FROM sessions WHERE ended_at IS NOT NULL AND duration_ms > 0 ORDER BY started_at
            """,
        ) { c ->
            c.rows {
                val system = if (it.isNull(1)) "?" else it.getString(1)
                val file = if (it.isNull(2)) "" else fileKey(it.getString(2))
                val genres = if (n == null || file.isEmpty()) emptyList()
                else cache.getOrPut("$system\u001F$file") { n.genres(system, file, refs, ::catalogGenres) }
                Play(
                    session = Metagame.Session(it.getLong(0), it.getLong(3), it.getLong(4), genres),
                    device = it.getString(5).orEmpty(),
                    system = system,
                    file = file,
                    emulator = if (it.isNull(6)) null else it.getString(6),
                    batteryEndPct = if (it.isNull(7)) null else it.getInt(7),
                    charged = !it.isNull(8) && it.getInt(8) != 0,
                    tempMaxC = if (it.isNull(9)) null else it.getFloat(9),
                    fpsMean = if (it.isNull(10)) null else it.getFloat(10),
                    powerMeanW = if (it.isNull(11)) null else it.getFloat(11),
                    charges = if (it.isNull(12)) null else it.getFloat(12),
                    cycles = if (it.isNull(13)) null else it.getInt(13),
                )
            }
        }
    }

    /**
     * Los generos que da el catalogo para un juego por su nombre canonico, en su consola.
     *
     * De las fichas ya guardadas, si hay un juego de aqui con ese nombre: abrir el paquete para
     * esto hacia que, tras cada repaso, el Companion volviera a leer entero el de PC. Solo si el
     * juego no esta en esta biblioteca —jugado en la otra consola— se mira el paquete, y entonces
     * solo su indice de nombres.
     */
    private fun catalogGenres(system: String, identity: String): List<String> =
        runCatching {
            Dossiers.of(system).values.firstOrNull { it.name.equals(identity, ignoreCase = true) }?.catalogGenres
                ?.takeIf { it.isNotEmpty() }
                ?: GameDb.packsFor(system).firstNotNullOfOrNull { it.named(identity) }
                    ?.let { r -> listOf(r.genreWd, r.genreGt, r.genreLr).flatMap { it.split('|') }.filter(String::isNotBlank) }
        }.getOrNull().orEmpty()

    /**
     * La tabla temporal de [retitled], en esta conexion: vive lo que ella, y no se escribe en
     * ningun cuaderno. Se rehace entera cada vez; son tantas filas como juegos renombrados.
     */
    private fun fillRetitle(rows: List<Triple<String, String, String>>) {
        val db = book.readableDatabase
        db.execSQL(
            "CREATE TEMP TABLE IF NOT EXISTS retitle " +
                "(system TEXT NOT NULL, title TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY (system, title))",
        )
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM temp.retitle")
            for ((system, title, name) in rows) {
                db.execSQL("INSERT OR REPLACE INTO temp.retitle VALUES (?, ?, ?)", arrayOf<Any?>(system, title, name))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Las medidas, de este cuaderno y de los de otras consolas, con el numero de su partida
     * corrido igual que en [source]: asi cada una sigue colgando de la suya.
     */
    private val samples: String by lazy {
        val others = book.others
        if (others.isEmpty()) return@lazy "samples"
        val cols = columns("main", "samples")
        fun pick(have: Set<String>, offset: Long) = cols.joinToString(", ") { col ->
            when {
                col !in have -> "NULL AS $col"
                (col == "id" || col == "session_id") && offset > 0 -> "$col + $offset AS $col"
                else -> col
            }
        }
        (listOf("SELECT ${pick(cols.toSet(), 0)} FROM main.samples") + others.map { o ->
            "SELECT ${pick(columns(o.schema, "samples").toSet(), o.offset)} FROM ${o.schema}.samples"
        }).joinToString(" UNION ALL ", "(", ")")
    }

    /** Las columnas de una tabla en uno de los cuadernos: pueden ser de versiones distintas. */
    private fun columns(schema: String, table: String): List<String> =
        book.readableDatabase.rawQuery("PRAGMA $schema.table_info($table)", null).use { c -> c.rows { it.getString(1) } }

    data class Overview(
        val sessions: Int,
        val games: Int,
        val totalMs: Long,
        val longestMs: Long,
        /** Todo lo que los juegos le han sacado a la bateria, en vatios-hora. */
        val energyWh: Float?,
        val peakTempC: Float?,
        /** Baterias enteras gastadas jugando. */
        val charges: Float?,
        /** Dias seguidos con algo jugado: ahora y el mejor. */
        val streakDays: Int,
        val bestStreakDays: Int,
        val firstAt: Long?,
        val lastAt: Long?,
    )

    /** Una consola, un juego o un tramo de tiempo, con lo jugado dentro. */
    data class Slice(val label: String, val totalMs: Long, val sessions: Int)

    // Los juegos, contados como en la pestana GAMES: por nombre y consola (ver GAME_KEY). Iba
    // por `title`, que es el FICHERO: un juego de tres discos contaba tres, y la tarjeta no
    // decia lo mismo que la lista.
    //
    // El pico de CPU, en cambio, solo de esta consola: es del aparato, no del jugador, y con los
    // cuadernos juntos una consola enseñaba el de otra, que calienta mas.
    fun overview(fullChargeUah: Long?): Overview = q(
        """
        SELECT COUNT(*), COUNT(DISTINCT $GAME_KEY), COALESCE(SUM(duration_ms), 0),
               COALESCE(MAX(duration_ms), 0),
               MAX(CASE WHEN device = '${esc(here)}' THEN temp_max_c END), MIN(started_at), MAX(started_at)
        FROM sessions WHERE ended_at IS NOT NULL
        """,
    ) { c ->
        if (!c.moveToFirst()) return@q Overview(0, 0, 0, 0, null, null, null, 0, 0, null, null)
        val run = streak()
        Overview(
            sessions = c.getInt(0),
            games = c.getInt(1),
            totalMs = c.getLong(2),
            longestMs = c.getLong(3),
            energyWh = energyWh(),
            peakTempC = if (c.isNull(4)) null else c.getFloat(4),
            charges = chargesSpent(fullChargeUah),
            streakDays = run.first,
            bestStreakDays = run.second,
            firstAt = if (c.isNull(5)) null else c.getLong(5),
            lastAt = if (c.isNull(6)) null else c.getLong(6),
        )
    }

    /** El tiempo por consola, de mas a menos. */
    fun bySystem(): List<Slice> = q(
        """
        SELECT COALESCE(system, '?'), SUM(duration_ms), COUNT(*)
        FROM sessions WHERE ended_at IS NOT NULL
        GROUP BY COALESCE(system, '?') ORDER BY SUM(duration_ms) DESC
        """,
    ) { c -> c.rows { Slice(it.getString(0), it.getLong(1), it.getInt(2)) } }

    /** Una partida del registro, para la lista. */
    data class Entry(
        val id: Long,
        val title: String,
        val system: String?,
        val startedAt: Long,
        val durationMs: Long,
        val mah: Float?,
        val tempMaxC: Float?,
        val fpsMean: Float?,
        /** En que consola se jugo: con los cuadernos de otras, no siempre en esta. */
        val device: String = "",
        /** Con el cargador puesto: entonces no hay gasto que medir, y eso es lo que se dice. */
        val charged: Boolean = false,
    )

    /** Un punto de la traza de una partida: una tanda de medidas ya resumida. */
    data class Point(
        val at: Long,
        val tempC: Float?,
        val gpuTempC: Float?,
        val cpu: Float?,
        val gpu: Float?,
        val fps: Float?,
        val cpuMhz: Int?,
        val gpuMhz: Int?,
    )


    /** Lo que cuesta un juego y lo que queda de bateria a ese ritmo. */
    data class Rate(
        val title: String,
        val system: String?,
        val mahPerHour: Float,
        /** Horas que darian con la carga de ahora. Nulo si no se puede leer la carga. */
        val hoursLeft: Float?,
    )

    /** Lo que la bateria da hoy frente a lo que daba de fabrica. */
    data class Health(val fullUah: Long?, val designUah: Long?, val cycles: Int?)

    /** Las ultimas partidas, la mas reciente arriba. */
    fun recent(limit: Int = 300): List<Entry> = q(
        """
        SELECT id, COALESCE(display_name, title, '?'), system, started_at, duration_ms,
               charge_used_uah, temp_max_c, fps_mean, device, charged
        FROM sessions WHERE ended_at IS NOT NULL
        ORDER BY started_at DESC LIMIT $limit
        """,
    ) { c -> c.rows { entry(it) } }

    /** Una fila de partida, con las columnas en el orden en que las piden las dos consultas. */
    private fun entry(c: Cursor) = Entry(
        id = c.getLong(0),
        title = c.getString(1),
        system = if (c.isNull(2)) null else c.getString(2),
        startedAt = c.getLong(3),
        durationMs = c.getLong(4),
        mah = if (c.isNull(5)) null else c.getLong(5) / 1000f,
        tempMaxC = if (c.isNull(6)) null else c.getFloat(6),
        fpsMean = if (c.isNull(7)) null else c.getFloat(7),
        device = c.getString(8).orEmpty(),
        charged = !c.isNull(9) && c.getInt(9) != 0,
    )

    /** Como se llama esta consola en el cuaderno: lo que apunta el seguidor en cada partida. */
    val here: String = android.os.Build.MODEL.orEmpty().ifEmpty { "handheld" }

    /**
     * Si el cuaderno trae partidas de mas de una consola. Entonces lo que depende del aparato
     * —la bateria, el calor, la velocidad, el consumo— no se puede mezclar: ver [rank].
     */
    /**
     * Para las cifras que dependen del aparato —bateria, consumo, temperatura, fotogramas, GPU— en
     * la ficha de un juego, la lista de juegos y el resumen de una consola: con mas de una consola en
     * el cuaderno, solo lo jugado en ESTA, como en rank(). El tiempo y las partidas si se suman.
     * Antes se promediaban juntas: una hora a 1.500 mAh/h en una y otra a 900 en la otra daba 1.200,
     * una cifra que no da ninguna (07-10-2026).
     */
    private val mine: String get() = if (mixed) " AND device = '${esc(here)}'" else ""

    val mixed: Boolean by lazy { q("SELECT COUNT(DISTINCT device) FROM sessions") { c -> c.moveToFirst() && c.getInt(0) > 1 } }

    /**
     * La traza de una partida: como fue por dentro, tanda a tanda.
     *
     * Es lo unico que distingue una partida que fue bien de otra que fue mal con la misma
     * media. Sesenta de media con una caida a veinte en el minuto tres no se parece en nada a
     * sesenta clavados, y el promedio de la fila de la sesion dice lo mismo de las dos.
     */
    fun trace(sessionId: Long): List<Point> = q(
        """
        SELECT at, temp_mean_c, gpu_temp_mean_c, cpu_mean_pct, gpu_mean_pct, fps_mean,
               cpu_mean_mhz, gpu_mean_mhz
        FROM samples WHERE session_id = $sessionId ORDER BY at
        """,
    ) { c ->
        c.rows {
            Point(
                at = it.getLong(0),
                tempC = if (it.isNull(1)) null else it.getFloat(1),
                gpuTempC = if (it.isNull(2)) null else it.getFloat(2),
                cpu = if (it.isNull(3)) null else it.getFloat(3),
                gpu = if (it.isNull(4)) null else it.getFloat(4),
                fps = if (it.isNull(5)) null else it.getFloat(5),
                cpuMhz = if (it.isNull(6)) null else it.getInt(6),
                gpuMhz = if (it.isNull(7)) null else it.getInt(7),
            )
        }
    }

    /**
     * Con que grueso se corta el tiempo en el grafico.
     *
     * La hora es la rara de las cuatro y es a proposito: no es un tramo del calendario sino
     * una casilla del reloj. Todo lo jugado entre las nueve y las diez de la noche, del dia
     * que sea. Responde «cuando juego», que es lo unico que las otras tres no pueden contar.
     */
    enum class Grain(val label: String) {
        HOUR("hour"), DAY("day"), WEEK("week"), MONTH("month")
    }

    /**
     * Lo jugado por tramo, del mas antiguo al mas reciente y CON los tramos vacios.
     *
     * Rellenar los huecos no es adorno. Un dia sin jugar, o un hueco a las cuatro de la
     * madrugada, son parte del retrato; y una lista de solo lo que tiene algo dentro miente
     * sobre el ritmo, porque pega dos dias separados por una semana como si fueran seguidos.
     * Ademas, sin relleno, un cuaderno recien empezado sale como una barra que ocupa la
     * pantalla entera.
     */
    fun buckets(grain: Grain): List<Slice> {
        if (grain == Grain.HOUR) return hours()
        val local = "started_at / 1000, 'unixepoch', 'localtime'"
        val bucket = when (grain) {
            Grain.HOUR, Grain.DAY -> "strftime('%Y-%m-%d', $local)"
            // Cada semana por la fecha de su lunes, aqui y abajo. Iba por su numero, y SQLite
            // y Java no numeran igual: «%W» empieza en lunes y llama 00 a los dias antes del
            // primero, y «ww» empieza en domingo y llama 1 a la del 1 de enero. No casaban
            // nunca, y cada barra ensenaba lo de la semana siguiente.
            Grain.WEEK -> "date($local, '-6 days', 'weekday 1')"
            Grain.MONTH -> "strftime('%Y-%m', $local)"
        }
        val found = HashMap<String, Slice>()
        q(
            """
            SELECT $bucket AS b, SUM(duration_ms), COUNT(*)
            FROM sessions WHERE ended_at IS NOT NULL GROUP BY b
            """,
        ) { c ->
            while (c.moveToNext()) {
                found[c.getString(0)] = Slice(c.getString(0), c.getLong(1), c.getInt(2))
            }
        }

        val (steps, field, key, show) = when (grain) {
            Grain.DAY -> Quad(21, java.util.Calendar.DAY_OF_YEAR, "yyyy-MM-dd", "d/M")
            Grain.WEEK -> Quad(12, java.util.Calendar.WEEK_OF_YEAR, "yyyy-MM-dd", "'w'ww")
            else -> Quad(12, java.util.Calendar.MONTH, "yyyy-MM", "MMM")
        }
        val keyer = java.text.SimpleDateFormat(key, java.util.Locale.US)
        val shower = java.text.SimpleDateFormat(show, java.util.Locale.getDefault())
        val cal = java.util.Calendar.getInstance()
        if (grain == Grain.WEEK) {
            // Al lunes de esta semana, que es la fecha con la que la consulta nombra cada una.
            val back = (cal.get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY + 7) % 7
            cal.add(java.util.Calendar.DAY_OF_YEAR, -back)
        }
        cal.add(field, -(steps - 1))
        return (0 until steps).map {
            val d = cal.time
            cal.add(field, 1)
            found[keyer.format(d)]?.copy(label = shower.format(d)) ?: Slice(shower.format(d), 0L, 0)
        }
    }

    private data class Quad(val steps: Int, val field: Int, val key: String, val show: String)

    /**
     * Las 24 horas del reloj, con lo jugado en CADA una.
     *
     * Partida a partida y no con un GROUP BY: el grupo le daba la partida entera a la hora en
     * que empezo, y una de las nueve y cuarenta a las doce y diez ponia dos horas y media en
     * «21» y nada en las 22, las 23 ni las 00, que es justo lo que la barra de las once tiene
     * que decir. Cada partida se reparte entre las horas que toco; la cuenta de partidas, en
     * la hora en que empezo, que es la que la tiene.
     */
    private fun hours(): List<Slice> {
        val ms = LongArray(24)
        val count = IntArray(24)
        q("SELECT started_at, duration_ms FROM sessions WHERE ended_at IS NOT NULL") { c ->
            val cal = java.util.Calendar.getInstance()
            while (c.moveToNext()) {
                var at = c.getLong(0)
                var left = if (c.isNull(1)) 0L else c.getLong(1)
                cal.timeInMillis = at
                count[cal.get(java.util.Calendar.HOUR_OF_DAY)]++
                while (left > 0) {
                    cal.timeInMillis = at
                    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    // Hasta la siguiente hora en punto, o hasta el final.
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.add(java.util.Calendar.HOUR_OF_DAY, 1)
                    val piece = minOf(left, (cal.timeInMillis - at).coerceAtLeast(1L))
                    ms[h] += piece
                    at += piece
                    left -= piece
                }
            }
        }
        return (0 until 24).map { h -> Slice("%02d".format(h), ms[h], count[h]) }
    }

    /**
     * Los aparatos del registro: ESTE primero, y los demas por lo jugado.
     *
     * El primero es el que lleva el brillo fuerte, en la barra de aparatos y en cada barra de
     * consola. Iba primero el mas jugado, y con un cuaderno traido de otra consola el brillo
     * fuerte se lo quedaba la otra: se leia al reves, como si lo de fuera fuera lo de aqui.
     * Con el mismo nombre con el que el tracker apunta las partidas; el orden es estable, asi
     * que los demas siguen por tiempo.
     */
    fun devices(
        here: String = this.here,
    ): List<String> = q(
        """
        SELECT device, SUM(duration_ms) FROM sessions WHERE ended_at IS NOT NULL
        GROUP BY device ORDER BY SUM(duration_ms) DESC
        """,
    ) { c -> c.rows { it.getString(0) } }.sortedByDescending { it == here }

    /** Una consola con su tiempo repartido entre los aparatos en los que se jugo. */
    data class Split(val label: String, val totalMs: Long, val parts: List<Pair<String, Long>>)

    /**
     * El tiempo por consola, partido por aparato.
     *
     * Los totales son las partes sumadas, asi que el mismo barrido agrupado responde a las
     * dos cosas. Pedirlos por separado seria leer la tabla dos veces para saber lo mismo.
     */
    fun bySystemPerDevice(devices: List<String>): List<Split> {
        val parts = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
        q(
            """
            SELECT COALESCE(system, '?'), device, SUM(duration_ms)
            FROM sessions WHERE ended_at IS NOT NULL GROUP BY 1, 2
            """,
        ) { c ->
            while (c.moveToNext()) {
                parts.getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1) to c.getLong(2)
            }
        }
        return parts.map { (system, list) ->
            Split(
                label = system,
                totalMs = list.sumOf { it.second },
                parts = list.sortedBy { devices.indexOf(it.first) },
            )
        }.sortedByDescending { it.totalMs }
    }

    /**
     * Lo que cada juego le saca a la bateria por hora, y cuanto duraria la carga de ahora.
     *
     * Solo las diez ultimas partidas de CADA juego, no todas. No por velocidad —sumar unos
     * miles de filas no cuesta nada— sino porque una partida vieja se midio con otra version
     * del emulador, otro perfil de rendimiento y una bateria mas sana, asi que miente sobre
     * lo que la consola va a hacer esta tarde. Por partidas recientes y no por dias
     * recientes, porque un juego que se toca una vez al mes se quedaria sin estimacion.
     *
     * Y fuera las jugadas con el cargador puesto: ahi el contador SUBE mientras se juega y la
     * cuenta no diria nada.
     */
    fun rates(chargeNowUah: Long?, device: String, limit: Int = 8): List<Rate> = q(
        """
        SELECT g, SUM(used), SUM(ms), MAX(sys) FROM (
            SELECT COALESCE(display_name, title, '?') AS g,
                   system AS sys, charge_used_uah AS used, duration_ms AS ms,
                   ROW_NUMBER() OVER (
                       PARTITION BY COALESCE(display_name, title, '?'), COALESCE(system, '?')
                       ORDER BY started_at DESC
                   ) AS n
            FROM sessions
            WHERE ended_at IS NOT NULL AND charged = 0 AND charge_used_uah IS NOT NULL
              AND duration_ms > 0 AND device = '${esc(device)}'
        ) WHERE n <= $RECENT GROUP BY g, COALESCE(sys, '?')
        """,
    ) { c ->
        c.rows {
            val uah = it.getDouble(1)
            val ms = it.getDouble(2)
            val perHour = if (ms <= 0.0) 0f else ((uah / 1000.0) / (ms / 3_600_000.0)).toFloat()
            Rate(
                system = if (it.isNull(3)) null else it.getString(3),
                title = it.getString(0),
                mahPerHour = perHour,
                hoursLeft = if (perHour <= 0f || chargeNowUah == null) null
                            else (chargeNowUah / 1000f) / perHour,
            )
        }.sortedByDescending { it.mahPerHour }.take(limit)
    }

    // ---------------------------------------------------------------- rankings

    /**
     * Lo que se puede medir, con su rotulo y su unidad.
     *
     * Copiado de RetroCompanion entero, incluida la eleccion de que columnas valen. La carga
     * en tanto por ciento y en miliamperios son la MISMA pregunta con dos reglas: el tanto por
     * ciento es lo que se entiende y el contador de microamperios es el que no miente en
     * partidas cortas, porque el otro va a saltos de una unidad.
     */
    enum class Metric(val label: String, val unit: String) {
        BATTERY_PCT_H("Drain", "%/h"),
        BATTERY_PCT_MIN("Drain", "%/min"),
        BATTERY_MAH_H("Drain", "mAh/h"),
        TEMP_MEAN("CPU average", "°C"),
        TEMP_MAX("CPU peak", "°C"),
        GPU_TEMP_MEAN("GPU average", "°C"),
        GPU_TEMP_MAX("GPU peak", "°C"),
        FPS_MEAN("Frames average", "fps"),
        FPS_MIN("Frames floor", "fps"),
        CPU_MHZ("CPU average", "MHz"),
        CPU_MIN_MHZ("CPU floor", "MHz"),
        GPU_MHZ("GPU average", "MHz"),
        POWER_MEAN("Draw average", "W"),
        POWER_MAX("Draw peak", "W"),
    }

    /**
     * Por que se agrupa.
     *
     * «Consola» es ambiguo en un aparato que emula veinte, y aqui se resuelve como lo haria
     * quien juega: una CONSOLA es la maquina emulada —una SNES, una PS1— y un APARATO es el
     * cacharro que tienes en las manos. Las columnas de la base conservan sus nombres viejos.
     */
    enum class GroupBy(val label: String, val column: String) {
        GAME("By game", "COALESCE(display_name, title)"),
        // '?' y no 'other', como en todas las demas: la fila se abre, y la pantalla de la
        // consola busca por '?'. Con 'other' no encontraba nada.
        SYSTEM("By console", "COALESCE(system, '?')"),
        DEVICE("By device", "device"),
    }

    /** Una fila del ranking: lo agrupado, la cifra pedida y el tiempo que hay detras. */
    data class Row(
        val label: String,
        val system: String?,
        val value: Float,
        val totalMs: Long,
        val sessions: Int,
    )

    /**
     * Una media ponderada POR TIEMPO, contando solo las partidas que traen el valor.
     *
     * La forma evidente —SUM(valor * duracion) / SUM(duracion)— divide en silencio por tiempo
     * que nunca aporto: una columna anadida en una version posterior esta vacia en todo lo
     * apuntado antes, y esas horas siguen cayendo en el divisor. Un juego medido a 59
     * fotogramas durante dos minutos, al lado de tres minutos grabados antes de que existieran
     * los fotogramas, salia a 24. El divisor tiene que ser el tiempo que el valor cubre.
     */
    private fun byTime(column: String): String =
        "SUM(CASE WHEN $column IS NOT NULL THEN $column * duration_ms END) / " +
            "SUM(CASE WHEN $column IS NOT NULL THEN duration_ms END)"

    /** Medidas donde lo interesante es el extremo bajo, asi que la lista abre por ahi. */
    private fun Metric.floorFirst(): Boolean = this == Metric.CPU_MIN_MHZ || this == Metric.FPS_MIN

    /**
     * El ranking de una medida, agrupado como se pida.
     *
     * El SQL es el de RetroCompanion palabra por palabra: las cifras tienen que ser las
     * mismas en los dos programas o el cuaderno deja de ser el mismo cuaderno.
     */
    fun rank(metric: Metric, groupBy: GroupBy): List<Row> {
        val value = when (metric) {
            Metric.BATTERY_PCT_H ->
                "SUM(battery_start_pct - battery_end_pct) * 3600000.0 / SUM(duration_ms)"
            Metric.BATTERY_PCT_MIN ->
                "SUM(battery_start_pct - battery_end_pct) * 60000.0 / SUM(duration_ms)"
            Metric.BATTERY_MAH_H ->
                "SUM(charge_used_uah) / 1000.0 * 3600000.0 / SUM(duration_ms)"
            Metric.TEMP_MEAN -> byTime("temp_mean_c")
            Metric.TEMP_MAX -> "MAX(temp_max_c)"
            Metric.GPU_TEMP_MEAN -> byTime("gpu_temp_mean_c")
            Metric.GPU_TEMP_MAX -> "MAX(gpu_temp_max_c)"
            Metric.FPS_MEAN -> byTime("fps_mean")
            Metric.FPS_MIN -> "MIN(fps_min)"
            Metric.CPU_MHZ -> byTime("cpu_mean_mhz")
            Metric.CPU_MIN_MHZ -> "MIN(cpu_min_mhz)"
            Metric.GPU_MHZ -> byTime("gpu_mean_mhz")
            Metric.POWER_MEAN -> byTime("power_mean_w")
            Metric.POWER_MAX -> "MAX(power_max_w)"
        }
        // Lo de la carga no significa nada con el cargador puesto, donde el contador SUBE
        // mientras se juega. Los vatios si: enchufada se sacan del rail de entrada, asi que
        // una partida cargando sigue diciendo lo que costo el juego.
        val isBattery = metric.unit.startsWith("%/") || metric.unit == "mAh/h"
        val where = buildString {
            append("ended_at IS NOT NULL AND duration_ms > 0")
            if (isBattery) append(" AND charged = 0")
            // Una bateria que acabo mas llena que empezo estaba enchufada, diga lo que diga
            // la fila. Eso no es gasto negativo, es que no hubo gasto, asi que se deja fuera
            // en vez de promediarlo o de recortarlo a cero, que tiraria la cifra hacia abajo.
            if (metric == Metric.BATTERY_PCT_H || metric == Metric.BATTERY_PCT_MIN) {
                append(" AND battery_start_pct IS NOT NULL AND battery_end_pct IS NOT NULL")
                append(" AND battery_end_pct <= battery_start_pct")
            }
            if (metric == Metric.BATTERY_MAH_H) append(" AND charge_used_uah IS NOT NULL")
            if (groupBy == GroupBy.GAME) append(" AND title IS NOT NULL")
            // Por juego o por consola, solo lo jugado en ESTA: todo lo de aqui depende del
            // aparato. Los mAh de una bateria de 8.600 y de una de 4.950, o los grados de dos
            // chips distintos, no se pueden sumar ni poner en la misma lista. Comparar aparatos
            // es lo que hace «By device».
            if (groupBy != GroupBy.DEVICE && mixed) append(" AND device = '${esc(here)}'")
        }
        // Un juego es su nombre y su consola: ver GAME_KEY.
        val groups = if (groupBy == GroupBy.GAME) "g, COALESCE(system, '?')" else "g"
        return q(
            "SELECT ${groupBy.column} AS g, $value, SUM(duration_ms), COUNT(*), MAX(system) " +
                "FROM sessions WHERE $where GROUP BY $groups HAVING $value IS NOT NULL " +
                "ORDER BY 2 ${if (metric.floorFirst()) "ASC" else "DESC"}",
        ) { c ->
            buildList {
                while (c.moveToNext()) {
                    val label = if (c.isNull(0)) continue else c.getString(0)
                    add(
                        Row(
                            label = label,
                            system = if (c.isNull(4)) null else c.getString(4),
                            // Nada de lo que se mide aqui puede ser menos que nada. Un
                            // negativo seria un sensor mintiendo, y un ranking no es sitio
                            // para discutir con el.
                            value = c.getFloat(1).coerceAtLeast(0f),
                            totalMs = c.getLong(2),
                            sessions = c.getInt(3),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Cuantas lineas tiene el cuaderno: las partidas mas sus medidas.
     *
     * Las dos tablas sumadas y no solo las partidas. Una partida es un renglon; lo que de
     * verdad llena el fichero son las tandas de medidas, una cada diez segundos, y por eso
     * cuarenta partidas pesan lo que pesan. Ensenar solo las partidas al lado del tamano
     * dejaria la cuenta sin explicar.
     */
    fun lines(): Int = q("SELECT (SELECT COUNT(*) FROM sessions) + (SELECT COUNT(*) FROM samples)") { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    /**
     * Cuando empezo la primera partida apuntada y cuando la ultima, o nulo sin ninguna.
     *
     * Lo mismo que traen `firstAt` y `lastAt` del resumen, sin el resto: el resumen necesita la
     * carga completa de la bateria para contar cargas, y quien solo quiere dos fechas no tiene
     * por que ir a preguntarsela al aparato.
     */
    fun span(): Pair<Long, Long>? = q(
        "SELECT MIN(started_at), MAX(started_at) FROM sessions WHERE ended_at IS NOT NULL",
    ) { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to c.getLong(1) else null }

    /**
     * La salud de la bateria, de la ultima partida apuntada EN ESTA consola.
     *
     * De la fila y no del aparato a proposito: es lo que la bateria decia al jugar. Y solo de
     * esta: con los cuadernos de otras consolas al lado, la ultima partida podia ser de la otra,
     * y su bateria de 8.600 mAh salia junto a la carga de ahora de esta, que es otra pila.
     */
    // Cada dato, de la ultima partida de esta consola que lo apunto, y no los tres de la ultima
    // partida: en una consola de pruebas las partidas desde el 24-09 traian la capacidad de ahora pero no la de
    // fabrica (ver Telemetry.readVendor), y sin ella la pestaña de bateria se quedaba sin salud.
    fun health(): Health {
        fun latest(col: String): Long? = q(
            """
            SELECT $col FROM sessions
            WHERE ended_at IS NOT NULL AND $col IS NOT NULL AND device = '${esc(here)}'
            ORDER BY started_at DESC LIMIT 1
            """,
        ) { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        return Health(
            fullUah = latest("charge_full_uah"),
            designUah = latest("charge_design_uah"),
            cycles = latest("battery_cycles")?.toInt(),
        )
    }


    /** Lo que se sabe de UN juego, sumando todas sus partidas. */
    data class Summary(
        val sessions: Int,
        /** La consola de la que sale, para nombrarla y colorearla en la cabecera. */
        val system: String?,
        val totalMs: Long,
        val meanSessionMs: Long,
        val mahPerHour: Float?,
        val powerW: Float?,
        val fps: Float?,
        /** El peor momento de todos, no la peor media: ahi es donde se notaron los tirones. */
        val worstFps: Float?,
        val peakTempC: Float?,
        val gpuPercent: Float?,
    )

    /**
     * El resumen de un juego.
     *
     * Las medias van ponderadas POR TIEMPO y no por partida. Promediando las cifras de cada
     * sesion, una de dos minutos pesaria igual que una de dos horas, y entonces una prueba
     * rapida movería la media de un juego al que se han echado tardes enteras.
     *
     * Lo de la bateria deja fuera lo jugado con el cargador puesto: alli el contador sube
     * mientras se juega y la cuenta no diria nada.
     *
     * De un juego de UNA consola, `system` ('?' para las partidas sin ella): ver GAME_KEY.
     */
    fun summary(title: String, system: String): Summary = q(
        """
        SELECT COUNT(*), COALESCE(SUM(duration_ms), 0),
               SUM(CASE WHEN charged = 0 AND charge_used_uah IS NOT NULL$mine THEN charge_used_uah END),
               SUM(CASE WHEN charged = 0 AND charge_used_uah IS NOT NULL$mine THEN duration_ms END),
               SUM(CASE WHEN power_mean_w IS NOT NULL$mine THEN power_mean_w * duration_ms END),
               SUM(CASE WHEN power_mean_w IS NOT NULL$mine THEN duration_ms END),
               SUM(CASE WHEN fps_mean IS NOT NULL$mine THEN fps_mean * duration_ms END),
               SUM(CASE WHEN fps_mean IS NOT NULL$mine THEN duration_ms END),
               MIN(CASE WHEN 1 = 1$mine THEN fps_min END), MAX(CASE WHEN 1 = 1$mine THEN temp_max_c END),
               SUM(CASE WHEN gpu_mean_pct IS NOT NULL$mine THEN gpu_mean_pct * duration_ms END),
               SUM(CASE WHEN gpu_mean_pct IS NOT NULL$mine THEN duration_ms END),
               MAX(system)
        FROM sessions
        WHERE ended_at IS NOT NULL AND COALESCE(display_name, title, '?') = '${esc(title)}'
          AND COALESCE(system, '?') = '${esc(system)}'
        """,
    ) { c ->
        if (!c.moveToFirst()) return@q Summary(0, null, 0, 0, null, null, null, null, null, null)
        val n = c.getInt(0)
        val total = c.getLong(1)
        fun weighted(v: Int, w: Int): Float? {
            if (c.isNull(v) || c.isNull(w) || c.getDouble(w) <= 0.0) return null
            return (c.getDouble(v) / c.getDouble(w)).toFloat()
        }
        Summary(
            sessions = n,
            totalMs = total,
            meanSessionMs = if (n == 0) 0L else total / n,
            // mAh por hora: microamperios-hora entre milisegundos, llevado a horas.
            mahPerHour = if (c.isNull(2) || c.isNull(3) || c.getDouble(3) <= 0.0) null
                         else ((c.getDouble(2) / 1000.0) / (c.getDouble(3) / 3_600_000.0)).toFloat(),
            powerW = weighted(4, 5),
            fps = weighted(6, 7),
            worstFps = if (c.isNull(8)) null else c.getFloat(8),
            peakTempC = if (c.isNull(9)) null else c.getFloat(9),
            gpuPercent = weighted(10, 11),
            system = if (c.isNull(12)) null else c.getString(12),
        )
    }


    /** Un juego con sus cifras comparables, para ordenarlo por lo que se quiera mirar. */
    data class Ranked(
        val title: String,
        /** La consola de la que sale, para poder nombrarla y colorearla en la fila. */
        val system: String?,
        val totalMs: Long,
        val peakTempC: Float?,
        val gpuTempC: Float?,
        val fps: Float?,
        /** El peor momento de todos. Es lo que se recuerda de un juego que da tirones. */
        val worstFps: Float?,
        val mahPerHour: Float?,
        val powerW: Float?,
        val cpuMhz: Int?,
        val gpuMhz: Int?,
        val gpuPercent: Float?,
    )

    /**
     * Todos los juegos con todo lo comparable, de una sola consulta.
     *
     * Una consulta y muchas lecturas, no una por pestana. Calor, fotogramas, reloj y consumo
     * son la misma tabla ordenada de maneras distintas, y leerla una vez por pestana seria
     * pagar siete veces por lo mismo.
     *
     * Las medias van ponderadas POR TIEMPO y no por partida: promediando las cifras de cada
     * sesion, una de dos minutos pesaria igual que una de dos horas.
     *
     * NO se filtra por un minimo de tiempo. Un juego de un minuto puede encabezar la lista del
     * calor y estar diciendo la verdad sobre ese minuto; lo que hace falta no es esconderlo
     * sino ensenar al lado cuanto tiempo lo respalda, y eso va en la propia fila.
     */
    fun ranking(): List<Ranked> = q(
        """
        SELECT COALESCE(display_name, title, '?') AS g,
               COALESCE(SUM(duration_ms), 0),
               MAX(CASE WHEN 1 = 1$mine THEN temp_max_c END), MAX(CASE WHEN 1 = 1$mine THEN gpu_temp_max_c END),
               SUM(CASE WHEN fps_mean IS NOT NULL$mine THEN fps_mean * duration_ms END),
               SUM(CASE WHEN fps_mean IS NOT NULL$mine THEN duration_ms END),
               MIN(CASE WHEN 1 = 1$mine THEN fps_min END),
               SUM(CASE WHEN charged = 0 AND charge_used_uah IS NOT NULL$mine THEN charge_used_uah END),
               SUM(CASE WHEN charged = 0 AND charge_used_uah IS NOT NULL$mine THEN duration_ms END),
               SUM(CASE WHEN power_mean_w IS NOT NULL$mine THEN power_mean_w * duration_ms END),
               SUM(CASE WHEN power_mean_w IS NOT NULL$mine THEN duration_ms END),
               SUM(CASE WHEN cpu_mean_mhz IS NOT NULL$mine THEN cpu_mean_mhz * duration_ms END),
               SUM(CASE WHEN cpu_mean_mhz IS NOT NULL$mine THEN duration_ms END),
               SUM(CASE WHEN gpu_mean_mhz IS NOT NULL$mine THEN gpu_mean_mhz * duration_ms END),
               SUM(CASE WHEN gpu_mean_mhz IS NOT NULL$mine THEN duration_ms END),
               SUM(CASE WHEN gpu_mean_pct IS NOT NULL$mine THEN gpu_mean_pct * duration_ms END),
               SUM(CASE WHEN gpu_mean_pct IS NOT NULL$mine THEN duration_ms END),
               MAX(system)
        FROM sessions WHERE ended_at IS NOT NULL GROUP BY g, COALESCE(system, '?')
        """,
    ) { c ->
        c.rows { r ->
            fun weighted(v: Int, w: Int): Float? {
                if (r.isNull(v) || r.isNull(w) || r.getDouble(w) <= 0.0) return null
                return (r.getDouble(v) / r.getDouble(w)).toFloat()
            }
            Ranked(
                title = r.getString(0),
                system = if (r.isNull(17)) null else r.getString(17),
                totalMs = r.getLong(1),
                peakTempC = if (r.isNull(2)) null else r.getFloat(2),
                gpuTempC = if (r.isNull(3)) null else r.getFloat(3),
                fps = weighted(4, 5),
                worstFps = if (r.isNull(6)) null else r.getFloat(6),
                mahPerHour = if (r.isNull(7) || r.isNull(8) || r.getDouble(8) <= 0.0) null
                             else ((r.getDouble(7) / 1000.0) / (r.getDouble(8) / 3_600_000.0)).toFloat(),
                powerW = weighted(9, 10),
                cpuMhz = weighted(11, 12)?.toInt(),
                gpuMhz = weighted(13, 14)?.toInt(),
                gpuPercent = weighted(15, 16),
            )
        }
    }

    /** Lo que se sabe de UNA consola, sumando todas sus partidas. */
    data class ConsoleSummary(
        val totalMs: Long,
        val sessions: Int,
        val games: Int,
        val peakTempC: Float?,
        val mahPerHour: Float?,
    )

    /**
     * El resumen de una consola.
     *
     * Mismo criterio que el de un juego: lo de la bateria deja fuera lo jugado con el cargador
     * puesto, donde el contador sube mientras se juega y la cuenta no diria nada.
     */
    fun console(systemId: String): ConsoleSummary = q(
        """
        SELECT COALESCE(SUM(duration_ms), 0), COUNT(*),
               COUNT(DISTINCT COALESCE(display_name, title, '?')), MAX(CASE WHEN 1 = 1$mine THEN temp_max_c END),
               SUM(CASE WHEN charged = 0 AND charge_used_uah IS NOT NULL$mine THEN charge_used_uah END),
               SUM(CASE WHEN charged = 0 AND charge_used_uah IS NOT NULL$mine THEN duration_ms END)
        FROM sessions
        WHERE ended_at IS NOT NULL AND COALESCE(system, '?') = '${esc(systemId)}'
        """,
    ) { c ->
        if (!c.moveToFirst()) return@q ConsoleSummary(0, 0, 0, null, null)
        ConsoleSummary(
            totalMs = c.getLong(0),
            sessions = c.getInt(1),
            games = c.getInt(2),
            peakTempC = if (c.isNull(3)) null else c.getFloat(3),
            mahPerHour = if (c.isNull(4) || c.isNull(5) || c.getDouble(5) <= 0.0) null
                         else ((c.getDouble(4) / 1000.0) / (c.getDouble(5) / 3_600_000.0)).toFloat(),
        )
    }

    /** Los juegos de una consola, el mas jugado arriba. */
    fun gamesOf(systemId: String): List<Slice> = q(
        """
        SELECT COALESCE(display_name, title, '?'), SUM(duration_ms), COUNT(*)
        FROM sessions
        WHERE ended_at IS NOT NULL AND COALESCE(system, '?') = '${esc(systemId)}'
        GROUP BY COALESCE(display_name, title, '?')
        ORDER BY SUM(duration_ms) DESC
        """,
    ) { c -> c.rows { Slice(it.getString(0), it.getLong(1), it.getInt(2)) } }
    /** Las partidas de un juego, la mas reciente arriba. */
    fun sessionsOf(title: String, system: String, limit: Int = 300): List<Entry> = q(
        """
        SELECT id, COALESCE(display_name, title, '?'), system, started_at, duration_ms,
               charge_used_uah, temp_max_c, fps_mean, device, charged
        FROM sessions
        WHERE ended_at IS NOT NULL AND COALESCE(display_name, title, '?') = '${esc(title)}'
          AND COALESCE(system, '?') = '${esc(system)}'
        ORDER BY started_at DESC LIMIT $limit
        """,
    ) { c -> c.rows { entry(it) } }

    // ---------------------------------------------------------------- la tarjeta de la lista

    /** Lo jugado de un juego: lo justo para contarlo en tres renglones. */
    data class Tally(
        val sessions: Int,
        val totalMs: Long,
        /** Cuando termino la ultima partida. */
        val lastAt: Long,
        /** Lo que gasta una hora de este juego, en mAh. Nulo si no hay con que saberlo. */
        val mahPerHour: Float?,
    ) {
        /**
         * Las horas que daria de si la carga que se le pase, EN ESTE juego.
         *
         * La de ahora y no la entera: lo que se quiere saber al elegir un juego es si llega la
         * bateria que queda, no cuanto duraria recien cargada. Es lo mismo que dice la
         * tarjeta que sale al empezar a jugar, y con la misma cuenta.
         */
        fun hoursLeft(chargeUah: Long?): Float? {
            val perHour = mahPerHour ?: return null
            if (chargeUah == null || perHour <= 0f) return null
            return (chargeUah / 1000f) / perHour
        }
    }

    /** Un juego jugado, con los ficheros y los nombres con que se apunto. */
    class GamePlayed internal constructor(
        val files: Set<String>,
        val names: Set<String>,
        /** El nombre de su ultima partida. */
        val name: String,
        val tally: Tally,
    )

    /**
     * Lo que se cuenta de cada consola y de cada juego en la tarjeta de la lista.
     *
     * Un juego se reconoce por su FICHERO, y no solo por su nombre. El nombre con que se
     * apunta una partida es el que tenia el juego ese dia, y cambia: en el cuaderno del
     * aparato hay dos partidas de «Castlevania: Symphony of the Night» de un fichero que la
     * lista llama hoy «Castlevania-Symphony of the Night», y buscando solo por el nombre la
     * tarjeta decia que no se habia jugado nunca. El fichero se compara sin la extension,
     * porque las partidas viejas lo apuntaban sin ella. Y el nombre sigue valiendo: los tres
     * discos de un juego son ficheros distintos con un solo nombre, y son un solo juego.
     *
     * Siempre dentro de su consola: el Tetris de la Game Boy no tiene por que cargar con las
     * horas del de la NES.
     */
    class Played internal constructor(
        rows: List<Row>,
        /** Las partidas sin cargador de este aparato, la mas reciente primero. */
        drains: List<Drain>,
    ) {
        /** Las partidas cerradas del cuaderno entero, de todos los aparatos. */
        val sessions: Int = rows.sumOf { it.sessions }

        internal class Row(
            val system: String, val file: String?, val name: String,
            val sessions: Int, val totalMs: Long, val lastAt: Long,
        )
        internal class Drain(
            val system: String, val file: String?, val name: String, val uah: Double, val ms: Double,
        )

        /** Los juegos de cada consola, ya juntados, el mas jugado primero. */
        private val bySystem: Map<String, List<GamePlayed>> = rows.groupBy { it.system }
            .mapValues { (system, own) ->
                join(own).map { part ->
                    val files = part.mapNotNull { it.file?.let(::fileKey) }.toSet()
                    val names = part.map { it.name }.toSet()
                    // Lo que gasta, con las reglas de hoursLeft(): las diez ultimas partidas, en
                    // ESTE aparato y sin el cargador puesto. La lista y la tarjeta que sale al
                    // empezar a jugar lo sacan de aqui las dos; con dos cuentas, un dia darian
                    // cifras distintas para el mismo juego y una de las dos estaria mintiendo.
                    val recent = drains.filter {
                        it.system == system && (it.name in names || it.file?.let(::fileKey) in files)
                    }.take(RECENT)
                    val ms = recent.sumOf { it.ms }
                    val perHour = if (ms <= 0.0) 0.0 else (recent.sumOf { it.uah } / 1000.0) / (ms / 3_600_000.0)
                    GamePlayed(
                        files = files,
                        names = names,
                        name = part.maxBy { it.lastAt }.name,
                        tally = Tally(
                            sessions = part.sumOf { it.sessions },
                            totalMs = part.sumOf { it.totalMs },
                            lastAt = part.maxOf { it.lastAt },
                            mahPerHour = if (perHour > 0.0) perHour.toFloat() else null,
                        ),
                    )
                }.sortedByDescending { it.tally.totalMs }
            }

        fun console(system: String): List<GamePlayed> = bySystem[system].orEmpty()

        /** Todos los juegos con su consola, el jugado mas recientemente primero. */
        fun latest(): List<Pair<String, GamePlayed>> =
            bySystem.flatMap { (system, games) -> games.map { system to it } }
                .sortedByDescending { it.second.tally.lastAt }

        fun game(system: String, file: String, name: String): Tally? {
            val key = fileKey(file)
            return bySystem[system]?.firstOrNull { key in it.files || name in it.names }?.tally
        }

        /**
         * Junta las filas que son el mismo juego: las que comparten fichero o nombre. Una fila
         * puede tender el puente entre dos grupos —el mismo fichero con el nombre nuevo y el
         * viejo— y entonces los dos pasan a ser uno.
         */
        private fun join(rows: List<Row>): List<List<Row>> {
            val parts = mutableListOf<MutableList<Row>>()
            for (r in rows) {
                val key = r.file?.let(::fileKey)
                val hits = parts.filter { part ->
                    part.any { it.name == r.name || (key != null && it.file?.let(::fileKey) == key) }
                }
                val into = hits.firstOrNull() ?: mutableListOf<Row>().also { parts += it }
                for (other in hits.drop(1)) {
                    into += other
                    parts.removeAll { it === other }
                }
                into += r
            }
            return parts
        }
    }

    /** Todo lo de la tarjeta, de dos consultas: lo jugado, y lo que costo en bateria. */
    fun played(device: String): Played {
        val drains = q(
            """
            SELECT COALESCE(system, '?'), title, COALESCE(display_name, title, '?'),
                   charge_used_uah, duration_ms
            FROM sessions
            WHERE ended_at IS NOT NULL AND charged = 0 AND charge_used_uah IS NOT NULL
              AND duration_ms > 0 AND device = '${esc(device)}'
            ORDER BY started_at DESC
            """,
        ) { c ->
            c.rows {
                Played.Drain(
                    it.getString(0), if (it.isNull(1)) null else it.getString(1), it.getString(2),
                    it.getDouble(3), it.getDouble(4),
                )
            }
        }
        val rows = q(
            """
            SELECT COALESCE(system, '?'), title, COALESCE(display_name, title, '?'),
                   COUNT(*), COALESCE(SUM(duration_ms), 0), MAX(ended_at)
            FROM sessions WHERE ended_at IS NOT NULL
            GROUP BY COALESCE(system, '?'), title, COALESCE(display_name, title, '?')
            """,
        ) { c ->
            c.rows {
                Played.Row(
                    it.getString(0), if (it.isNull(1)) null else it.getString(1), it.getString(2),
                    it.getInt(3), it.getLong(4), it.getLong(5),
                )
            }
        }
        return Played(rows, drains)
    }


    /** Comillas dobladas: un titulo lleva apostrofes con mas frecuencia de la que parece. */
    private fun esc(s: String) = s.replace("'", "''")
    /**
     * Vatios-hora: la potencia media por lo que duro, sumado.
     *
     * Un total y no un ritmo. El ritmo sirve para comparar un juego con otro; una cifra de
     * cabecera tiene que crecer con lo que hay detras.
     *
     * Solo de esta consola, como el pico de CPU: es lo que ha tirado ESTE aparato. Con los
     * cuadernos juntos las dos enseñaban la misma cifra, la suma, y no se sabia de cual era.
     * Los logros de energia si suman las dos: esos son del jugador (ver Achievements).
     */
    private fun energyWh(): Float? = q(
        """
        SELECT SUM(power_mean_w * duration_ms) FROM sessions
        WHERE ended_at IS NOT NULL AND power_mean_w IS NOT NULL AND duration_ms > 0 AND charged = 0
          AND device = '${esc(here)}'
        """,
    ) { c ->
        if (!c.moveToFirst() || c.isNull(0)) null else (c.getDouble(0) / 3_600_000.0).toFloat()
    }

    /**
     * Cuantas baterias enteras se han gastado jugando.
     *
     * Cada partida se divide por lo que la bateria daba EL DIA que se jugo, no por lo que da
     * hoy. Una celda pierde capacidad con los anos, asi que dividir todo el historial por la
     * cifra de hoy haria que el total subiera solo, y un numero que cuenta lo que hiciste
     * solo debe moverse cuando haces algo.
     *
     * Y solo de esta consola, por lo mismo que [energyWh].
     */
    private fun chargesSpent(fullChargeUah: Long?): Float? {
        // Sin la capacidad de ahora, solo las partidas que apuntaron la suya. Iba como parametro
        // nulo, y Android no acepta un nulo ahi: la consulta fallaba, con ella el cuaderno entero,
        // y el Companion se quedaba en «reading…» para siempre. Pasa en cualquier aparato que no
        // de la carga completa, y en todos por debajo del 5 %, donde no se estima.
        //
        // Y una partida que no apunto la suya se divide por la bateria de SU consola: la de ahora
        // si es esta, y si es otra, la que esa apunto en sus partidas. Con los cuadernos de otras
        // consolas juntos, la de aqui se aplicaba a todas: una consola de 8.600 mAh contaba de menos
        // lo jugado en otra de 4.950, y esa de mas lo de la primera.
        val full = fullExpr(fullChargeUah)
        return q(
            "SELECT SUM(charge_used_uah * 1.0 / $full) FROM sessions " +
                "WHERE ended_at IS NOT NULL AND charge_used_uah IS NOT NULL AND $full > 0 AND device = '${esc(here)}'",
        ) { c ->
            if (!c.moveToFirst() || c.isNull(0)) null else c.getFloat(0).takeIf { it > 0f }
        }
    }

    /**
     * La capacidad por la que dividir cada partida, como expresion SQL: la que apunto la partida
     * y, si no apunto ninguna, la de su consola (la de ahora si es esta). Ver [chargesSpent].
     */
    private fun fullExpr(fullChargeUah: Long? = null): String {
        val caps = q(
            "SELECT device, MAX(charge_full_uah) FROM sessions WHERE charge_full_uah > 0 GROUP BY device",
        ) { c -> c.rows { it.getString(0) to it.getLong(1) } }.toMap().toMutableMap()
        fullChargeUah?.takeIf { it > 0 }?.let { caps[here] = it }
        return if (caps.isEmpty()) "charge_full_uah" else caps.entries.joinToString(
            " ", "COALESCE(charge_full_uah, CASE device ", " END)",
        ) { (device, uah) -> "WHEN '${esc(device)}' THEN $uah" }
    }

    /**
     * Dias seguidos con algo jugado: el de ahora y el mejor de siempre.
     *
     * Por dia local y no por hora: jugar a las once de la noche y al dia siguiente a la una
     * de la madrugada son dos dias, y a nadie le parece que sea seguido por dos horas.
     */
    private fun streak(): Pair<Int, Int> {
        val days = mutableListOf<Long>()
        q(
            "SELECT DISTINCT CAST(strftime('%s', date(started_at / 1000, 'unixepoch', " +
                "'localtime')) AS INTEGER) / 86400 AS d FROM sessions " +
                "WHERE ended_at IS NOT NULL ORDER BY d",
        ) { c -> while (c.moveToNext()) days += c.getLong(0) }
        if (days.isEmpty()) return 0 to 0

        var best = 1
        var run = 1
        for (i in 1 until days.size) {
            run = if (days[i] == days[i - 1] + 1) run + 1 else 1
            if (run > best) best = run
        }

        val today = book.readableDatabase.rawQuery(
            "SELECT CAST(strftime('%s', date('now', 'localtime')) AS INTEGER) / 86400", null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else return 0 to best }

        // Ayer todavia cuenta: la racha se rompe cuando pasa un dia entero sin jugar, no a
        // medianoche.
        if (days.last() < today - 1) return 0 to best
        var current = 1
        var i = days.size - 1
        while (i > 0 && days[i] == days[i - 1] + 1) {
            current++
            i--
        }
        return current to best
    }

    private fun <T> q(sql: String, read: (Cursor) -> T): T =
        book.readableDatabase.rawQuery(
            sql.trimIndent().replace(TABLE) {
                "${it.groupValues[1]} ${if (it.groupValues[2] == "sessions") source else samples}"
            }, null,
        ).use(read)

    private fun <T> Cursor.rows(one: (Cursor) -> T): List<T> =
        buildList { while (moveToNext()) add(one(this@rows)) }
}

/**
 * Un fichero como se compara con el de una partida: sin la carpeta, sin la extension y en
 * minusculas. Las partidas viejas apuntaban «Mega Man X6 (Europe)» y las nuevas
 * «Mega Man X6 (Europe).chd», y son el mismo juego.
 *
 * Extension es lo que va detras del ultimo punto si es corto, sin espacios y con alguna letra:
 * en «Dr. Mario (USA)» el punto no abre ninguna, y «celeste.p8.png» se queda en «celeste.p8»
 * por los dos lados, que es lo que importa.
 */
internal fun fileKey(file: String): String {
    val name = file.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot + 1) else ""
    val isExt = ext.length in 1..4 && ext.all(Char::isLetterOrDigit) && ext.any(Char::isLetter) ||
        ext.lowercase() in LONG_EXTENSIONS
    val stem = if (isExt) name.substring(0, dot) else name
    return stem.trim().lowercase()
}

/**
 * Las extensiones de mas de cuatro letras que declara el catalogo, casi todas de ficheros que no
 * son el juego sino un acceso a el. Fuera de estas, el tope de cuatro sigue: cualquier palabra
 * tras un punto no es una extension («Mr.Driller»). Por ese tope «…(DLC).steam» no casaba con la
 * partida apuntada sin extension, y la tarjeta de Kingdom Hearts III decia «Not played yet» con
 * media hora apuntada. PlayedTest comprueba que estan todas las del catalogo.
 */
internal val LONG_EXTENSIONS = setOf(
    "amazon", "arduboy", "desktop", "doomforge", "easyrpg", "gamehub", "pcgame", "psvita", "scummvm", "shortcut",
    "steam", "swiffid",
)
