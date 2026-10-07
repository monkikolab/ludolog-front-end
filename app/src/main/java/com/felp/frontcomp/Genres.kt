package com.felp.frontcomp

/**
 * Los generos de cada fuente, traducidos a unos pocos nombres propios.
 *
 * Cada fuente habla a su manera: Wikidata dice «action role-playing game» o «survival horror»,
 * libretro dice «Action» y GameTDB «action,adventure». La ficha los guarda TAL CUAL (ver Dossier)
 * y la traduccion se hace al enseñarlos: cambiar esta tabla no obliga a volver a bajar nada. El
 * metagame hara lo mismo para repartir el tiempo entre sus vertices (docs/metagame.md).
 *
 * El orden importa dos veces. Gana la primera regla que case con cada genero crudo, y van de lo
 * mas concreto a lo mas general: «Action RPG» antes que «RPG», y «Action» la ultima, que casi
 * todo es accion. Y entre los de un mismo juego se enseña primero el de la regla mas alta. El
 * terror va el primero: es lo que mas dice de un juego y el unico genero que solo sabe Wikidata.
 */
internal object Genres {

    private val RULES: List<Pair<Regex, String>> = listOf(
        "survival horror|psychological horror|horror" to "Horror",
        "metroidvania" to "Metroidvania",
        "action role|action rpg|action-rpg" to "Action RPG",
        "tactical role|strategy role|tactical rpg|srpg" to "Tactical RPG",
        "role.?playing|\\brpg\\b|jrpg" to "RPG",
        "roguelike|roguelite" to "Roguelike",
        "visual novel" to "Visual Novel",
        "platform|run & jump|run and jump|jump'?n'?run" to "Platform",
        "shoot|shooter|fps|first.person|third.person|lightgun|shmup|\\bgun" to "Shooter",
        "fighting|versus|brawler" to "Fighting",
        "beat'?em|beat 'em|hack and slash|hack & slash|character.action" to "Beat 'em up",
        "action.adventure|action / adventure" to "Action-Adventure",
        "racing|driving" to "Racing",
        "sport|soccer|football|baseball|basketball|tennis|golf|hockey|wrestling|boxing" to "Sports",
        "puzzle|labyrinth|maze" to "Puzzle",
        "strategy|tactic" to "Strategy",
        "simulat" to "Simulation",
        "music|rhythm|danc" to "Music",
        "party|mini.?game" to "Party",
        "stealth" to "Stealth",
        "adventure|point.and.click" to "Adventure",
        "action" to "Action",
    ).map { (rx, name) -> Regex(rx, RegexOption.IGNORE_CASE) to name }

    /**
     * Los nombres propios de una lista de generos crudos, sin repetir, y del mas concreto al mas
     * general (el orden de la tabla), no en el que llegaron: Wikidata no los ordena, y para
     * Terranigma daba «role-playing video game» antes que «action role-playing game».
     */
    fun canon(raw: List<String>): List<String> {
        val found = sortedSetOf<Int>()
        for (r in raw) for (piece in r.split(',', '/')) {
            val s = piece.trim()
            if (s.isEmpty()) continue
            val at = RULES.indexOfFirst { (rx, _) -> rx.containsMatchIn(s) }
            if (at >= 0) found += at
        }
        return found.map { RULES[it].second }
    }

    /** Todos los nombres propios, del mas concreto al mas general: para elegir uno a mano. */
    val all: List<String> get() = RULES.map { it.second }

    /** El que se enseña en una linea: el mas concreto de todos, o el crudo si ninguno casa. */
    fun label(raw: List<String>): String? = canon(raw).firstOrNull() ?: raw.firstOrNull()?.replaceFirstChar(Char::uppercase)
}
