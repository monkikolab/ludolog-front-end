package com.felp.frontcomp

/**
 * Como se llama hoy cada juego, y que es, para leer las partidas con eso y no con lo que se sabia
 * al jugarse.
 *
 * Una partida guarda el nombre del juego EN ESE MOMENTO, y el cuaderno es un registro: no se
 * reescribe. Asi que renombrar un juego despues partia su historia en dos. En el cuaderno de dos
 * consolas ya pasaba: «Metroid Prime Trilogy» y «Metroid Prime: Trilogy», el mismo fichero, dos
 * filas en el Companion con las horas repartidas, y la vieja sin caratula, porque la caratula se
 * busca por el nombre de hoy.
 *
 * El nombre se resuelve al leer, por la consola y el fichero, en este orden:
 *  1. el que el juego tiene hoy en la biblioteca de esta consola;
 *  2. si aqui no esta, la referencia mas reciente de cualquier cuaderno (la tabla `names`, que
 *     cada consola escribe en el suyo: ver Logbook.remember). Por su identidad del catalogo, el
 *     juego de aqui que sea el mismo aunque su fichero se llame distinto; si no, el nombre que
 *     dice la referencia;
 *  3. si no hay nada, el apuntado.
 *
 * El genero, que es lo que el metagame reparte entre sus vertices, igual: ver [genres].
 */
internal class GameNames(
    /** «consola␟fichero» → lo que se sabe del juego de esta biblioteca. El fichero, como lo compara [fileKey]. */
    private val byFile: Map<String, Known>,
    /** «consola␟identidad» → lo mismo. La identidad es el nombre canonico del catalogo. */
    private val byIdentity: Map<String, Known>,
) {
    /**
     * Un juego de esta biblioteca: su nombre de hoy, su identidad en el catalogo, y sus generos,
     * los puestos a mano y los del catalogo por separado: los de a mano mandan tambien sobre los
     * del catalogo de OTRA consola.
     */
    data class Known(
        val name: String,
        val identity: String? = null,
        val ownGenres: List<String> = emptyList(),
        val catalogGenres: List<String> = emptyList(),
    )

    fun name(system: String, file: String): String? = byFile[key(system, file)]?.name

    fun identity(system: String, file: String): String? = byFile[key(system, file)]?.identity

    /** El genero puesto a mano en esta consola, para apuntarlo en la referencia. */
    fun ownGenres(system: String, file: String): List<String> = byFile[key(system, file)]?.ownGenres.orEmpty()

    /** El nombre de hoy de una partida de esa consola y ese fichero. Ver la cabecera. */
    fun resolve(system: String, file: String, recorded: String?, refs: List<Logbook.NameRef>): String? {
        name(system, file)?.let { return it }
        val ref = latest(refs, system, file) ?: return recorded
        ref.identity?.let { id -> byIdentity[key(system, id)]?.let { return it.name } }
        return ref.name
    }

    /**
     * Los generos de las partidas de esa consola y ese fichero, en nombres propios (ver Genres):
     * lo que el metagame reparte entre sus vertices. Vacio si no se sabe ninguno, y entonces esas
     * partidas son experiencia neutra, repartida entre todos por igual: ver docs/metagame.md.
     *
     * Lo puesto a mano manda, se haya puesto aqui o en otra consola; despues el catalogo, del
     * juego de aqui o, si aqui no esta, buscado por su identidad en el paquete de su consola.
     */
    fun genres(
        system: String,
        file: String,
        refs: List<Logbook.NameRef>,
        catalog: (system: String, identity: String) -> List<String>,
    ): List<String> {
        val mine = byFile[key(system, file)]
        val ref = latest(refs, system, file)
        val same = (mine?.identity ?: ref?.identity)?.let { byIdentity[key(system, it)] }
        val raw = mine?.ownGenres.orEmpty()
            .ifEmpty { ref?.genre?.split('|').orEmpty() }
            .ifEmpty { same?.ownGenres.orEmpty() }
            .ifEmpty { mine?.catalogGenres.orEmpty() }
            .ifEmpty { same?.catalogGenres.orEmpty() }
            .ifEmpty { ref?.identity?.let { catalog(system, it) }.orEmpty() }
        return Genres.canon(raw)
    }

    private fun latest(refs: List<Logbook.NameRef>, system: String, file: String): Logbook.NameRef? =
        refs.filter { it.system == system && it.file == file }.maxByOrNull { it.at }

    companion object {
        /**
         * Los de la biblioteca de ahora, para quien lee el cuaderno sin tenerla a mano: las
         * pestañas del Companion y el servicio que mide las partidas. La pone la pantalla
         * principal cada vez que cambia la biblioteca, un nombre o una ficha.
         */
        @Volatile var current: GameNames? = null

        private fun key(system: String, part: String) = "$system\u001F$part"

        /** Para las pruebas: los juegos por consola y fichero, sin fichas. */
        fun of(known: Map<Pair<String, String>, Known>): GameNames {
            val byFile = known.mapKeys { (k, _) -> key(k.first, k.second) }
            val byIdentity = HashMap<String, Known>()
            for ((k, v) in known) v.identity?.let { byIdentity.putIfAbsent(key(k.first, it), v) }
            return GameNames(byFile, byIdentity)
        }

        /** Los de una biblioteca, con el nombre que se ve en la lista. Lee las fichas: fuera del hilo de la pantalla. */
        fun of(games: List<Game>, title: (Game) -> String): GameNames {
            val byFile = HashMap<String, Known>()
            val byIdentity = HashMap<String, Known>()
            for (g in games) {
                val d = Dossiers.get(g)
                val known = Known(title(g), d?.name, d?.ownGenres.orEmpty(), d?.catalogGenres.orEmpty())
                byFile.putIfAbsent(key(g.systemId, fileKey(g.fileName)), known)
                known.identity?.let { byIdentity.putIfAbsent(key(g.systemId, it), known) }
            }
            return GameNames(byFile, byIdentity)
        }
    }
}
