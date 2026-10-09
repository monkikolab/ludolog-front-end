package com.felp.frontcomp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import java.io.File

/**
 * Where an app is filed.
 *
 * Three, not two, because an Android game is neither: it belongs in the library next to
 * the ROMs, under its own console, rather than in the drawer at all.
 */
enum class AppSlot(val key: String, val label: String) {
    APP("app", "Apps"), EMULATOR("emu", "Emulators"), GAME("game", "Android games");

    companion object {
        fun of(key: String?): AppSlot? = entries.firstOrNull { it.key == key }
    }
}

/** An installed app that can be launched. */
data class AppEntry(
    val label: String,
    val pkg: String,
    /** Which tab of the drawer it lives in, or the library when it is a game. */
    val slot: AppSlot,
    /** Taken out of the drawer by the user; still listed for Settings to bring it back. */
    val hidden: Boolean = false,
    /* What the package says about itself, for the default description. */
    val version: String = "",
    val category: String = "",
    /** The developer's own android:description, which almost nobody writes. */
    val blurb: String = "",
    val updated: Long = 0L,
    val bytes: Long = 0L,
) {
    val isEmulator: Boolean get() = slot == AppSlot.EMULATOR

    /**
     * The app as a library entry, for the Android games console.
     *
     * A scheme instead of a path: everything downstream keys games by path, and `app://`
     * is both a valid key and an obvious signal to whoever launches it that there is no
     * file here.
     */
    fun asGame(): Game = Game(path = "$APP_SCHEME$pkg", systemId = ANDROID_SYSTEM, title = label)
}

/** The console the apps filed as games appear under. */
const val ANDROID_SYSTEM = "android"

/**
 * Los favoritos, como una consola mas al principio de la lista: todos los juegos marcados, de
 * cualquier consola. No es una consola del catalogo; cada juego sigue siendo de la suya, y con
 * ella se lanza.
 */
const val FAVORITES_SYSTEM = "favorites"

/**
 * La fila de los ultimos jugados, arriba de todo (09-10-2026): lo que se quiere al encender casi
 * siempre es seguir con lo de ayer. Sale del Companion, asi que con el apagado no esta.
 */
const val RECENT_SYSTEM = "recent"

const val APP_SCHEME = "app://"

val Game.appPackage: String? get() = path.removePrefix(APP_SCHEME).takeIf { it != path }

/**
 * The installed apps, for the launcher window.
 *
 * A retro handheld is still an Android device: the emulators, a browser, a file manager
 * and a store all have to be reachable without dropping back to the system launcher,
 * because on these devices the frontend usually *is* the launcher.
 */
object AppsRepo {

    /**
     * Tools the front end uses for itself and never lists. Activity Launcher is how a
     * specific activity gets opened by hand when something needs it; as a tile in the
     * drawer it would only confuse.
     */
    private val INTERNAL = setOf("de.szalkowski.activitylauncher")

    /** Where the rasterised icons go, for whoever renders medallions out of them. */
    val iconDir: File get() = DataHome.file("icons")

    /**
     * Apps with a launcher entry, minus our own and the internal tools.
     *
     * Emulators are told apart from the rest, not hidden: they are the ones most often
     * opened on purpose, to change a setting or manage saves. The emulator table decides
     * which is which, and the user's own filing (see Prefs.appSlot) wins over the table.
     */
    fun list(ctx: Context, emulators: Emulators?, prefs: Prefs? = null): List<AppEntry> {
        val pm = ctx.packageManager
        val emuPkgs = emulators?.defs.orEmpty()
            .flatMap { it.packages() }
            .toHashSet()

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == ctx.packageName || pkg in INTERNAL) return@mapNotNull null
                val ai = info.activityInfo.applicationInfo
                val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                AppEntry(
                    label = info.loadLabel(pm).toString(),
                    pkg = pkg,
                    slot = AppSlot.of(prefs?.appSlot(pkg))
                        ?: if (pkg in emuPkgs) AppSlot.EMULATOR else AppSlot.APP,
                    hidden = prefs?.isAppHidden(pkg) == true,
                    version = pi?.versionName.orEmpty(),
                    category = runCatching {
                        ApplicationInfo.getCategoryTitle(ctx, ai.category)?.toString()
                    }.getOrNull().orEmpty(),
                    blurb = runCatching { ai.loadDescription(pm)?.toString() }.getOrNull().orEmpty(),
                    updated = pi?.lastUpdateTime ?: 0L,
                    bytes = runCatching { File(ai.sourceDir).length() }.getOrDefault(0L),
                )
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * What can be said about an app from its package alone: the developer's blurb if there
     * is one, then category, version and size, then when it was last updated. It is the
     * default; the user's own text, when they write one, replaces it.
     */
    fun describe(e: AppEntry): String = buildString {
        if (e.blurb.isNotBlank()) append(e.blurb.trim()).append('\n')
        val bits = mutableListOf<String>()
        if (e.category.isNotEmpty()) bits += e.category
        if (e.version.isNotEmpty()) bits += "v${e.version}"
        if (e.bytes > 0) bits += "${(e.bytes + 524_288) / 1_048_576} MB"
        if (bits.isNotEmpty()) append(bits.joinToString(" · "))
        if (e.updated > 0) {
            if (isNotEmpty()) append('\n')
            append("Updated ").append(DateFormat.format("d MMM yyyy", e.updated)).append('.')
        }
    }.ifEmpty { "An installed app." }

    /** [game]: un juego de Android, que con dos pantallas va a la de la imagen (ver DualPlay). */
    fun launch(ctx: Context, entry: AppEntry, game: Boolean = false): String? {
        val intent = ctx.packageManager.getLaunchIntentForPackage(entry.pkg)
            ?: return "No launch intent for ${entry.label}"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { if (game) DualPlay.launchGame(ctx, intent) else ctx.startActivity(intent); null }
            .getOrElse { "Did not start: ${it.javaClass.simpleName}" }
    }

    /**
     * Hands the package to the system uninstaller. The confirmation and the outcome are
     * Android's: a frontend has no business removing packages by itself.
     *
     * Three attempts, because which of them exists depends on the ROM. ACTION_DELETE is
     * the plain one; ACTION_UNINSTALL_PACKAGE is the one that asks for a result and needs
     * REQUEST_DELETE_PACKAGES, which is declared in the manifest; and if neither resolves
     * — which is what happens on a device whose installer is locked down — the app's own
     * page in Settings does, and it has an Uninstall button on it. The first version only
     * tried ACTION_DELETE and silently did nothing when it was not handled.
     */
    fun uninstall(ctx: Context, entry: AppEntry): String? {
        val uri = Uri.parse("package:${entry.pkg}")
        val tries = listOf(
            Intent(Intent.ACTION_DELETE, uri),
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
        )
        for (intent in tries) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(intent); true }.getOrDefault(false)) return null
        }
        return "No uninstaller on this device"
    }

    /**
     * Writes every app's icon as a PNG, once.
     *
     * Adaptive icons are XML, not images, so nothing outside the device can get at them:
     * the app is the only thing that can rasterise them, and it does so here so the
     * turntable pipeline on the PC has something to texture the medallions with. Existing
     * files are left alone; delete the folder to refresh.
     */
    fun exportIcons(ctx: Context, entries: List<AppEntry>) {
        runCatching { iconDir.mkdirs() }
        for (e in entries) {
            val f = File(iconDir, "${e.pkg}.png")
            if (f.isFile) continue
            val bmp = icon(ctx, e.pkg) ?: continue
            runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    /**
     * The app icon as a bitmap.
     *
     * Adaptive icons are not plain bitmaps, so they have to be drawn onto a canvas rather
     * than cast; without this every modern app would come back blank.
     */
    fun icon(ctx: Context, pkg: String): Bitmap? = runCatching {
        loadIcon(ctx, pkg)
    }.getOrNull()

    /**
     * Los iconos ya pintados, por paquete y version (lastUpdateTime): pintar uno es pedirselo al
     * sistema y dibujarlo, y se hacia en el hilo de la pantalla en cada casilla del cajon (revision del
     * 09-10-2026). Ver rememberAppIcon.
     */
    private val icons = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Bitmap>>()

    /** El icono si ya esta pintado, sin ir al sistema; nulo si no. */
    fun cachedIcon(pkg: String, updated: Long): Bitmap? = icons["$pkg@$updated"]?.orElse(null)

    /** El icono, pintado y guardado. En otro hilo. */
    fun iconFor(ctx: Context, pkg: String, updated: Long): Bitmap? =
        icons.getOrPut("$pkg@$updated") { java.util.Optional.ofNullable(icon(ctx, pkg)) }.orElse(null)

    private fun loadIcon(ctx: Context, pkg: String): Bitmap? = runCatching {
        val d: Drawable = ctx.packageManager.getApplicationIcon(pkg)
        if (d is BitmapDrawable && d.bitmap != null) return@runCatching d.bitmap
        val w = d.intrinsicWidth.coerceIn(1, 192)
        val h = d.intrinsicHeight.coerceIn(1, 192)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, canvas.width, canvas.height)
        d.draw(canvas)
        bmp
    }.getOrNull()
}
