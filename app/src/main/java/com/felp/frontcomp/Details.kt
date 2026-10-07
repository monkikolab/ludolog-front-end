package com.felp.frontcomp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch

// Lo que se cuenta de una consola o un juego en la ficha y en la tarjeta. Salio de MainActivity.kt el
// 07-10-2026.

/**
 * Lo que se lee bajo el emblema cuando el cursor esta en el cuaderno.
 *
 * Con las cifras que ya tiene delante —partidas y tiempo— y no con una frase de folleto: lo
 * que hace falta saber para decidir si entrar es si hay algo escrito dentro.
 */
internal fun reckoningDetails(vm: LibraryViewModel, name: String): Details {
    // Del cuaderno ya leido para las tarjetas, no de un contador aparte. Lo era, y solo sumaba
    // las partidas de este programa: con el cuaderno traido de RetroCompanion decia «3 sessions»
    // sobre 47.
    val sessions = vm.played?.sessions ?: 0
    return Details(
        title = name,
        text = "How long you have played, game by game and console by console, and what " +
            "each session cost you in battery, in heat and in frames.",
        // Corto: en la letra de pixeles de la sala caben unos treinta y cinco caracteres, y
        // «49 sessions written down  ·  L2 + R2» ya se quedaba en «L2 +».
        footer = if (sessions <= 0) "no sessions yet  ·  L2 + R2" else
            "$sessions ${if (sessions == 1) "session" else "sessions"}  ·  L2 + R2",
    )
}

/**
 * Lo que se lee bajo la radio cuando el cursor esta en Ludolog Link: si escucha, y con cuantos
 * devices. Corto, por la letra de pixeles de la sala (unos treinta y cinco caracteres de pie).
 */
internal fun linkDetails(): Details {
    val on = LinkSaveCheck.on.value
    val n = LinkSaveCheck.peers.intValue
    val pc = LinkSaveCheck.pcLink.value
    return Details(
        title = "Ludolog Link",
        text = when {
            n > 0 && !LinkSaveCheck.sync.value -> "Sharing off."
            on && pc -> "Listening. PC Link on at ${LinkSaveCheck.address.value}."
            on && n > 0 -> "Listening to devices. Saves and sessions are shared."
            on -> "Listening. Pair a device to share."
            n > 0 -> "Asleep. Wakes when you come back here."
            else -> "Open it to pair your devices and PC."
        },
        footer = (if (on) "on" else "off") + (if (pc) " · PC Link" else "") + "  ·  " + when (n) {
            0 -> "no devices paired"
            1 -> "1 device paired"
            else -> "$n devices paired"
        },
    )
}

internal fun LibraryViewModel.systemDetails(id: String): Details {
    val sys = catalog?.byId?.get(id)
    val games = gamesOf(id)
    val withArt = art?.let { a -> games.count(a::has) } ?: 0
    return Details(
        title = displayName(sys, id),
        text = prefs.description("sys", id) ?: sys?.description.orEmpty().ifEmpty { "${gameCount(games.size)} in the library." },
        footer = "${gameCount(games.size)}  ·  $withArt with art",
    )
}

internal fun LibraryViewModel.gameDetails(g: Game): Details {
    val sys = catalog?.byId?.get(g.systemId)
    val n = prefs.playCount(g)
    val pkg = g.appPackage
    return Details(
        title = prefs.gameTitle(g) ?: g.title,
        // Un juego de Android no tiene fichero del que hablar, así que se cuenta lo que se
        // escribió de él en el cajón de apps, que es donde se ordenó.
        text = prefs.description("game", g.path)
            ?: pkg?.let { prefs.description("app", it) ?: it }
            ?: buildString {
                append(g.fileName)
                g.region?.let { append("  ·  ").append(it.uppercase()) }
                if (n > 0) append("\nPlayed $n ${if (n == 1) "time" else "times"}.")
            },
        footer = "${displayName(sys, g.systemId)}  ·  ${gameCount(gamesOf(g.systemId).size)}",
    )
}

/**
 * La tarjeta de una consola en la lista: lo que el Companion sabe de ella, o su historia si
 * esta apagado.
 *
 * Aparte de systemDetails() y no dentro: aquel es el texto PROPIO de la consola, el que se
 * edita desde su menu, y editar una descripcion no puede empezar con las horas jugadas
 * escritas en la caja. El pie es el de siempre, que habla de la biblioteca y no del cuaderno.
 */
internal fun LibraryViewModel.systemCard(id: String): Details {
    val d = systemDetails(id)
    val book = played?.takeIf { prefs.logbook } ?: return d
    // Los favoritos no son una consola del cuaderno: lo suyo se suma juego a juego, cada uno
    // en la suya. Buscados como consola decian «Nothing played on this console yet».
    if (id == FAVORITES_SYSTEM) {
        val favs = favorites()
        val tallies = favs.mapNotNull { g -> book.game(g.systemId, g.fileName, bookTitle(g))?.let { g to it } }
        val top = tallies.maxByOrNull { it.second.totalMs }
        return d.copy(
            text = if (top == null) "Your favourite games, from every console. None played yet."
            else buildString {
                append(span(tallies.sumOf { it.second.totalMs })).append(" played across ")
                append(gameCount(favs.size)).append(".")
                append("\nLast played ").append(ago(tallies.maxOf { it.second.lastAt })).append('.')
                append("\nMost played: ").append(displayTitle(top.first)).append('.')
            },
        )
    }
    val games = book.console(id)
    // El mas jugado con el nombre que tiene HOY en la lista, que es la que se esta mirando; si
    // ya no esta en ella, con el de su ultima partida.
    val top = games.firstOrNull()?.let { best ->
        gamesOf(id).firstOrNull { fileKey(it.fileName) in best.files }?.let { displayTitle(it) } ?: best.name
    }
    return d.copy(text = consoleText(games, top))
}

/** La de un juego, igual: lo que dice el Companion, o su texto si esta apagado. */
internal fun LibraryViewModel.gameCard(g: Game): Details {
    val d = gameDetails(g)
    val book = played?.takeIf { prefs.logbook } ?: return d
    // Por su fichero, y si no por el nombre con que se lanza, que es como lo apunta el cuaderno.
    return d.copy(text = gameText(book.game(g.systemId, g.fileName, bookTitle(g)), chargeUah))
}

/**
 * Lo jugado en una consola, en tres renglones: cuanto, cuando y a que.
 *
 * Un renglon por dato y el nombre del juego al final, que es lo unico largo: en una caja de
 * tres renglones, si algo se corta, que sea el final de un titulo y no una cifra.
 */
private fun consoleText(games: List<LogStats.GamePlayed>, top: String?): String {
    if (games.isEmpty() || top == null) {
        return "Nothing played on this console yet. The Companion writes down every game " +
            "you launch from here."
    }
    val sessions = games.sumOf { it.tally.sessions }
    return buildString {
        append(span(games.sumOf { it.tally.totalMs })).append(" played over ")
        append(count(sessions, "session")).append(", ").append(count(games.size, "game")).append('.')
        append("\nLast played ").append(ago(games.maxOf { it.tally.lastAt })).append('.')
        append(if (games.size == 1) "\nOnly game played: " else "\nMost played: ")
        append(top).append('.')
    }
}

/** Lo jugado en un juego: cuanto, cuando y lo que le dura la bateria. */
private fun gameText(t: LogStats.Tally?, chargeUah: Long?): String {
    if (t == null) {
        return "Not played yet. The Companion starts counting the first time you launch it " +
            "from here."
    }
    return buildString {
        append(span(t.totalMs)).append(" played")
        if (t.sessions == 1) append(" in one session.")
        else append(" over ").append(t.sessions).append(" sessions, about ")
            .append(span(t.totalMs / t.sessions)).append(" each.")
        append("\nLast played ").append(ago(t.lastAt)).append('.')
        // Con la carga de AHORA, como la tarjeta de al empezar a jugar: lo que se quiere saber
        // al elegir un juego es si llega la bateria que queda. Y sin estimacion no hay renglon:
        // una cifra inventada es peor que callarse.
        t.hoursLeft(chargeUah)?.let {
            append("\nAbout ").append(span((it * 3_600_000f).toLong()))
                .append(" of play left on this charge.")
        }
    }
}

private fun count(n: Int, what: String) = if (n == 1) "one $what" else "$n ${what}s"

/** «1 game», «18 games»: la cuenta de los pies y las cabeceras, en cifra. Salia «1 games». */
internal fun gameCount(n: Int) = if (n == 1) "1 game" else "$n games"

/**
 * Cuando, dicho como se dice: hoy, ayer, hace unos dias, o la fecha.
 *
 * Por dias de calendario y no por horas: lo jugado anoche a las once es de ayer aunque sea
 * la una de la tarde. Y la fecha en ingles, como el resto del texto; con el idioma del
 * aparato saldria «Last played on 12 sept.».
 */
private fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val zone = java.util.TimeZone.getDefault()
    fun day(t: Long) = (t + zone.getOffset(t)) / 86_400_000L
    val days = day(now) - day(at)
    val sameYear = java.util.Calendar.getInstance().run {
        timeInMillis = now; val y = get(java.util.Calendar.YEAR)
        timeInMillis = at; get(java.util.Calendar.YEAR) == y
    }
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 7L -> "$days days ago"
        else -> "on " + java.text.SimpleDateFormat(
            if (sameYear) "d MMM" else "d MMM yyyy", java.util.Locale.ENGLISH,
        ).format(java.util.Date(at))
    }
}


/**
 * Las primeras frases de una sinopsis, hasta unos 220 caracteres: lo que cabe en la tarjeta, que
 * en los temas de instrumento tiene tres renglones. Cortada en un punto y no a media palabra; si
 * la primera frase ya es mas larga, se corta en una palabra y lleva puntos suspensivos. Entera
 * sigue en GAME INFO.
 */
internal fun shortSynopsis(s: String, max: Int = 220): String {
    val text = s.replace(Regex("""\s+"""), " ").trim()
    if (text.length <= max) return text
    val sentences = Regex("""(?<=[.!?])\s+""").split(text)
    val out = StringBuilder()
    for (sentence in sentences) {
        if (out.length + sentence.length + 1 > max) break
        if (out.isNotEmpty()) out.append(' ')
        out.append(sentence)
    }
    if (out.isNotEmpty()) return out.toString()
    return text.take(max).substringBeforeLast(' ').trimEnd(',', ';', ':') + "…"
}

