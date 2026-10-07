package com.felp.frontcomp

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * El diagnostico que se adjunta a un aviso de fallo (07-10-2026): un zip en Download con la version,
 * el aparato, los ajustes, el registro reciente de Ludolog, como acabaron sus ultimos procesos y los
 * fallos que dejo CrashLog.
 *
 * Se adjunta a un aviso publico en GitHub, asi que: sin claves ni contraseñas (los ajustes con nombre
 * de credencial no entran), las dos ultimas cifras de cada IP tapadas, y de la biblioteca solo
 * cuantos juegos hay por consola, no sus nombres. Las rutas de carpetas si van: hacen falta para
 * entender un fallo de lectura, y About dice que se revise antes de compartirlo.
 */
internal object Diagnostics {

    /** Escribe el zip en Download y devuelve donde quedo. Bloquea: fuera del hilo de la pantalla. */
    fun export(ctx: Context, prefs: Prefs, library: ScanResult?): String {
        val name = "ludolog-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download is not available")
        ctx.contentResolver.openOutputStream(uri).use { out ->
            ZipOutputStream(out ?: error("Download is not available")).use { z ->
                put(z, "info.txt", info(ctx, prefs, library))
                put(z, "settings.txt", settings(prefs))
                put(z, "log.txt", logcat())
                put(z, "exits.txt", exits(ctx))
                CrashLog.files(ctx).forEach { f -> put(z, "crashes/${f.name}", f.readText()) }
            }
        }
        return "Download/$name"
    }

    private fun put(z: ZipOutputStream, name: String, text: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(redact(text).toByteArray())
        z.closeEntry()
    }

    // Solo las IP de la red local: «21.0.12.101» tiene forma de IP y es una version.
    private val ip = Regex("""\b(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}\b""")
    private fun redact(s: String) = ip.replace(s) { m ->
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        val local = a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        if (local) "$a.$b.x.x" else m.value
    }

    private fun info(ctx: Context, prefs: Prefs, library: ScanResult?): String = buildString {
        val pm = ctx.packageManager
        val me = runCatching { pm.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        appendLine("Ludolog ${me?.versionName} (${me?.longVersionCode})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val dm = ctx.resources.displayMetrics
        appendLine("Screen: ${dm.widthPixels}x${dm.heightPixels}, ${dm.densityDpi} dpi")
        appendLine("Locale: ${Locale.getDefault()}, time zone: ${java.util.TimeZone.getDefault().id}")
        appendLine("Theme: ${prefs.themeId}")
        appendLine("Data folder: ${runCatching { DataHome.dir.path }.getOrDefault("?")}")
        val link = runCatching { pm.getPackageInfo("com.felp.ludologlink", 0).versionName }.getOrNull()
        appendLine("Ludolog Link: ${link ?: "not installed"}")
        appendLine()
        if (library == null) appendLine("Library: not scanned yet")
        else {
            appendLine("Library: ${library.games.size} games, ${library.filesSeen} files seen, ${library.unknownFolders.size} unknown folders")
            library.games.groupingBy { it.systemId }.eachCount().toSortedMap().forEach { (s, n) -> appendLine("  $s: $n") }
        }
    }

    /** Los ajustes, sin nada que se llame como una credencial. */
    private fun settings(prefs: Prefs): String {
        // Por el nombre de una credencial. «key» suelto no: los atajos de botones se llaman key.* y hacen falta.
        val secret = Regex("(?i)(secret|pass|token|cred|apikey|api_key|client|login|^art\\.)")
        return prefs.dump().entries.joinToString("\n") { (k, v) ->
            if (secret.containsMatchIn(k)) "$k = <hidden>" else "$k = $v"
        }
    }

    /** El registro de este proceso: Android solo deja a cada app leer el suyo. */
    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.takeLast(2_000_000)
    }.getOrElse { "logcat failed: ${it.message}" }

    /** Como acabaron los ultimos procesos de Ludolog: un cierre del sistema, un fallo, un ANR. */
    private fun exits(ctx: Context): String = buildString {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return@buildString
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (e in runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10) }.getOrDefault(emptyList())) {
            appendLine("${stamp.format(Date(e.timestamp))}  ${reason(e.reason)}  importance ${e.importance}  ${e.description.orEmpty()}")
            if (e.reason == ApplicationExitInfo.REASON_ANR || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText().take(200_000) } }
                    .getOrNull()?.let { appendLine(it) }
            }
        }
    }

    private fun reason(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        // Los de Android 12 y 13, por numero: la app arranca desde Android 11.
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($r)"
    }
}

/**
 * Los fallos de Kotlin que cierran Ludolog, guardados para el diagnostico (07-10-2026). Android
 * cuenta que el proceso murio (ver Diagnostics.exits) pero no con que excepcion: eso solo se sabe
 * aqui, justo antes. Se guardan los cinco ultimos en la carpeta privada, y despues sigue el
 * manejador de siempre, que cierra la app como antes.
 */
internal object CrashLog {
    private const val KEEP = 5

    fun install(ctx: Context) {
        val dir = File(ctx.filesDir, "crashes")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching {
                dir.mkdirs()
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                File(dir, "crash-$stamp.txt").writeText("Thread: ${thread.name}\n\n" + t.stackTraceToString())
                dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, t)
        }
    }

    fun files(ctx: Context): List<File> =
        File(ctx.filesDir, "crashes").listFiles()?.sortedBy { it.name }.orEmpty()
}
