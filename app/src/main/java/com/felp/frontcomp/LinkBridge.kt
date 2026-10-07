package com.felp.frontcomp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

/**
 * Lo que Ludolog Link —otra app, firmada con la misma clave— le pide a Ludolog.
 *
 * Link gestiona los ROMs desde el PC sin servidor dentro de Ludolog, pero hay dos cosas que no
 * puede hacer por fuera. Una: los datos de un juego cuelgan de la RUTA de su ROM —favorito,
 * veces jugado, emulador, nombre, ficha, arte—, y un ROM renombrado los dejaria huerfanos. Dos:
 * la biblioteca solo se repasa al arrancar, y lo subido no saldria hasta reiniciar.
 *
 * El permiso del manifiesto es de firma: solo una app con la misma clave puede enviar esto.
 * Es un CONTRATO con otra app: ver docs/ludolog-link.md antes de cambiar acciones o extras.
 */
class LinkBridge : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val action = intent.action
        if (action == ACTION_STATE) {
            // Link se encendio o se apago: el LED de su radio (ver LinkSaveCheck.on).
            val on = intent.getBooleanExtra("on", false)
            val address = intent.getStringExtra("address").orEmpty()
            val peers = intent.getIntExtra("peers", LinkSaveCheck.peers.intValue)
            val pc = intent.getBooleanExtra("pcLink", LinkSaveCheck.pcLink.value)
            val sync = intent.getBooleanExtra("sync", LinkSaveCheck.sync.value)
            main.post { LinkSaveCheck.noteState(on, address, peers, pc, sync) }
            return
        }
        if (action == ACTION_RESTORE) {
            // Un respaldo esperando (LinkRestore): solo se pide el reinicio, que lo pone en su sitio.
            // Si este aviso levanto el proceso, LudologApp ya lo aplico y no queda nada.
            if (LinkRestore.pending()) configChanged.intValue++
            return
        }
        if (action == ACTION_EDITS_IN) {
            // Correcciones de otras consolas (ver LinkEdits): se aplican y la pantalla se repasa.
            val pending = goAsync()
            Thread {
                try {
                    if (LinkEdits.applyIncoming(ctx)) main.post { edited.intValue++ }
                } catch (e: Exception) {
                    android.util.Log.w("Ludolog", "correcciones de Link: ${e.message}")
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        if (action != ACTION_MOVED && action != ACTION_LIBRARY_CHANGED && action != ACTION_CONFIG_CHANGED &&
            action != ACTION_MEDIA_CHANGED) return
        val from = intent.getStringArrayExtra(EXTRA_FROM).orEmpty()
        val to = intent.getStringArrayExtra(EXTRA_TO).orEmpty()
        // Fuera del hilo principal, que esto toca disco; goAsync mantiene vivo el proceso mientras.
        val pending = goAsync()
        Thread {
            try {
                if (action == ACTION_CONFIG_CHANGED) {
                    // Ajustes nuevos desde el PC: los aplica Ludolog misma y luego se refresca.
                    if (LinkConfig.applyPending()) main.post { configChanged.intValue++ }
                    return@Thread
                }
                if (action == ACTION_MEDIA_CHANGED) mediaChanged(intent.getStringArrayExtra(EXTRA_PATHS).orEmpty())
                if (action == ACTION_MOVED && from.size == to.size) {
                    val prefs = Prefs(ctx)
                    for (i in from.indices) moved(prefs, from[i], to[i])
                }
                main.post { changes.intValue++ }
            } catch (e: Exception) {
                android.util.Log.w("Ludolog", "puente de Link: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_LIBRARY_CHANGED = "com.felp.frontcomp.link.LIBRARY_CHANGED"
        const val ACTION_MOVED = "com.felp.frontcomp.link.MOVED"
        const val ACTION_CONFIG_CHANGED = "com.felp.frontcomp.link.CONFIG_CHANGED"
        const val ACTION_RESTORE = "com.felp.frontcomp.link.RESTORE"
        const val ACTION_MEDIA_CHANGED = "com.felp.frontcomp.link.MEDIA_CHANGED"
        /** Link se encendio o se apago (extras `on`, `address`, `peers`, `pcLink`, `sync`). Ver LinkSaveCheck.on. */
        /** Correcciones de otras consolas en `link/edits-in.json` (ver LinkEdits). */
        const val ACTION_EDITS_IN = "com.felp.frontcomp.link.EDITS_IN"
        const val ACTION_STATE = "com.felp.frontcomp.link.STATE"

        /** De Ludolog A Link (al reves que las demas): el cuaderno de esta consola cambio. */
        const val ACTION_COMPANION_CHANGED = "com.felp.frontcomp.link.COMPANION_CHANGED"
        private const val LINK_PACKAGE = "com.felp.ludologlink"
        private const val PERMISSION = "com.felp.frontcomp.permission.LINK"
        const val EXTRA_FROM = "from"
        const val EXTRA_TO = "to"
        const val EXTRA_PATHS = "paths"

        /**
         * Le dice a Ludolog Link, si esta instalado y su servicio escuchando, que el cuaderno de
         * esta consola cambio: Link lo comparte con las consolas emparejadas. Solo a ese paquete y
         * solo a quien tenga el permiso de firma. Sin Link, o con su servicio parado, no llega a
         * nadie y no pasa nada: Ludolog no espera respuesta.
         */
        fun companionChanged(ctx: Context) {
            runCatching { ctx.sendBroadcast(Intent(ACTION_COMPANION_CHANGED).setPackage(LINK_PACKAGE), PERMISSION) }
        }

        /** De Ludolog a Link: se abrio / se cerro un juego en un emulador (partidas guardadas). */
        const val ACTION_GAME_OPENED = "com.felp.frontcomp.link.GAME_OPENED"
        const val ACTION_GAME_CLOSED = "com.felp.frontcomp.link.GAME_CLOSED"

        /** Lo que se apunta para Link en `<datos>/link/played.tsv`: como mucho estas lineas. */
        private const val PLAYED_KEEP = 1000

        /**
         * Un juego que se abre o se cierra en un emulador, para que Ludolog Link sincronice las
         * partidas guardadas de ESE emulador (por app: RetroArch es uno, con todos sus nucleos).
         *
         * Se apunta en `<datos>/link/played.tsv` —Link lo lee al encenderse, para saber que se jugo
         * mientras estaba apagado— y se avisa por si escucha. Las partidas cortas tambien: el
         * Companion las tira, pero pueden haber guardado. Sin Link no pasa nada: unas lineas de
         * texto que nadie lee, y como mucho [PLAYED_KEEP].
         *
         * Linea: `S|E <tab> instante <tab> paquete <tab> consola <tab> archivo <tab> inicio`.
         */
        fun gameOpened(ctx: Context, pkg: String?, system: String?, file: String?, at: Long) =
            game(ctx, ACTION_GAME_OPENED, "S", pkg, system, file, at, at)

        fun gameClosed(ctx: Context, pkg: String?, system: String?, file: String?, started: Long, ended: Long) =
            game(ctx, ACTION_GAME_CLOSED, "E", pkg, system, file, ended, started)

        private fun game(ctx: Context, action: String, kind: String, pkg: String?, system: String?, file: String?, at: Long, started: Long) {
            if (pkg.isNullOrEmpty()) return
            runCatching {
                val log = DataHome.file("link/played.tsv")
                log.parentFile?.mkdirs()
                fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ')
                log.appendText(listOf(kind, at.toString(), clean(pkg), clean(system), clean(file), started.toString()).joinToString("\t") + "\n")
                // Recortar de vez en cuando, no en cada linea: se lee entero solo si ya paso de largo.
                if (log.length() > 160_000) {
                    val lines = log.readLines().takeLast(PLAYED_KEEP)
                    val tmp = File(log.path + ".part")
                    tmp.writeText(lines.joinToString("\n", postfix = "\n"))
                    if (!tmp.renameTo(log)) { log.delete(); tmp.renameTo(log) }
                }
            }
            runCatching {
                ctx.sendBroadcast(Intent(action).setPackage(LINK_PACKAGE).putExtra("pkg", pkg).putExtra("system", system)
                    .putExtra("file", file).putExtra("started", started).putExtra("at", at), PERMISSION)
            }
        }

        /** Cuenta los avisos; la pantalla principal lo mira para repasar la biblioteca, como PinnedShortcuts. */
        val changes = mutableIntStateOf(0)

        /** Sube cuando se aplicaron correcciones de otras consolas (EDITS_IN): nombres y fichas se releen. */
        val edited = mutableIntStateOf(0)

        /** Sube cuando se aplicaron ajustes llegados del PC: la pantalla principal se refresca entera. */
        val configChanged = mutableIntStateOf(0)

        private val main = Handler(Looper.getMainLooper())

        /**
         * Las extensiones del arte que se nombra como el ROM (ver Scraper.destinationFor), y las de lo
         * que TapeQueue saca de un video: van tras el nombre ENTERO del video (`x.mp4.clean.m4a`).
         */
        private val ART_TAILS = listOf(
            "png", "jpg", "jpeg", "webp", "mp4",
            "mp4.clean", "mp4.clean.m4a", "mp4.split", "mp4.m4a", "mp4.digital", "mp4.digital.m4a", "mp4.tape",
        )

        /**
         * Arte o videos que Link puso, cambio o quito en `<datos>/media/`: lo mismo que hace el
         * scraper con lo suyo. El sonido sacado de un video anterior con ese nombre ya no vale
         * (TapeQueue.forget), y quien lo este pintando debe volver a leerlo (ArtRevisions).
         * Solo rutas dentro de la carpeta de medios. El repaso que sigue rehace el indice.
         */
        private fun mediaChanged(paths: Array<out String?>) {
            val media = DataHome.file("media").canonicalPath + File.separator
            for (p in paths.filterNotNull()) {
                val f = File(p)
                if (!f.canonicalPath.startsWith(media)) continue
                if (f.name.endsWith(".mp4", ignoreCase = true)) TapeQueue.forget(f)
                ArtRevisions.bump(f)
            }
        }

        /**
         * Un ROM que paso de [from] a [to]: lo suyo va con el.
         *
         * Primero la ficha, porque dice de que consola es el juego para Ludolog —puede no ser la
         * carpeta—, y con eso se sabe en que carpeta de medios esta su arte.
         */
        fun moved(prefs: Prefs, from: String, to: String) {
            if (from == to) return
            prefs.moveGame(from, to)
            val system = Dossiers.moved(from, to) ?: File(from).parentFile?.name
            if (system != null) moveArt(system, File(from).name, File(to).name)
        }

        private fun moveArt(system: String, oldName: String, newName: String) {
            val oldStem = oldName.substringBeforeLast('.')
            val newStem = newName.substringBeforeLast('.')
            if (oldStem == newStem) return
            val kinds = DataHome.file("media/$system").listFiles()?.filter { it.isDirectory } ?: return
            for (kind in kinds) for (tail in ART_TAILS) {
                val a = File(kind, "$oldStem.$tail")
                if (!a.isFile) continue
                val b = File(kind, "$newStem.$tail")
                if (!b.exists()) a.renameTo(b)
            }
        }
    }
}
