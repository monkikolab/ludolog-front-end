package com.felp.frontcomp

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Que juego es un fichero, por lo que lleva dentro y no por como se llama.
 *
 * El nombre miente a menudo: «Echo Night (ESP).bin» es la version americana, los dos Earthworm
 * Jim de la carpeta de 32X son de Mega Drive, y una ROM renombrada a mano ya no se parece a
 * nada. Lo de dentro no miente, y con eso el catalogo (GameDb) dice el nombre exacto, la
 * consola de verdad y los datos del juego.
 *
 * Devuelve CLAVES, las mismas que escribe el generador del catalogo (tools/catalog/build.mjs):
 *
 *     c:<crc32>          cartuchos y ficheros sueltos
 *     h:<sha1, 16 hex>   la ISO entera: en un CHD de DVD es la que lleva su cabecera
 *     s:<serial>         discos: SYSTEM.CNF, UMD_DATA.BIN, PARAM.SFO; sin guiones
 *     i:<codigo>         GameCube y Wii: las cuatro primeras letras del ID del disco
 *     g:<codigo>         DS y 3DS: el codigo de juego de cuatro letras de su cabecera
 *     r:<romset>         arcade: el nombre del zip
 *     t:<title id>       Switch: el que va en el nombre (el contenido esta cifrado)
 *     v:<numero>         PC: el numero de Steam que lleva dentro el fichero `.steam`
 *     a:<paquete>        Android: el paquete de la app (no sale de aqui, ver Dossiers.refresh)
 *
 * Mas claves de las necesarias no estorban: una de cartucho no casa nunca en un paquete de
 * discos, ni un romset en uno de cartuchos. Por eso un zip da a la vez su romset y el CRC de lo
 * que lleva dentro, y una ROM de NES su CRC con cabecera y sin ella: no hace falta saber de que
 * consola es para saber que mirar.
 *
 * Son lecturas pequeñas salvo en los cartuchos, que se leen enteros para su CRC (un N64, 64 MB
 * como mucho). Se hace una vez por fichero: el resultado queda en su ficha (Dossiers), y solo
 * se repite si el fichero cambia de tamaño o de fecha.
 *
 * Kotlin puro y sin Android, para que corra en las pruebas. Y sin `readNBytes`, que en Android
 * no existe hasta la 13 y la app arranca desde la 11.
 */
internal object GameId {

    /** Mas grande que esto no es un cartucho y no se lee entero. */
    private const val CART_MAX = 128L shl 20

    /** Los discos se leen por su contenido; su CRC no se calcula nunca. */
    private val DISC = setOf(
        "iso", "gcm", "chd", "cue", "rvz", "wia", "wbfs", "ciso", "pbp", "cso", "zso", "gcz",
        "nrg", "mdf", "img", "ccd", "gdi", "cdi", "toc", "xci", "nsp", "vpk",
    )

    /** Lo que no es un juego que se pueda leer: accesos, listas, textos. */
    private val SKIP = setOf("m3u", "steam", "app", "lnk", "desktop", "sh", "bat", "txt", "shortcut")

    /** Las claves de un fichero; vacio si no se le saca nada. */
    fun of(file: File): List<String> {
        val name = file.name
        val ext = Catalog.extensionOf(name)
        val keys = LinkedHashSet<String>()
        runCatching {
            when {
                ext == "zip" -> zip(file, keys)
                ext == "7z" -> keys += "r:" + stem(name).lowercase()
                ext == "chd" -> chd(file)?.let(keys::add)
                ext == "iso" || ext == "gcm" -> keys += iso(file, raw = false)
                ext in setOf("rvz", "wia", "wbfs", "ciso") -> nintendoDisc(file)?.let(keys::add)
                ext == "cue" -> cueData(file)?.let { keys += iso(it, raw = true) }
                ext == "bin" && rawDisc(file) -> keys += iso(file, raw = true)
                ext == "pbp" -> pbp(file)?.let(keys::add)
                // DS: el codigo de su cabecera, y el CRC si no es de las enormes (hay de 512 MB).
                ext == "nds" || ext == "dsi" -> {
                    RandomAccessFile(file, "r").use { f -> ndsHeader(ByteArray(0x10).also { f.read(it) }) }?.let(keys::add)
                    if (file.length() in 1..CART_MAX) keys += cart(file, ext)
                }
                ext == "3ds" || ext == "cci" -> ctrCode(file)?.let(keys::add)
                // Un acceso de Steam lleva dentro solo el numero del juego: «274520» es Darkwood.
                ext == "steam" -> steamId(file)?.let(keys::add)
                ext in DISC || ext in SKIP -> Unit
                file.length() in 1..CART_MAX -> keys += cart(file, ext)
            }
        }
        // Lo que diga el nombre, al final: el serial entre corchetes que ponen algunas
        // colecciones («[SLUS-00820]») y el Title ID de Switch.
        SERIAL_IN_NAME.find(name)?.let { keys += "s:" + serialKey(it.groupValues[1]) }
        TITLE_ID.find(name)?.let { keys += "t:" + it.groupValues[1].uppercase() }
        return keys.toList()
    }

    /** Un serial como se compara: «SLUS-00820», «SLUS_008.20» y «SLUS00820» son el mismo. */
    fun serialKey(s: String): String = buildString { for (c in s.uppercase()) if (c in 'A'..'Z' || c in '0'..'9') append(c) }

    private fun stem(name: String): String {
        val ext = Catalog.extensionOf(name)
        return if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
    }

    private val SERIAL_IN_NAME = Regex("""[\[(]([A-Z]{4}[-_ ]?\d{3}\.?\d{2})[\])]""")
    private val TITLE_ID = Regex("""\[(01[0-9A-Fa-f]{14})]""")

    // ------------------------------------------------------------------ cartuchos

    /**
     * El CRC de un cartucho, y el de su contenido sin la cabecera que le ponen los volcadores si
     * la tiene: No-Intro guarda la de NES sin sus 16 bytes, y libretro tiene ademas la lista con
     * ellos. Los dos salen de una sola pasada.
     *
     * Los de N64 se ponen en el orden de bytes de No-Intro (.z64), y un .smd de Mega Drive se
     * desentrelaza: tal cual no casan con nada. Las dos cosas se hacen a trozos, sin cargar el
     * fichero entero en memoria.
     */
    fun cart(file: File, ext: String = Catalog.extensionOf(file.name)): List<String> {
        val size = file.length()
        val head = file.inputStream().use { take(it, 16) }
        if (head.size < 16) return listOf(crcStream(file, 0))
        val magic = be32(head, 0)
        return when {
            magic == N64_WORDS || magic == N64_PAIRS -> listOf(n64(file, magic))
            ext == "smd" && size > 512 -> listOf(smdCrc(file), crcStream(file, 0))
            else -> crcs(file, headerSkip(head, ext, size))
        }
    }

    private const val N64_WORDS = 0x40123780
    private const val N64_PAIRS = 0x37804012

    /**
     * La cabecera que sobra, si la hay: la de NES y FDS (16), la de Lynx (64), la de Atari 7800
     * (128) y la de copiadora de SNES y PC Engine (512, cuando el tamaño no es de una ROM).
     */
    private fun headerSkip(head: ByteArray, ext: String, size: Long): Int = when {
        ascii(head, 0, 4) == "NES\u001a" || ascii(head, 0, 4) == "FDS\u001a" -> 16
        ascii(head, 0, 4) == "LYNX" -> 64
        ascii(head, 1, 10) == "ATARI7800" -> 128
        ext in setOf("smc", "sfc", "pce", "fig", "swc") && size % 1024 == 512L -> 512
        else -> 0
    }

    /** El CRC entero y, si [skip] no es cero, tambien el de lo que va despues de esos bytes. */
    private fun crcs(file: File, skip: Int): List<String> {
        val whole = CRC32()
        val tail = CRC32()
        var pos = 0L
        val buf = ByteArray(1 shl 16)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                whole.update(buf, 0, n)
                if (skip > 0) {
                    val from = (skip - pos).coerceIn(0L, n.toLong()).toInt()
                    if (from < n) tail.update(buf, from, n - from)
                }
                pos += n
            }
        }
        return if (skip > 0 && pos > skip) listOf("c:" + hex(whole.value), "c:" + hex(tail.value))
        else listOf("c:" + hex(whole.value))
    }

    private fun crcStream(file: File, skip: Int) = crcs(file, skip).last()

    /** .n64 (palabras al reves) y .v64 (pares al reves), como .z64, que es como lo guarda No-Intro. */
    private fun n64(file: File, magic: Int): String {
        val c = CRC32()
        val buf = ByteArray(1 shl 16)
        file.inputStream().use { input ->
            while (true) {
                val n = fill(input, buf)
                if (n <= 0) break
                swap(buf, n, magic)
                c.update(buf, 0, n)
                if (n < buf.size) break
            }
        }
        return "c:" + hex(c.value)
    }

    private fun swap(b: ByteArray, n: Int, magic: Int) {
        if (magic == N64_WORDS) {
            var i = 0
            while (i + 3 < n) {
                val x = b[i]; val y = b[i + 1]
                b[i] = b[i + 3]; b[i + 1] = b[i + 2]; b[i + 2] = y; b[i + 3] = x
                i += 4
            }
        } else {
            var i = 0
            while (i + 1 < n) { val x = b[i]; b[i] = b[i + 1]; b[i + 1] = x; i += 2 }
        }
    }

    /**
     * Un .smd en el orden normal: tras 512 bytes de cabecera, bloques de 16 KB con los bytes
     * impares en la primera mitad y los pares en la segunda.
     */
    private fun smdCrc(file: File): String {
        val c = CRC32()
        val block = ByteArray(16384)
        val out = ByteArray(16384)
        file.inputStream().use { input ->
            take(input, 512)
            while (true) {
                val n = fill(input, block)
                if (n <= 0) break
                // Un bloque final mas corto tiene sus dos mitades en su propio largo, no a 8 KB: se
                // leian bytes del bloque anterior y salia un CRC que no era (revision del 09-10-2026).
                val half = minOf(n, block.size) / 2
                for (i in 0 until half) {
                    out[i * 2 + 1] = block[i]
                    out[i * 2] = block[half + i]
                }
                c.update(out, 0, half * 2)
                if (n < block.size) break
            }
        }
        return "c:" + hex(c.value)
    }

    /** Lo mismo que [cart], sobre un contenido ya en memoria (lo que venia en un zip). */
    internal fun cartBytes(b: ByteArray, ext: String): List<String> {
        fun crc(bytes: ByteArray, from: Int = 0) = CRC32().run { update(bytes, from, bytes.size - from); "c:" + hex(value) }
        if (b.size < 16) return listOf(crc(b))
        val magic = be32(b, 0)
        return when {
            magic == N64_WORDS || magic == N64_PAIRS -> listOf(crc(b.copyOf().also { swap(it, it.size, magic) }))
            ext == "smd" && b.size > 512 -> listOf(crc(smd(b)), crc(b))
            else -> headerSkip(b, ext, b.size.toLong()).let { skip ->
                if (skip > 0 && b.size > skip) listOf(crc(b), crc(b, skip)) else listOf(crc(b))
            }
        }
    }

    internal fun smd(b: ByteArray): ByteArray {
        val body = b.copyOfRange(512, b.size)
        val out = ByteArray(body.size)
        var blk = 0
        while (blk < body.size) {
            // Igual que en smdCrc: las mitades de un bloque final corto van en su propio largo; a 8 KB
            // se salia del fichero y ese zip se quedaba sin CRC (revision del 09-10-2026).
            val half = minOf(8192, (body.size - blk) / 2)
            for (i in 0 until half) {
                out[blk + i * 2 + 1] = body[blk + i]
                out[blk + i * 2] = body[blk + half + i]
            }
            blk += 16384
        }
        return out
    }

    // ------------------------------------------------------------------ zip

    /**
     * Un zip: el nombre del romset, por si es de arcade, y el CRC de lo que lleva dentro, que el
     * zip ya trae escrito en su directorio sin descomprimir nada.
     *
     * Solo con pocos ficheros dentro: un romset de arcade lleva decenas de trozos y sus CRC no
     * dicen nada. Hay dos casos que no se leen del directorio: las ROM a las que hay que quitar
     * la cabecera o dar la vuelta (se descomprimen, que son pequeñas), y un disco de GameCube o
     * Wii (basta su principio).
     */
    private fun zip(file: File, keys: MutableSet<String>) {
        keys += "r:" + stem(file.name).lowercase()
        ZipFile(file).use { z ->
            val entries = z.entries().toList().filter { !it.isDirectory }
            if (entries.size > 3) return
            for (e in entries) {
                val inner = Catalog.extensionOf(e.name)
                when {
                    inner in setOf("rvz", "wia", "iso", "gcm", "wbfs", "ciso") ->
                        z.getInputStream(e).use { nintendoHeader(take(it, 0x8100)) }?.let(keys::add)
                    inner == "nds" -> {
                        z.getInputStream(e).use { ndsHeader(take(it, 0x10)) }?.let(keys::add)
                        if (e.crc >= 0) keys += "c:" + hex(e.crc)
                    }
                    inner in DISC -> Unit
                    inner in TRANSFORMED && e.size in 1..(64L shl 20) ->
                        keys += cartBytes(z.getInputStream(e).use { it.readBytes() }, inner)
                    e.crc >= 0 -> keys += "c:" + hex(e.crc)
                }
            }
        }
    }

    private val TRANSFORMED = setOf("nes", "fds", "n64", "v64", "z64", "smd", "lnx", "a78", "smc", "sfc", "pce")

    // ------------------------------------------------------------------ discos

    /**
     * La cabecera de un CHD: la v5 (y la v4) guarda el SHA1 de los datos sin comprimir. En un DVD
     * —PS2, PSP— es el SHA1 de la ISO, el mismo que da Redump: se sabe que juego es sin
     * descomprimir nada. En un CD no casa, porque lleva dentro el subcodigo.
     */
    internal fun chd(file: File): String? {
        val h = RandomAccessFile(file, "r").use { f -> ByteArray(124).also { f.read(it) } }
        if (ascii(h, 0, 8) != "MComprHD") return null
        val at = when (be32(h, 12)) { 5 -> 0x40; 4 -> 0x58; else -> return null }
        return "h:" + h.copyOfRange(at, at + 8).joinToString("") { "%02x".format(it) }
    }

    /**
     * GameCube y Wii guardan su ID de seis letras al principio del disco, y los formatos
     * comprimidos llevan copia: RVZ y WIA en 0x58, WBFS en 0x200, CISO en 0x8000.
     */
    private fun nintendoDisc(file: File): String? =
        nintendoHeader(RandomAccessFile(file, "r").use { f -> ByteArray(0x8100).also { f.read(it) } })

    internal fun nintendoHeader(h: ByteArray): String? {
        fun id(at: Int): String? {
            if (h.size < at + 6) return null
            val s = ascii(h, at, at + 6)
            return if (s.all { it in 'A'..'Z' || it in '0'..'9' }) "i:" + s.take(4) else null
        }
        return when {
            ascii(h, 0, 3) == "RVZ" || ascii(h, 0, 3) == "WIA" -> id(0x58)
            ascii(h, 0, 4) == "WBFS" -> id(0x200)
            ascii(h, 0, 4) == "CISO" -> id(0x8000)
            // Una imagen normal: el numero magico de GameCube en 0x1C, o el de Wii en 0x18.
            h.size >= 0x20 && (be32(h, 0x1C) == 0xC2339F3D.toInt() || be32(h, 0x18) == 0x5D1C9EA3) -> id(0)
            else -> null
        }
    }

    /**
     * El codigo de juego de una ROM de DS: cuatro letras en 0x0C de su cabecera («ASME»), el mismo
     * que usan No-Intro y GameTDB. Asi se reconoce aunque pese demasiado para leerla entera.
     */
    internal fun ndsHeader(h: ByteArray): String? {
        val code = ascii(h, 0x0C, 0x10)
        return if (code.length == 4 && code.all { it in 'A'..'Z' || it in '0'..'9' }) "g:$code" else null
    }

    /**
     * El numero de Steam de un acceso `.steam`: el fichero es solo eso, unos digitos. Se leen como
     * mucho 32 bytes, por si alguien dejo ahi otra cosa con esa extension.
     */
    internal fun steamId(file: File): String? {
        if (file.length() !in 1..32) return null
        val id = file.readText().trim()
        return if (id.isNotEmpty() && id.all(Char::isDigit)) "v:$id" else null
    }

    /**
     * El de una ROM de 3DS (NCSD): la primera particion empieza donde dice su tabla, y en 0x150 de
     * su cabecera lleva el codigo de producto, «CTR-P-XXXX». La cabecera no va cifrada.
     */
    internal fun ctrCode(file: File): String? {
        RandomAccessFile(file, "r").use { f ->
            val h = ByteArray(0x200)
            f.read(h)
            if (ascii(h, 0x100, 0x104) != "NCSD") return null
            val at = (le32(h, 0x120).toLong() and 0xFFFFFFFFL) * 0x200
            val p = ByteArray(0x10)
            f.seek(at + 0x150)
            f.read(p)
            return CTR_CODE.find(ascii(p, 0, 0x10))?.groupValues?.get(1)?.let { "g:$it" }
        }
    }

    private val CTR_CODE = Regex("""^[A-Z]{3}-[A-Z]-([A-Z0-9]{4})""")

    /** El primer fichero de datos que nombra un .cue. */
    private fun cueData(cue: File): File? = runCatching {
        cue.readLines().firstNotNullOfOrNull { CUE_FILE.find(it)?.groupValues?.get(1) }
            ?.let { File(cue.parentFile, it) }?.takeIf { it.isFile }
    }.getOrNull()

    private val CUE_FILE = Regex("""^\s*FILE\s+"([^"]+)"""", RegexOption.IGNORE_CASE)

    /** Si un .bin es la imagen cruda de un CD: empieza por la marca de sincronia de un sector. */
    private fun rawDisc(file: File): Boolean {
        val h = file.inputStream().use { take(it, 12) }
        return h.size == 12 && h[0] == 0.toByte() && (1..10).all { h[it] == 0xFF.toByte() } && h[11] == 0.toByte()
    }

    /**
     * Un disco ISO 9660, o su imagen cruda de 2352 bytes por sector: el serial de SYSTEM.CNF
     * (PlayStation y PS2), de UMD_DATA.BIN y PSP_GAME/PARAM.SFO (PSP) o de PS3_GAME/PARAM.SFO.
     * Y si no es ISO 9660 pero es de GameCube o Wii, su ID.
     */
    internal fun iso(file: File, raw: Boolean): List<String> {
        RandomAccessFile(file, "r").use { f ->
            if (!raw) {
                val head = ByteArray(0x20)
                f.seek(0); f.read(head)
                nintendoHeader(head)?.let { return listOf(it) }
            }
            // En crudo, los datos de cada sector van tras 16 bytes (modo 1) o 24 (modo 2).
            val sector: Int
            val skip: Int
            if (raw) {
                val probe = ByteArray(16)
                f.seek(16L * 2352); f.read(probe)
                sector = 2352; skip = if (probe[15] == 2.toByte()) 24 else 16
            } else {
                sector = 2048; skip = 0
            }
            val read = { lba: Long, count: Int ->
                val out = ByteArray(count * 2048)
                val buf = ByteArray(sector)
                for (i in 0 until count) {
                    f.seek((lba + i) * sector)
                    if (f.read(buf) < skip + 2048) break
                    System.arraycopy(buf, skip, out, i * 2048, 2048)
                }
                out
            }
            val pvd = read(16, 1)
            if (ascii(pvd, 1, 6) != "CD001") return emptyList()
            val root = dir(read, le32(pvd, 156 + 2).toLong(), le32(pvd, 156 + 10))
            val out = mutableListOf<String>()
            root["SYSTEM.CNF"]?.let { (lba, size) ->
                val txt = String(read(lba, blocks(size)), 0, minOf(size, 2048 * blocks(size)), Charsets.ISO_8859_1)
                BOOT.find(txt)?.let { out += "s:" + serialKey(it.groupValues[1]) }
            }
            root["UMD_DATA.BIN"]?.let { (lba, size) ->
                val txt = String(read(lba, 1), 0, minOf(size, 64), Charsets.ISO_8859_1)
                txt.substringBefore('|').trim().takeIf { it.length >= 9 }?.let { out += "s:" + serialKey(it) }
            }
            for (d in listOf("PSP_GAME", "PS3_GAME")) {
                val (lba, size) = root[d] ?: continue
                val (sl, ss) = dir(read, lba, size)["PARAM.SFO"] ?: continue
                val p = sfo(read(sl, blocks(ss)).copyOf(ss))
                (p["DISC_ID"] ?: p["TITLE_ID"])?.let { out += "s:" + serialKey(it) }
            }
            return out.distinct()
        }
    }

    private val BOOT = Regex("""BOOT2?\s*=\s*cdrom0?:\\?([A-Z]{4}[_-]\d{3}\.\d{2})""", RegexOption.IGNORE_CASE)

    private fun blocks(bytes: Int) = maxOf(1, (bytes + 2047) / 2048)

    /** Las entradas de un directorio ISO 9660: nombre -> (sector, tamaño). */
    private fun dir(read: (Long, Int) -> ByteArray, lba: Long, size: Int): Map<String, Pair<Long, Int>> {
        val buf = read(lba, blocks(size).coerceAtMost(16))
        val out = HashMap<String, Pair<Long, Int>>()
        var p = 0
        while (p < buf.size) {
            val len = buf[p].toInt() and 0xFF
            if (len == 0) { p = (p / 2048 + 1) * 2048; continue }
            if (p + 33 > buf.size) break
            val nlen = buf[p + 32].toInt() and 0xFF
            // Las dos primeras de cada directorio son «.» y «..», con un byte 0 o 1 por nombre.
            if (nlen > 1 || (nlen == 1 && (buf[p + 33].toInt() and 0xFF) > 1)) {
                val name = ascii(buf, p + 33, p + 33 + nlen).substringBefore(';').uppercase()
                out[name] = le32(buf, p + 2).toLong() to le32(buf, p + 10)
            }
            p += len
        }
        return out
    }

    /** Un PARAM.SFO: clave -> valor, como texto. */
    internal fun sfo(b: ByteArray): Map<String, String> {
        if (b.size < 20 || ascii(b, 0, 4) != "\u0000PSF") return emptyMap()
        val keyTable = le32(b, 8)
        val dataTable = le32(b, 12)
        val count = le32(b, 16)
        val out = HashMap<String, String>()
        for (i in 0 until count) {
            val e = 20 + i * 16
            if (e + 16 > b.size) break
            val keyOff = le16(b, e)
            val fmt = le16(b, e + 2)
            val len = le32(b, e + 4)
            val dOff = le32(b, e + 12)
            var k = keyTable + keyOff
            val key = buildString { while (k in b.indices && b[k] != 0.toByte()) append(b[k++].toInt().toChar()) }
            val at = dataTable + dOff
            if (at < 0 || at + len > b.size) continue
            out[key] = if (fmt == 0x0404) le32(b, at).toString()
            else String(b, at, len, Charsets.UTF_8).trimEnd('\u0000')
        }
        return out
    }

    /** Un EBOOT.PBP de PlayStation o PSP: su PARAM.SFO va al principio, y en el, el DISC_ID. */
    private fun pbp(file: File): String? {
        RandomAccessFile(file, "r").use { f ->
            val h = ByteArray(0x28)
            f.read(h)
            if (ascii(h, 0, 4) != "\u0000PBP") return null
            val from = le32(h, 0x08)
            val to = le32(h, 0x0C)
            if (to <= from || to - from > 65536) return null
            val sfo = ByteArray(to - from)
            f.seek(from.toLong()); f.read(sfo)
            return sfo(sfo)["DISC_ID"]?.let { "s:" + serialKey(it) }
        }
    }

    // ------------------------------------------------------------------ bytes

    /** Hasta [n] bytes del flujo, menos si se acaba antes. */
    private fun take(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        val got = fill(input, out)
        return if (got == n) out else out.copyOf(maxOf(got, 0))
    }

    /** Llena [buf] todo lo que se pueda: un `read` suelto puede devolver menos sin haber acabado. */
    private fun fill(input: InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val r = input.read(buf, got, buf.size - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    private fun hex(v: Long) = "%08x".format(v and 0xFFFFFFFFL)
    private fun ascii(b: ByteArray, from: Int, to: Int): String =
        if (to > b.size || from < 0) "" else String(b, from, to - from, Charsets.ISO_8859_1)
    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}
