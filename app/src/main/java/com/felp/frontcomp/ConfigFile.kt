package com.felp.frontcomp

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Los ajustes, en un fichero de la carpeta de datos y no en las preferencias privadas.
 *
 * Es un SharedPreferences de verdad, con su contrato —`apply` cambia la memoria al momento y
 * escribe en otro hilo, `commit` no vuelve hasta que esta en disco— para que Prefs no tenga
 * que saber donde vive lo que lee. Y con el formato de Android, el mismo XML: el fichero se
 * lee a ojo, se corrige con un editor de texto, y el de antes se trae entero.
 *
 * Uno por proceso. `Prefs(ctx)` se crea en muchos sitios —la actividad, el servicio que mide
 * las partidas, la tarjeta de encima del juego— y todos tienen que ver el mismo mapa: lo que
 * guarda uno lo lee el siguiente sin pasar por el disco.
 *
 * Escribe con AtomicFile: el fichero nuevo aparte y despues el cambio de nombre. Un corte a
 * mitad —sacar la tarjeta, quedarse sin bateria— deja entero el anterior, no uno a medias.
 */
internal class ConfigFile private constructor(
    /**
     * Donde se escribe, o null si no se debe escribir en ninguna parte.
     *
     * Null antes de elegir carpeta, y tambien si la elegida no estaba al abrir o no se pudo
     * leer. En esos casos lo que hay en memoria son los valores de fabrica, y escribirlos en
     * cuanto la tarjeta volviera pisaria los ajustes de verdad.
     */
    private val target: AtomicFile?,
    initial: Map<String, Any?>,
) : SharedPreferences {

    private val lock = Any()
    private val writeLock = Any()
    private val map = HashMap(initial)
    private val listeners = WeakHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Unit>()

    /** Si ya hay una escritura en cola: varias `apply` seguidas se quedan en una. */
    private var queued = false

    override fun getAll(): MutableMap<String, *> = synchronized(lock) { HashMap(map) }

    override fun getString(key: String, defValue: String?): String? =
        synchronized(lock) { map[key] as String? } ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        synchronized(lock) { (map[key] as Set<*>?)?.mapTo(HashSet()) { it as String } } ?: defValues

    override fun getInt(key: String, defValue: Int): Int =
        synchronized(lock) { map[key] as Int? } ?: defValue

    override fun getLong(key: String, defValue: Long): Long =
        synchronized(lock) { map[key] as Long? } ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        synchronized(lock) { map[key] as Float? } ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        synchronized(lock) { map[key] as Boolean? } ?: defValue

    override fun contains(key: String): Boolean = synchronized(lock) { key in map }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(lock) { listeners[listener] = Unit }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private inner class Editor : SharedPreferences.Editor {
        /** Lo cambiado, en orden. Un null es quitar la clave, como en Android. */
        private val changes = LinkedHashMap<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = also { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) =
            also { changes[key] = values?.let(::HashSet) }
        override fun putInt(key: String, value: Int) = also { changes[key] = value }
        override fun putLong(key: String, value: Long) = also { changes[key] = value }
        override fun putFloat(key: String, value: Float) = also { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { changes[key] = value }
        override fun remove(key: String) = also { changes[key] = null }
        override fun clear() = also { clear = true }

        override fun apply() {
            announce(toMemory())
            queueWrite()
        }

        override fun commit(): Boolean {
            announce(toMemory())
            return target?.let(::writeNow) ?: true
        }

        private fun toMemory(): List<String> = synchronized(lock) {
            val touched = ArrayList<String>()
            if (clear) {
                touched += map.keys
                map.clear()
            }
            for ((k, v) in changes) {
                if (v == null) {
                    if (map.remove(k) != null) touched += k
                } else if (map[k] != v) {
                    map[k] = v
                    touched += k
                }
            }
            touched
        }
    }

    private fun announce(keys: List<String>) {
        if (keys.isEmpty()) return
        val who = synchronized(lock) { listeners.keys.toList() }
        if (who.isEmpty()) return
        val run = { for (k in keys) for (l in who) l.onSharedPreferenceChanged(this, k) }
        if (Looper.myLooper() == Looper.getMainLooper()) run() else MAIN.post(run)
    }

    private fun queueWrite() {
        val t = target ?: return
        synchronized(lock) {
            if (queued) return
            queued = true
        }
        WRITER.execute {
            synchronized(lock) { queued = false }
            writeNow(t)
        }
    }

    private fun writeNow(t: AtomicFile): Boolean = synchronized(writeLock) {
        val snapshot = synchronized(lock) { HashMap(map) }
        runCatching { write(t, snapshot) }
            .onFailure { android.util.Log.e("Ludolog", "config: ${it.message}") }
            .isSuccess
    }

    companion object {
        /** Un solo hilo: las escrituras salen en orden y nunca dos a la vez. */
        private val WRITER = Executors.newSingleThreadExecutor { r ->
            Thread(r, "Ludolog-config").apply { isDaemon = true }
        }
        private val MAIN = Handler(Looper.getMainLooper())

        @Volatile
        private var shared: ConfigFile? = null

        /** El del proceso. Se abre la primera vez que alguien lo pide y ya no cambia, salvo [reopen]. */
        fun open(): SharedPreferences = shared ?: synchronized(this) {
            shared ?: create().also { shared = it }
        }

        /**
         * Lo vuelve a abrir, ya con la carpeta de datos elegida: lo llama DataHome.adopt.
         *
         * En un primer arranque la actividad lee los ajustes antes de que haya carpeta, y el de
         * entonces —sin fichero, a proposito— se quedaba para todo el proceso. Lo que se guardaba
         * despues en la bienvenida, la carpeta de ROMs elegida, vivia solo en memoria y se perdia
         * al reiniciar. Los Prefs que ya estaban siguen con el viejo; los que se creen desde aqui,
         * con este.
         */
        fun reopen() {
            synchronized(this) { shared = create() }
        }

        private fun create(): ConfigFile {
            if (!DataHome.ready()) return ConfigFile(null, emptyMap())
            val file = AtomicFile(DataHome.file(DataHome.CONFIG))
            return runCatching { ConfigFile(file, read(file)) }.getOrElse {
                // Ilegible no es vacio: se arranca con lo de fabrica pero sin escribir, que es
                // la unica manera de no perder lo que hubiera dentro.
                android.util.Log.e("Ludolog", "config ilegible: ${it.message}")
                ConfigFile(null, emptyMap())
            }
        }

        /**
         * Escribe `into` con lo que haya en `from`, menos las credenciales. Si ya existe, nada.
         *
         * Es la mudanza de los ajustes: las preferencias privadas de antes pasan al fichero tal
         * cual, con sus tipos.
         */
        fun seed(into: File, from: SharedPreferences) {
            if (into.exists()) return
            val kept = from.all.filterKeys { !it.startsWith(Prefs.SECRET) }
                .filterValues { it != null }
            write(AtomicFile(into), kept)
        }

        private fun read(file: AtomicFile): Map<String, Any?> {
            val out = HashMap<String, Any?>()
            val input = try {
                file.openRead()
            } catch (e: FileNotFoundException) {
                return out
            }
            input.use {
                val p = Xml.newPullParser()
                p.setInput(it, "utf-8")
                var setName: String? = null
                var set: HashSet<String>? = null
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        val name = p.getAttributeValue(null, "name")
                        val value = p.getAttributeValue(null, "value")
                        when (p.name) {
                            "string" -> {
                                val text = p.nextText()
                                if (set != null) set.add(text) else if (name != null) out[name] = text
                            }
                            "int" -> if (name != null) out[name] = value.toInt()
                            "long" -> if (name != null) out[name] = value.toLong()
                            "float" -> if (name != null) out[name] = value.toFloat()
                            "boolean" -> if (name != null) out[name] = value.toBoolean()
                            "set" -> {
                                setName = name
                                set = HashSet()
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG && p.name == "set") {
                        if (setName != null) out[setName] = set
                        setName = null
                        set = null
                    }
                    ev = p.next()
                }
            }
            return out
        }

        private fun write(file: AtomicFile, values: Map<String, Any?>) {
            val out = file.startWrite()
            try {
                serialize(values, out)
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }

        /** El XML de Android, con las claves en orden para que dos versiones se comparen a ojo. */
        private fun serialize(values: Map<String, Any?>, out: OutputStream) {
            val s = Xml.newSerializer()
            s.setOutput(out, "utf-8")
            s.startDocument("utf-8", true)
            runCatching { s.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true) }
            s.startTag(null, "map")
            for ((k, v) in values.toSortedMap()) {
                fun scalar(tag: String) {
                    s.startTag(null, tag).attribute(null, "name", k)
                        .attribute(null, "value", v.toString()).endTag(null, tag)
                }
                when (v) {
                    is String -> s.startTag(null, "string").attribute(null, "name", k)
                        .text(v).endTag(null, "string")
                    is Int -> scalar("int")
                    is Long -> scalar("long")
                    is Float -> scalar("float")
                    is Boolean -> scalar("boolean")
                    is Set<*> -> {
                        s.startTag(null, "set").attribute(null, "name", k)
                        for (x in v.map { it.toString() }.sorted()) {
                            s.startTag(null, "string").text(x).endTag(null, "string")
                        }
                        s.endTag(null, "set")
                    }
                }
            }
            s.endTag(null, "map")
            s.endDocument()
            out.flush()
        }
    }
}
