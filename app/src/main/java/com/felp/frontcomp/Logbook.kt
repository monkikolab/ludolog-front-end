package com.felp.frontcomp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * El cuaderno: una fila por partida jugada, y una fila de medidas cada pocos segundos.
 *
 * El esquema es el de RetroCompanion, columna por columna y con los mismos nombres. No por
 * pereza: asi un cuaderno viejo se puede traer aqui, y uno de aqui se puede llevar alli,
 * sin traductor de por medio. Las columnas que aqui no hacen falta —las que existian para
 * ADIVINAR que juego era— se quedan declaradas y vacias, que cuesta nada y mantiene la
 * puerta abierta.
 *
 * El fichero vive en la tarjeta, junto a los medios, y no en la carpeta privada de la
 * aplicacion. Es el registro de alguien: tiene que poder copiarlo, y tiene que sobrevivir a
 * desinstalar la aplicacion.
 *
 * Con [withOthers], para leer: engancha ademas los cuadernos de las otras consolas, y LogStats
 * los lee junto con este (ver Logbooks). Se escribe siempre en el de esta consola: ni se
 * fusionan ni se tocan los de las otras.
 */
internal class Logbook(
    context: Context,
    private val withOthers: Boolean = false,
) : SQLiteOpenHelper(context, PATH, null, VERSION) {

    /** Los cuadernos de otras consolas enganchados a esta conexion, para LogStats. */
    var others: List<Other> = emptyList()
        private set

    /** Uno de otra consola: como se llama en las consultas y cuanto se suma a sus numeros de partida. */
    data class Other(val schema: String, val offset: Long)

    /**
     * La tabla de nombres, si el cuaderno aun no la tiene: los de antes del 26-09-2026 y los de
     * RetroCompanion no la traen (ver [remember]). Anadir una tabla no toca ninguna partida.
     *
     * Y los de las otras consolas, de solo leer y por copia (ver Logbooks.readable). Uno que no
     * se pueda abrir se salta: el de esta consola se lee igual.
     */
    override fun onOpen(db: SQLiteDatabase) {
        if (!db.isReadOnly) runCatching {
            db.execSQL(NAMES)
            // La del 26-09-2026 nacio sin el genero: se le anade, sin tocar sus filas.
            val cols = db.rawQuery("PRAGMA main.table_info(names)", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(1)) }
            }
            if ("genre" !in cols) db.execSQL("ALTER TABLE main.names ADD COLUMN genre TEXT")
            // Y cuando se marco terminado: viaja a las otras consolas con el nombre.
            if ("done_at" !in cols) db.execSQL("ALTER TABLE main.names ADD COLUMN done_at INTEGER")
            db.execSQL(MISSIONS)
        }
        if (!withOthers) return
        others = Logbooks.others().mapIndexedNotNull { i, file ->
            val other = Other("other${i + 1}", OTHER_OFFSET * (i + 1))
            runCatching {
                db.execSQL("ATTACH DATABASE ? AS ${other.schema}", arrayOf(Logbooks.readable(file).path))
                other
            }.onFailure {
                android.util.Log.w("Ludolog", "cuaderno de ${file.name}: no se pudo leer (${it.javaClass.simpleName})")
            }.getOrNull()
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SESSIONS)
        db.execSQL(SAMPLES)
        db.execSQL("CREATE INDEX idx_samples_session ON samples(session_id)")
        db.execSQL("CREATE INDEX idx_sessions_started ON sessions(started_at)")
    }

    /**
     * Migrar, nunca reconstruir. Aqui dentro hay partidas de verdad, y anadir una columna no
     * puede tirar el historial de nadie.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Y tampoco al reves: las tablas son las mismas, el numero solo dice de donde viene. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ------------------------------------------------------------------ escribir

    /**
     * Como se llama hoy un juego jugado en esta consola: su consola (el id del catalogo), su
     * fichero como lo compara fileKey, el nombre, su identidad en el catalogo si se sabe, cuando
     * cambio por ultima vez, y el genero si se le puso a mano (uno o varios, con «|»).
     */
    data class NameRef(
        val system: String,
        val file: String,
        val name: String,
        val identity: String?,
        val at: Long,
        val genre: String? = null,
        /** Cuando se marco terminado, si se marco. */
        val doneAt: Long? = null,
    )

    /**
     * Apunta como se llaman hoy los juegos jugados aqui, sin tocar ninguna partida.
     *
     * Para las otras consolas, que leen este cuaderno con su biblioteca y no con la de aqui: sin
     * esto, un juego renombrado aqui seguia saliendo alli con el nombre que tenia al jugarse, y
     * uno cuyo fichero aqui se llama distinto no se reconocia como el mismo. Ver GameNames.
     *
     * La hora solo cambia si cambia el nombre: entre dos cuadernos gana el cambio mas reciente,
     * no la ultima vez que se abrio la aplicacion. Y lo que no cambia no se reescribe: el fichero
     * es el que viaja a las otras consolas, y tocarlo en cada arranque lo haria viajar siempre.
     */
    fun remember(refs: List<NameRef>) {
        if (refs.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in refs) db.execSQL(REMEMBER, arrayOf<Any?>(r.system, r.file, r.name, r.identity, r.at, r.genre, r.doneAt))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Una mision cumplida en esta consola. En el cuaderno y no en los ajustes: su recompensa es
     * experiencia, y el personaje tiene que ser el mismo en todas las consolas.
     */
    fun missionDone(id: String, at: Long) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO main.missions_done (id, done_at) VALUES (?, ?)", arrayOf<Any?>(id, at))
    }

    /** Los juegos jugados en esta consola, por su consola y su fichero tal como se apuntaron. */
    /**
     * Los juegos que este cuaderno da por terminados, por consola y fichero. Se vuelven a
     * apuntar aunque no se hayan jugado aqui, para que desmarcarlos tambien viaje.
     */
    fun completedFiles(): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT system, file FROM main.names WHERE done_at IS NOT NULL", null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    fun playedFiles(): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT DISTINCT system, title FROM main.sessions WHERE system IS NOT NULL AND title IS NOT NULL", null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }

    /** Abre una partida y devuelve su numero. Se llama al lanzar, no al adivinar. */
    fun open(s: Start): Long = writableDatabase.insert("sessions", null, ContentValues().apply {
        put("device", s.device)
        put("package_name", s.packageName)
        put("emulator", s.emulator)
        put("system", s.system)
        put("title", s.title)
        put("display_name", s.displayName)
        put("started_at", s.startedAt)
        put("charge_start_uah", s.chargeUah)
        put("battery_start_pct", s.batteryPercent)
    })

    /** Una tanda de medidas ya resumida. */
    fun sample(sessionId: Long, at: Long, r: Bucket.Row) {
        writableDatabase.insert("samples", null, ContentValues().apply {
            put("session_id", sessionId)
            put("at", at)
            put("samples", r.samples)
            put("temp_mean_c", r.tempMeanC)
            put("temp_max_c", r.tempMaxC)
            put("gpu_temp_mean_c", r.gpuTempMeanC)
            put("gpu_temp_max_c", r.gpuTempMaxC)
            put("cpu_mean_pct", r.cpuMeanPercent)
            put("cpu_max_pct", r.cpuMaxPercent)
            put("gpu_mean_pct", r.gpuMeanPercent)
            put("gpu_max_pct", r.gpuMaxPercent)
            put("cpu_mean_mhz", r.cpuMeanMhz)
            put("cpu_min_mhz", r.cpuMinMhz)
            put("gpu_mean_mhz", r.gpuMeanMhz)
            put("power_mean_w", r.powerMeanW)
            put("power_max_w", r.powerMaxW)
            put("fps_mean", r.fpsMean)
            put("fps_min", r.fpsMinimum)
        })
    }

    /** Cierra la partida con lo que costo. */
    fun close(sessionId: Long, e: End) {
        writableDatabase.update("sessions", ContentValues().apply {
            put("ended_at", e.endedAt)
            put("duration_ms", e.durationMs)
            put("charge_end_uah", e.chargeUah)
            put("charge_used_uah", e.chargeUsedUah)
            put("battery_end_pct", e.batteryPercent)
            put("charged", if (e.charged) 1 else 0)
            put("temp_mean_c", e.tempMeanC)
            put("temp_max_c", e.tempMaxC)
            put("gpu_temp_mean_c", e.gpuTempMeanC)
            put("gpu_temp_max_c", e.gpuTempMaxC)
            put("cpu_mean_pct", e.cpuMeanPercent)
            put("cpu_max_pct", e.cpuMaxPercent)
            put("gpu_mean_pct", e.gpuMeanPercent)
            put("gpu_max_pct", e.gpuMaxPercent)
            put("cpu_mean_mhz", e.cpuMeanMhz)
            put("cpu_min_mhz", e.cpuMinMhz)
            put("gpu_mean_mhz", e.gpuMeanMhz)
            put("power_mean_w", e.powerMeanW)
            put("power_max_w", e.powerMaxW)
            put("fps_mean", e.fpsMean)
            put("fps_min", e.fpsMinimum)
            put("charge_full_uah", e.chargeFullUah)
            put("charge_design_uah", e.chargeDesignUah)
            put("battery_cycles", e.batteryCycles)
            put("samples", e.samples)
        }, "id = ?", arrayOf(sessionId.toString()))
    }

    /**
     * Cierra las partidas que se quedaron abiertas, o las tira si no hay con que cerrarlas.
     *
     * Si el sistema mata la aplicacion mientras alguien juega, la fila se queda sin cerrar y sin
     * duracion. Antes se borraba entera, y con ella una partida de veinte minutos porque Android
     * se llevo el proceso a mitad. Pero no hay que adivinar cuanto duro: las medidas se guardan
     * cada pocos segundos, y la ultima dice hasta cuando se sabe que se estaba jugando. Se cierra
     * ahi —lo que diga es poco, nunca de mas— y se tira solo si no llego a guardar ninguna o si
     * no pasa del minimo de los ajustes, que es la misma regla que al cerrar una normal.
     */
    fun closeUnfinished(minMs: Long) {
        val db = writableDatabase
        val open = db.rawQuery(
            "SELECT s.id, s.started_at, (SELECT MAX(m.at) FROM samples m WHERE m.session_id = s.id) " +
                "FROM sessions s WHERE s.ended_at IS NULL",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getLong(1), if (c.isNull(2)) null else c.getLong(2))) } }
        for ((id, started, last) in open) {
            val lasted = if (last == null) 0L else (last - started).coerceAtLeast(0L)
            if (last == null || lasted < minMs.coerceAtLeast(1L)) {
                forget(id)
                continue
            }
            db.update("sessions", ContentValues().apply {
                put("ended_at", last)
                put("duration_ms", lasted)
            }, "id = ?", arrayOf(id.toString()))
        }
    }

    /**
     * Tira una partida y sus medidas.
     *
     * Hace falta porque el seguidor tiene formas conocidas de apuntar de mas: salir del
     * emulador a la pantalla de inicio y volver al front-end media hora despues cuenta esa
     * media hora. Sin poder borrarla, una sola partida torcida envenena las medias para
     * siempre, y las medias son justo lo que se viene a mirar.
     */
    fun forget(sessionId: Long) {
        writableDatabase.delete("samples", "session_id = ?", arrayOf(sessionId.toString()))
        writableDatabase.delete("sessions", "id = ?", arrayOf(sessionId.toString()))
    }

    data class Start(
        val device: String,
        val packageName: String,
        val emulator: String?,
        val system: String?,
        val title: String?,
        val displayName: String?,
        val startedAt: Long,
        val chargeUah: Long?,
        val batteryPercent: Int,
    )

    data class End(
        val endedAt: Long,
        val durationMs: Long,
        val chargeUah: Long?,
        val chargeUsedUah: Long?,
        val batteryPercent: Int,
        val charged: Boolean,
        val tempMeanC: Float?,
        val tempMaxC: Float?,
        val gpuTempMeanC: Float?,
        val gpuTempMaxC: Float?,
        val cpuMeanPercent: Float?,
        val cpuMaxPercent: Float?,
        val gpuMeanPercent: Float?,
        val gpuMaxPercent: Float?,
        val cpuMeanMhz: Int?,
        val cpuMinMhz: Int?,
        val gpuMeanMhz: Int?,
        val powerMeanW: Float?,
        val powerMaxW: Float?,
        val fpsMean: Float?,
        val fpsMinimum: Float?,
        val chargeFullUah: Long?,
        val chargeDesignUah: Long?,
        val batteryCycles: Int?,
        val samples: Int,
    )

    internal companion object {
        /**
         * La MISMA version de esquema que RetroCompanion, no una propia.
         *
         * SQLiteOpenHelper se niega a abrir un fichero cuya version sea mayor que la que
         * declara: «can't downgrade database from version 9 to 1». Como las tablas son las
         * suyas columna por columna, y la idea es que un cuaderno se pueda llevar de una
         * aplicacion a la otra, el numero tambien tiene que ser el suyo. Si algun dia RC sube
         * a diez, aqui se sube a diez.
         */
        const val VERSION = 9

        /**
         * Lo que se suma al numero de una partida de otra consola al leerla: la primera empieza
         * en mil millones, la segunda en dos mil millones. Los numeros se repiten entre
         * cuadernos, y la ventana de una partida la busca, con sus medidas, por su numero.
         */
        const val OTHER_OFFSET = 1_000_000_000L

        /** Si una partida leida es de esta consola: las de otras no se pueden olvidar desde aqui. */
        fun isOwn(id: Long): Boolean = id < OTHER_OFFSET

        /**
         * En la carpeta de datos: es el registro de alguien y tiene que sobrevivir a
         * desinstalar, y a cambiar de aparato llevandose la carpeta. Con el nombre de esta
         * consola: ver Logbooks.
         */
        val PATH: String get() = Logbooks.own().path

        /**
         * Los nombres de hoy de los juegos jugados aqui: ver [remember]. Propia de Ludolog;
         * RetroCompanion no la conoce y la deja estar.
         */
        private const val NAMES = """
            CREATE TABLE IF NOT EXISTS names (
                system     TEXT    NOT NULL,
                file       TEXT    NOT NULL,
                name       TEXT    NOT NULL,
                identity   TEXT,
                changed_at INTEGER NOT NULL,
                genre      TEXT,
                PRIMARY KEY (system, file)
            )
        """

        /**
         * Uno nuevo entra; uno que ya estaba solo se reescribe si cambio algo, y su hora solo si
         * cambio el nombre o el genero puesto a mano, que es lo que otra consola tiene que
         * preferir si es mas nuevo. En el SET se leen los valores de antes, asi que el orden no
         * importa. El genero se copia tal cual, tambien vacio: quitarlo a mano es un cambio.
         */
        private const val REMEMBER = """
            INSERT INTO main.names (system, file, name, identity, changed_at, genre, done_at) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(system, file) DO UPDATE SET
                changed_at = CASE WHEN names.name <> excluded.name OR names.genre IS NOT excluded.genre
                    THEN excluded.changed_at ELSE names.changed_at END,
                name = excluded.name,
                identity = COALESCE(excluded.identity, names.identity),
                genre = excluded.genre,
                done_at = excluded.done_at
            WHERE names.name <> excluded.name OR names.genre IS NOT excluded.genre
                OR names.identity IS NOT COALESCE(excluded.identity, names.identity)
                OR names.done_at IS NOT excluded.done_at
                OR names.done_at IS NOT excluded.done_at
        """

        /** Las misiones cumplidas en esta consola. Propia de Ludolog, como names. */
        private const val MISSIONS = """
            CREATE TABLE IF NOT EXISTS missions_done (
                id      TEXT    NOT NULL,
                done_at INTEGER NOT NULL,
                PRIMARY KEY (id, done_at)
            )
        """

        /** Tal cual esta en RetroCompanion. No tocar los nombres. */
        private const val SESSIONS = """
            CREATE TABLE sessions (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                device            TEXT    NOT NULL,
                package_name      TEXT    NOT NULL,
                emulator          TEXT,
                system            TEXT,
                title             TEXT,
                display_name      TEXT,
                fingerprint       TEXT,
                activity          TEXT,
                started_at        INTEGER NOT NULL,
                ended_at          INTEGER,
                duration_ms       INTEGER,
                charge_start_uah  INTEGER,
                charge_end_uah    INTEGER,
                charge_used_uah   INTEGER,
                battery_start_pct INTEGER,
                battery_end_pct   INTEGER,
                charged           INTEGER NOT NULL DEFAULT 0,
                temp_mean_c       REAL,
                temp_max_c        REAL,
                cpu_mean_pct      REAL,
                cpu_max_pct       REAL,
                gpu_mean_pct      REAL,
                gpu_max_pct       REAL,
                cpu_mean_mhz      INTEGER,
                cpu_min_mhz       INTEGER,
                gpu_mean_mhz      INTEGER,
                charge_full_uah   INTEGER,
                charge_design_uah INTEGER,
                battery_cycles    INTEGER,
                fps_mean          REAL,
                fps_min           REAL,
                gpu_temp_mean_c   REAL,
                gpu_temp_max_c    REAL,
                power_mean_w      REAL,
                power_max_w       REAL,
                thermal_max       INTEGER,
                samples           INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val SAMPLES = """
            CREATE TABLE samples (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id   INTEGER NOT NULL,
                at           INTEGER NOT NULL,
                samples      INTEGER NOT NULL,
                temp_mean_c  REAL,
                temp_max_c   REAL,
                cpu_mean_pct REAL,
                cpu_max_pct  REAL,
                gpu_mean_pct REAL,
                gpu_max_pct  REAL,
                cpu_mean_mhz INTEGER,
                cpu_min_mhz  INTEGER,
                gpu_mean_mhz INTEGER,
                fps_mean     REAL,
                fps_min      REAL,
                gpu_temp_mean_c REAL,
                gpu_temp_max_c  REAL,
                power_mean_w REAL,
                power_max_w  REAL
            )
        """
    }
}
