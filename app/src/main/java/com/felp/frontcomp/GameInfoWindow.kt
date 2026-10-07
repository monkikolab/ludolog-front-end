package com.felp.frontcomp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lo que se sabe de un juego y de donde sale: su ficha (Dossiers), leida.
 *
 * La sinopsis arriba, que es lo que se viene a leer, con su procedencia debajo: la licencia de
 * Wikipedia pide decir de que articulo sale, y GameTDB, que se la cite. Despues una fila por
 * dato, y en la de abajo el porque de cada uno —que fuente lo dijo, y si la identidad es segura
 * o solo un parecido—, que es lo que hace falta para fiarse o para corregirlo.
 */
@Composable
internal fun GameInfoWindow(vm: LibraryViewModel, game: Game, onClose: () -> Unit) {
    val d = remember(game.path, vm.dossierRevision) { Dossiers.get(game) }
    val rows = remember(d) { infoRows(vm, game, d) }
    var selected by remember { mutableStateOf(0) }
    val sel = selected.coerceIn(0, rows.lastIndex)
    val title = (vm.prefs.gameTitle(game) ?: game.title).uppercase()
    ModalWindow(onDismiss = onClose, widthFraction = headerFraction(title, "GAME INFO", "B  close", min = 0.62f), heightFraction = 0.86f) {
        WindowFrame(title = title, subtitle = "GAME INFO", description = rows[sel].blurb, hint = "B  close") {
            Column {
                d?.synopsis?.let { story ->
                    Spacer(Modifier.height(6.dp))
                    Text(story, color = MenuInk, fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = MenuBody,
                        maxLines = 6, overflow = TextOverflow.Ellipsis)
                    d["syn.from"]?.let { from ->
                        val credit = if (d["syn.src"] == "gt") "From GameTDB, «$from», gametdb.com"
                            else "From Wikipedia, «$from», CC BY-SA 4.0"
                        Text(credit, color = MenuFaint, fontSize = 10.sp,
                            fontFamily = MenuBody, modifier = Modifier)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ActRows(rows, sel) { selected = it }
            }
        }
    }
}

/** Las filas de la ficha: cada dato, con su fuente en la explicacion. */
private fun infoRows(vm: LibraryViewModel, game: Game, d: Dossier?): List<Act> {
    val out = mutableListOf<Act>()
    fun row(label: String, value: String, blurb: String) { out += Act(label, blurb, value = value) {} }
    if (d == null || (d["name"] == null && d.keys.isEmpty())) {
        row("Not read yet", "",
            if (vm.prefs.catalogOn) "Ludolog reads each game after a scan and looks it up in its catalog. " +
                "Scan the library, or update the catalog in Settings, Library."
            else "The game catalog is off. Turn it on in Settings, Library, to know what this game is.")
        return out
    }
    val name = d["name"]
    val how = d["how"]
    if (name == null) {
        row("Identity", "unknown",
            if (how == "none" && d["cat"] != null) "Not in the catalog: nothing inside the file or in its name matched a game it knows."
            else "Read from the file, but not looked up yet: the catalog has not been downloaded.")
    } else {
        row("Identity", if (d.exact) "exact" else "by title", when (how) {
            "crc" -> "$name. Found by the checksum of the file: it is exactly this version."
            "sha1" -> "$name. Found by the fingerprint the disc image carries: exactly this version."
            "serial" -> "$name. Found by the serial number written on the disc."
            "gameid" -> "$name. Found by the ID written on the disc."
            "romset" -> "$name. Found by the name of its romset."
            "name" -> "$name. The file is named exactly as in the catalog."
            else -> "$name. Only its title matches, so it could be another version of the game."
        })
    }
    d.realSystem?.let { real ->
        val here = vm.catalog?.byId?.get(game.systemId)?.name ?: game.systemId
        val there = vm.catalog?.byId?.get(real)?.name ?: real
        row("Console", there, "The file is in the $here folder, but it is a $there game. Its art comes from $there.")
    }
    val genres = d.genres
    if (genres.isNotEmpty()) {
        val from = listOfNotNull(
            d["g.wd"]?.let { "Wikidata: ${it.replace("|", ", ")}" },
            d["g.gt"]?.let { "GameTDB: ${it.replace("|", ", ")}" },
            d["g.lr"]?.let { "libretro: ${it.replace("|", ", ")}" },
            d["my.genre"]?.let { "yours: ${it.replace("|", ", ")}" },
        )
        row("Genre", Genres.canon(genres).take(2).joinToString(", ").ifEmpty { genres.first() }, from.joinToString(". ") + ".")
    }
    val src = d["src"].orEmpty()
    fun source(at: Int) = when (src.getOrNull(at)) { 'w' -> "Wikidata"; 'l' -> "libretro"; 'g' -> "GameTDB"; else -> null }
    d["date"]?.let { date ->
        row("Released", pretty(date), "The first release anywhere" + (source(0)?.let { ", from $it." } ?: "."))
    }
    d["dev"]?.let { row("Developer", it.replace("|", ", "), "Who made it" + (source(1)?.let { ", from $it." } ?: ".")) }
    d["pub"]?.let { row("Publisher", it.replace("|", ", "), "Who put it out" + (source(2)?.let { ", from $it." } ?: ".")) }
    d["series"]?.let { row("Series", it.replace("|", ", "), "The saga it belongs to" + (source(3)?.let { ", from $it." } ?: ".")) }
    name?.let { n ->
        val langs = GameDb.languagesOf(n)
        if (langs.isNotEmpty()) row("Languages", langs.joinToString(", "), "The languages its catalog name lists.")
    }
    d["fame"]?.toIntOrNull()?.let { n ->
        row("Fame", "$n Wikipedias", "How many Wikipedias have an article about it. A hidden gem has few.")
    }
    return out
}

/** «1995-10-20» -> «20 Oct 1995»; «1994-03» -> «Mar 1994»; el año, tal cual. */
private fun pretty(date: String): String {
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val p = date.split('-')
    val m = p.getOrNull(1)?.toIntOrNull()?.let { months.getOrNull(it - 1) }
    return when {
        p.size >= 3 && m != null -> "${p[2].trimStart('0')} $m ${p[0]}"
        m != null -> "$m ${p[0]}"
        else -> p[0]
    }
}
