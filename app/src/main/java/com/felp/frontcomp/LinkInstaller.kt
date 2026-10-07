package com.felp.frontcomp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Instalar Ludolog Link desde la bienvenida (paso LINK de DataSetup).
 *
 * De un APK que la persona dejo en el aparato (se busca solo en las carpetas Download de cada
 * unidad y en la de Ludolog y su `link/`, o se elige a mano), o bajado del ultimo release de
 * ludolog-link en GitHub ([latest], [download]; 07-10-2026). El paso de instalar —[inspect] e
 * [install]— es el mismo venga de donde venga el archivo. Desde Ajustes, [getAndInstall].
 *
 * Antes de ofrecerlo se mira que sea Link de verdad: su paquete, y que este firmado con la misma
 * clave que Ludolog. Con otra clave Android lo instalaria, pero no podria hablar con Ludolog (el
 * permiso LINK es de firma), y nada avisaria de por que.
 */
internal object LinkInstaller {
    const val PACKAGE = "com.felp.ludologlink"
    private const val APK = "application/vnd.android.package-archive"

    /** Un APK de Link en el aparato: donde esta, su version y si se puede instalar. */
    class Found(val file: File, val version: String, val code: Long, val problem: String?)

    /** El mejor APK de Link que haya en las carpetas de siempre, o nulo. Toca disco: fuera del hilo de la interfaz. */
    fun findLocal(ctx: Context): Found? {
        val dirs = buildList {
            for (v in DataHome.volumes()) { add(File(v, "Download")); add(File(v, "Downloads")) }
            if (DataHome.ready()) { add(DataHome.dir); add(File(DataHome.dir, "link")) }
        }
        return dirs.asSequence()
            .flatMap { d -> d.listFiles()?.asSequence().orEmpty() }
            .filter { it.isFile && it.name.endsWith(".apk", true) && it.name.contains("link", true) }
            .mapNotNull { inspect(ctx, it) }
            // El que se puede instalar antes que el que no, y de esos el mas nuevo.
            .sortedWith(compareBy<Found> { it.problem != null }.thenByDescending { it.code })
            .firstOrNull()
    }

    /** Lo que es [apk]: nulo si no es Link; con [Found.problem] si lo es pero no sirve. */
    fun inspect(ctx: Context, apk: File): Found? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = runCatching { pm.getPackageArchiveInfo(apk.path, flags) }.getOrNull() ?: return null
        if (info.packageName != PACKAGE) return null
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val theirs = signatures(info)
        val ours = runCatching { signatures(pm.getPackageInfo(ctx.packageName, flags)) }.getOrDefault(emptySet())
        // Si Android no dice la firma del archivo (pasa en algunas versiones), no se descarta por eso:
        // el instalador y el permiso de firma siguen mandando.
        val problem = if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours)
            "signed with another key: it could not talk to Ludolog" else null
        return Found(apk, info.versionName.orEmpty(), code, problem)
    }

    private fun signatures(info: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else @Suppress("DEPRECATION") info.signatures
        return sigs.orEmpty().map { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    /** Si Android deja a Ludolog instalar apps. Si no, primero [allowSettings]. */
    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** La pagina de Android donde se deja a Ludolog instalar apps («fuentes desconocidas»). */
    fun allowSettings(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /**
     * Lo copia a un sitio propio y abre el instalador de Android, que pregunta. Al volver, quien
     * llama mira LinkSaveCheck.present (se actualiza en MainActivity.onResume).
     *
     * La copia es para que el instalador lea un archivo que no cambia ni desaparece a mitad: el de
     * Download puede estar todavia bajandose, o estar en una tarjeta que se saca.
     */
    fun install(ctx: Context, apk: File): Result<Unit> = runCatching {
        val copy = File(DataHome.work(), "ludolog-link.apk")
        apk.inputStream().use { i -> copy.outputStream().use { o -> i.copyTo(o) } }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", copy)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ------------------------------------------------------------------ desde GitHub

    /** La pagina del ultimo release, para quien lo busque a mano. */
    const val PAGE = "https://github.com/monkikolab/ludolog-link/releases/latest"
    private const val RELEASE_API = "https://api.github.com/repos/monkikolab/ludolog-link/releases/latest"

    /** El APK del ultimo release: su nombre, de donde se baja, cuanto ocupa y la version. */
    class Offer(val name: String, val url: String, val bytes: Long, val version: String)

    /**
     * Lo ultimo publicado, o por que no se pudo saber. Se pregunta a la API porque el nombre del APK
     * lleva la version (ludolog-link-0.5.0.apk): no hay un nombre fijo que pedir. Toca la red.
     */
    fun latest(): Result<Offer> = runCatching {
        val c = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Ludolog")
        }
        val body = try {
            when (c.responseCode) {
                200 -> {}
                404 -> error("not published yet")
                else -> error("GitHub answered ${c.responseCode}")
            }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val release = JSONObject(body)
        val assets = release.optJSONArray("assets")
        // El de Android: el release trae tambien el instalador y el zip del PC.
        val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }.firstOrNull {
            val n = it.optString("name")
            n.endsWith(".apk", true) && n.contains("link", true)
        } ?: error("the release has no Android app")
        val url = apk.getString("browser_download_url")
        if (!url.startsWith("https://github.com/")) error("unexpected download address")
        Offer(apk.getString("name"), url, apk.optLong("size"), release.optString("tag_name").removePrefix("v"))
    }

    /**
     * Baja [offer] y lo mira como cualquier otro APK ([inspect]): si no es Link, o no lleva la clave
     * de Ludolog, no se instala. Bloquea.
     */
    fun download(ctx: Context, offer: Offer): Result<Found> = runCatching {
        val bytes = GameDb.get(offer.url)
        if (offer.bytes > 0 && bytes.size.toLong() != offer.bytes) error("it arrived incomplete")
        val file = File(DataHome.work(), "github-link.apk")
        file.writeBytes(bytes)
        inspect(ctx, file) ?: error("the file is not Ludolog Link")
    }

    /** Lo que dice la fila de Ajustes: [DOWNLOADING], un aviso corto, o nulo en reposo. */
    val status = mutableStateOf<String?>(null)
    /** El porque del ultimo fallo, para la descripcion de esa fila. */
    val lastError = mutableStateOf<String?>(null)
    const val DOWNLOADING = "downloading…"

    /** Desde Ajustes: baja el ultimo de GitHub y abre el instalador de Android, que pregunta. */
    fun getAndInstall(ctx: Context) {
        if (status.value == DOWNLOADING) return
        if (!allowed(ctx)) {
            status.value = "allow, then again"
            lastError.value = "Android asks to let Ludolog install apps first."
            runCatching { ctx.startActivity(allowSettings(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        status.value = DOWNLOADING
        lastError.value = null
        kotlin.concurrent.thread(name = "link-install") {
            val r = latest().mapCatching { download(ctx, it).getOrThrow() }.mapCatching { f ->
                f.problem?.let { error(it) }
                install(ctx, f.file).getOrThrow()
            }
            Handler(Looper.getMainLooper()).post {
                status.value = if (r.isSuccess) null else "failed"
                lastError.value = r.exceptionOrNull()?.message
            }
        }
    }

    /** Un APK elegido a mano (content://), copiado a un archivo propio para mirarlo e instalarlo. */
    fun fromPicked(ctx: Context, uri: Uri): File? = runCatching {
        val dest = File(DataHome.work(), "picked-link.apk")
        ctx.contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } } ?: return null
        dest
    }.getOrNull()
}
