package com.felp.frontcomp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import java.io.File
import java.util.EnumMap

/**
 * The kinds of artwork a game can have, in the order the grid prefers them.
 *
 * Names match the ES-DE layout on purpose: the user already has a scraped media folder and
 * it should light up on first run, rather than making them re-download art we can just read.
 */
enum class ArtKind(val folder: String) {
    COVER("covers"),
    BOX3D("3dboxes"),
    MIXIMAGE("miximages"),
    TITLESCREEN("titlescreens"),
    SCREENSHOT("screenshots"),
    MARQUEE("marquees"),
    FANART("fanart"),
}

/**
 * Finds the image file for a game.
 *
 * Built by listing directories once rather than by asking the filesystem per game: a
 * library of a few thousand games against half a dozen art kinds is tens of thousands of
 * stat() calls, which is the difference between a scan that feels instant and one that
 * does not.
 */
class ArtIndex private constructor(
    private val byKey: Map<String, Map<ArtKind, File>>,
    /**
     * Gameplay videos, kept apart from the images.
     *
     * Not an ArtKind because nothing that picks artwork should ever fall back to a video:
     * a panel that wanted a cover and got an MP4 has no way to draw it.
     */
    private val videoByKey: Map<String, File>,
    val roots: List<File>,
    val fileCount: Int,
) {
    fun find(game: Game, prefer: ArtKind = ArtKind.COVER): File? {
        val forGame = byKey[key(game.systemId, game.fileName.substringBeforeLast('.'))]
            ?: byKey[key(game.systemId, game.title)]
            ?: return null
        return forGame[prefer] ?: ArtKind.entries.firstNotNullOfOrNull { forGame[it] }
    }

    fun has(game: Game): Boolean = find(game) != null

    /** The gameplay recording for a game, if one was ever scraped. */
    fun video(game: Game): File? =
        videoByKey[key(game.systemId, game.fileName.substringBeforeLast('.'))]
            ?: videoByKey[key(game.systemId, game.title)]

    companion object {
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")
        private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "avi")

        /** ES-DE and Daijishō both write gameplay clips here. */
        private const val VIDEO_FOLDER = "videos"

        private fun key(systemId: String, name: String) =
            systemId.lowercase() + "/" + SystemDef.key(name)

        @Volatile private var rootsCache: List<File>? = null

        /** Olvida las raices guardadas. Se llama al reescanear, que es cuando pueden cambiar. */
        fun invalidateRoots() { rootsCache = null }

        /**
         * Where scraped media usually lives, including the folders other frontends write.
         *
         * El resultado se guarda. Averiguarlo cuesta un listado de /storage y una decena de
         * stat, y esta funcion es el valor por defecto de siete llamadas distintas: recorrer
         * el carrusel de consolas la invocaba tres veces por cada movimiento del cursor,
         * desde dentro de un composable y en el hilo de la interfaz.
         *
         * Las unidades de almacenamiento no aparecen solas mientras la aplicacion esta
         * delante, y las carpetas de medios nuevas las crea el propio scraper: lo guardado se
         * tira al reescanear y al terminar de bajar arte (ver LibraryViewModel.freshIndex).
         */
        fun defaultRoots(): List<File> = rootsCache ?: findRoots().also { rootsCache = it }

        private fun findRoots(): List<File> {
            val volumes = DataHome.volumes()
            // La carpeta del TEMA puesto, delante de todo.
            //
            // Es lo que separa un tema de otro. Con una sola carpeta de medios para todos,
            // instalar uno pisaba los medios del anterior y elegir otro en ajustes cambiaba la
            // letra y el color mientras las consolas, los medallones y la sala seguian siendo
            // del vecino. Delante y no en vez de: la comun se queda detras, asi que un tema sin
            // medios propios hereda los que haya y una instalacion vieja sigue funcionando
            // igual hasta que se reinstale.
            val mine = ThemeFiles.media()
            // Y detras, la comun de la carpeta de datos, que es donde escribe el scraper. Una
            // sola: esta en una unidad concreta, no se busca en cada una como las de los demas
            // programas.
            val common = DataHome.file("media")
            val names = listOf(
                "ES-DE/downloaded_media",
                "Emulation/downloaded_media",
                "media",
            )
            val seen = HashSet<String>()
            return (listOfNotNull(mine, common) + volumes.flatMap { vol -> names.map { File(vol, it) } })
                .filter { it.isDirectory }
                .filter { seen.add(runCatching { it.canonicalPath }.getOrDefault(it.path).lowercase()) }
        }

        /**
         * Expects <root>/<systemId>/<artKind>/<rom name>.png, and also accepts art dropped
         * straight into <root>/<systemId>/ for people who keep a flat folder.
         */
        fun build(roots: List<File> = defaultRoots()): ArtIndex {
            val acc = HashMap<String, EnumMap<ArtKind, File>>()
            val vids = HashMap<String, File>()
            var count = 0

            fun put(systemId: String, file: File, kind: ArtKind) {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                if (ext !in IMAGE_EXT) return
                val k = key(systemId, file.name.substringBeforeLast('.'))
                acc.getOrPut(k) { EnumMap(ArtKind::class.java) }.putIfAbsent(kind, file)
                count++
            }

            fun putVideo(systemId: String, file: File) {
                val ext = file.name.substringAfterLast('.', "").lowercase()
                if (ext !in VIDEO_EXT) return
                vids.putIfAbsent(key(systemId, file.name.substringBeforeLast('.')), file)
            }

            for (root in roots) {
                root.listFiles()?.forEach { sysDir ->
                    if (!sysDir.isDirectory) return@forEach
                    val systemId = sysDir.name
                    sysDir.listFiles()?.forEach { entry ->
                        if (entry.isDirectory) {
                            if (entry.name == VIDEO_FOLDER) {
                                entry.listFiles()?.forEach { putVideo(systemId, it) }
                                return@forEach
                            }
                            val kind = ArtKind.entries.firstOrNull { it.folder == entry.name }
                                ?: return@forEach
                            entry.listFiles()?.forEach { put(systemId, it, kind) }
                        } else {
                            put(systemId, entry, ArtKind.COVER)
                        }
                    }
                }
            }
            return ArtIndex(acc, vids, roots, count)
        }
    }
}

/**
 * The picture of the console itself, for the preview panel.
 *
 * Deliberately loaded from disk rather than shipped: console photos are somebody's work,
 * and which set to use is the user's choice. Drop files named after the system id into
 * <mediaRoot>/systems/ and they appear.
 */
/**
 * Lo que es del cuaderno y no de ninguna consola: su emblema, su icono, su fondo.
 *
 * Carpeta propia —`<medios>/companion/`— y no dentro de `systems/`. El cuaderno no es una
 * consola: no tiene juegos, no se escanea, no se renombra. Metiendo su giro entre los de las
 * consolas acabaria saliendo en sitios donde se enumeran consolas, que es justo lo que no es.
 */
object CompanionArt {
    fun file(name: String, roots: List<File> = ArtIndex.defaultRoots()): File? {
        for (root in roots) {
            File(root, "companion/$name").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    fun spin(): File? = file("reckoning.mp4")
    fun icon(): File? = file("reckoning-icon.png")
    fun background(): File? = file("background.jpg")

    /**
     * El avatar del propio cuaderno, si el tema lo trae: `avatar.mp4`, o una imagen quieta con
     * el mismo nombre para un tema sin movimiento. Donde lo hay, ocupa dentro del cuaderno el
     * sitio del aparato girando. Se pide por extensiones, las mismas que el del aparato.
     */
    fun avatar(kinds: List<String>): File? = kinds.firstNotNullOfOrNull { file("avatar.$it") }
}

/**
 * Lo de Ludolog Link: el giro de su radio, con el LED en verde (escuchando) o en rojo (apagado).
 * Carpeta propia, `<medios>/link/`, por lo mismo que el cuaderno: no es una consola.
 */
object LinkArt {
    fun file(name: String, roots: List<File> = ArtIndex.defaultRoots()): File? {
        for (root in roots) {
            File(root, "link/$name").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    fun spin(on: Boolean): File? = file(if (on) "radio-on.mp4" else "radio-off.mp4")
}

/**
 * El render que el usuario eligió para cada consola, cuando no quiere el suyo.
 *
 * Se guarda el NOMBRE del render —el de otra consola— y no un fichero: cada tema trae los
 * suyos, y así la elección vale en todos, cada uno con su versión de esa máquina. Es estado de
 * Compose para que quien pinta la consola se entere al momento de que ha cambiado.
 */
object RenderChoices {
    private val chosen = mutableStateMapOf<String, String>()

    fun load(all: Map<String, String>) = Snapshot.withMutableSnapshot {
        chosen.clear()
        chosen.putAll(all)
    }

    fun of(systemId: String): String? = chosen[systemId]

    fun set(systemId: String, render: String?) = Snapshot.withMutableSnapshot {
        if (render == null) chosen.remove(systemId) else chosen[systemId] = render
    }
}

object SystemArt {
    private val EXTS = listOf("png", "webp", "jpg", "jpeg")
    private val VIDEO_EXTS = listOf("mp4", "webm", "mkv")

    /**
     * Los renders que hay, por nombre: lo que se puede elegir para una consola. Los de la
     * carpeta del tema primero, y sin repetir los que tambien estan en la comun.
     */
    fun renders(roots: List<File> = ArtIndex.defaultRoots()): List<String> =
        roots.flatMap { root -> File(root, "systems").listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.extension.lowercase() in VIDEO_EXTS + EXTS }
            .map { it.nameWithoutExtension }
            .distinct()

    /**
     * El render elegido, si lo hay y este tema lo trae en algun formato. Si lo trae, manda
     * del todo —su foto aunque la consola propia tenga giro—, y si no, se queda la propia: un
     * tema sin esa maquina no puede dejar la consola sin nada.
     */
    private fun chosen(choice: String?, roots: List<File>): String? =
        choice?.takeIf { inSystems(it, VIDEO_EXTS + EXTS, roots) != null }

    private fun inSystems(stem: String, exts: List<String>, roots: List<File>): File? {
        for (root in roots) {
            val dir = File(root, "systems")
            if (!dir.isDirectory) continue
            for (ext in exts) File(dir, "$stem.$ext").takeIf { it.isFile }?.let { return it }
        }
        return null
    }

    /**
     * The console turning on itself, if somebody rendered one.
     *
     * Looked up exactly like the still picture, so a folder that already has
     * systems/snes.png can gain systems/snes.mp4 and it just appears.
     *
     * @param choice el render elegido para esta consola; null para la suya.
     */
    fun video(
        systemId: String,
        video: String = "",
        roots: List<File> = ArtIndex.defaultRoots(),
        choice: String? = RenderChoices.of(systemId),
    ): File? {
        chosen(choice, roots)?.let { return inSystems(it, VIDEO_EXTS, roots) }
        if (video.isNotEmpty()) {
            File(video).takeIf { it.isAbsolute && it.isFile }?.let { return it }
            for (root in roots) File(root, video).takeIf { it.isFile }?.let { return it }
        }
        for (root in roots) {
            val dir = File(root, "systems")
            if (!dir.isDirectory) continue
            for (ext in VIDEO_EXTS) {
                val f = File(dir, "$systemId.$ext")
                if (f.isFile) return f
            }
        }
        return null
    }

    fun find(
        systemId: String,
        image: String = "",
        roots: List<File> = ArtIndex.defaultRoots(),
        choice: String? = RenderChoices.of(systemId),
    ): File? {
        chosen(choice, roots)?.let { return inSystems(it, EXTS, roots) }
        // La ruta declarada en el TOML manda; si es absoluta se usa tal cual.
        if (image.isNotEmpty()) {
            File(image).takeIf { it.isAbsolute && it.isFile }?.let { return it }
            for (root in roots) File(root, image).takeIf { it.isFile }?.let { return it }
        }
        for (root in roots) {
            val dir = File(root, "systems")
            if (!dir.isDirectory) continue
            for (ext in EXTS) {
                val f = File(dir, "$systemId.$ext")
                if (f.isFile) return f
            }
        }
        return null
    }
}

/**
 * What spins in the app drawer: a phone for any app, and a medallion per emulator, rendered
 * from its icon by the asset pipeline. Looked up like the consoles' turntables, under
 * apps/ in any media folder, so dropping apps/<package>.mp4 there is all it takes.
 */
object AppArt {
    private val VIDEO_EXTS = listOf("mp4", "webm", "mkv")

    fun video(pkg: String, roots: List<File> = ArtIndex.defaultRoots()): File? = lookup(pkg, roots)

    fun phone(roots: List<File> = ArtIndex.defaultRoots()): File? = lookup("phone", roots)

    private fun lookup(name: String, roots: List<File>): File? {
        for (root in roots) {
            val dir = File(root, "apps")
            if (!dir.isDirectory) continue
            for (ext in VIDEO_EXTS) {
                val f = File(dir, "$name.$ext")
                if (f.isFile) return f
            }
        }
        return null
    }
}

/**
 * Las carátulas cambiadas a mano en esta sesión, y cuántas veces.
 *
 * El cargador de imágenes guarda lo que ya pintó con la ruta del fichero por clave, y cambiar
 * el fichero no cambia la ruta: la carátula elegida se quedaba debajo de la vieja hasta
 * reiniciar. Coil sabe meter la fecha del fichero en la clave, pero eso es leer el disco por
 * cada imagen, y en el hilo de la interfaz, para algo que pasa una vez de cada mil. Así que se
 * apunta aquí lo que se cambió, y solo eso se pide con otra clave: ver [artModel].
 */
internal object ArtRevisions {
    private val bumped = mutableStateMapOf<String, Int>()

    /** Desde cualquier hilo: el scraper escribe fuera del de la interfaz. */
    fun bump(file: File) = Snapshot.withMutableSnapshot {
        bumped[file.path] = (bumped[file.path] ?: 0) + 1
    }

    fun of(file: File): Int = bumped[file.path] ?: 0
}

/**
 * Lo que se le da a AsyncImage para pintar una carátula: el fichero tal cual o, si se cambió
 * en esta sesión, pedido con su versión en la clave. Leer la versión aquí es lo que hace que
 * quien la pinta se entere del cambio y la vuelva a pedir.
 */
@Composable
internal fun artModel(file: File?): Any? {
    if (file == null) return null
    val rev = ArtRevisions.of(file)
    if (rev == 0) return file
    val ctx = LocalPlatformContext.current
    return remember(file.path, rev) {
        ImageRequest.Builder(ctx).data(file).memoryCacheKey("${file.path}#$rev").build()
    }
}
