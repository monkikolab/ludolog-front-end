package com.felp.frontcomp

import java.io.File

/**
 * La carpeta de ROMs: encontrar la que haya y, si no hay, hacerla.
 *
 * Hecha como la hace ES-DE, con una subcarpeta por consola y sus mismos nombres. No por
 * imitar: es el arbol que ya tiene medio mundo en la tarjeta, el que leen Daijisho, Beacon o
 * Jump, y el que ES-DE reconoce si algun dia se instala al lado. Una biblioteca empezada aqui
 * sirve tal cual en cualquiera de ellos.
 */
internal object RomFolders {

    /**
     * Los nombres de carpeta de ES-DE, tal cual los crea su «Create/update system directories».
     *
     * Copiados de una tarjeta preparada por ES-DE (la de una consola de pruebas), y no de memoria: lo que
     * valga alli es lo que tiene que salir aqui.
     */
    private val ESDE = setOf(
        "3do", "adam", "amiga", "amiga1200", "amiga600", "amigacd32", "amstradcpc", "androidapps",
        "androidgames", "apple2", "apple2gs", "arcade", "arcadia", "archimedes", "arduboy",
        "astrocde", "atari2600", "atari5200", "atari7800", "atari800", "atarijaguar", "atarilynx",
        "atarist", "atarixe", "atomiswave", "bbcmicro", "c64", "cdimono1", "cdtv", "chailove",
        "channelf", "coco", "colecovision", "consolearcade", "cps", "cps1", "cps2", "cps3",
        "crvision", "daphne", "doom", "dos", "dragon32", "dreamcast", "easyrpg", "electron",
        "emulators", "epic", "famicom", "fba", "fbneo", "fds", "flash", "fm7", "fmtowns", "gamate",
        "gameandwatch", "gamecom", "gamegear", "gb", "gba", "gbc", "gc", "genesis", "gmaster",
        "gx4000", "intellivision", "j2me", "laserdisc", "lcdgames", "lowresnx", "lutro",
        "macintosh", "mame", "mark3", "mastersystem", "megacd", "megacdjp", "megadrive",
        "megadrivejp", "megaduck", "mess", "model2", "model3", "moto", "msx", "msx1", "msx2",
        "msxturbor", "multivision", "n3ds", "n64", "n64dd", "naomi", "naomi2", "naomigd", "nds",
        "neogeo", "neogeocd", "neogeocdjp", "nes", "ngage", "ngp", "ngpc", "odyssey2", "openbor",
        "oric", "palm", "pc", "pc88", "pc98", "pcarcade", "pcengine", "pcenginecd", "pcfx", "pico8",
        "plus4", "pokemini", "ports", "ps2", "ps3", "psp", "psvita", "psx", "pv1000", "quake",
        "samcoupe", "satellaview", "saturn", "saturnjp", "scummvm", "scv", "sega32x", "sega32xjp",
        "sega32xna", "segacd", "sfc", "sg-1000", "sgb", "snes", "snesna", "spectravideo", "steam",
        "stv", "sufami", "supergrafx", "supervision", "supracan", "switch", "symbian", "tanodragon",
        "tg-cd", "tg16", "ti99", "tic80", "to8", "type-x", "uzebox", "vectrex", "vic20", "videopac",
        "vircon32", "virtualboy", "vpinball", "vsmile", "wasm4", "wii", "wiiu", "windows",
        "windows3x", "windows9x", "wonderswan", "wonderswancolor", "x1", "x68000", "zmachine",
        "zx81", "zxspectrum",
    )

    /** Como se llaman las que se buscan solas en cada unidad. Ver Scanner.defaultRoots. */
    val NAMES = listOf("ROMs", "Roms", "roms", "Emulation/roms")

    /**
     * La carpeta de una consola: el nombre de ES-DE si alguno de los suyos lo es —su id primero,
     * luego sus alias en el orden del catalogo—, y si no, su id. GameCube es «gc» y los juegos de
     * PC «steam», como en ES-DE; lo que ES-DE no tiene se queda con el nombre de aqui.
     */
    fun folderFor(sys: SystemDef): String = SAME_NAME_OTHER_THING[sys.id]
        ?: (listOf(sys.id) + sys.aliases).map { it.lowercase() }.firstOrNull { it in ESDE } ?: sys.id

    /**
     * Los ids de aqui que en ES-DE son otra cosa. «pc» alli es el PC de MS-DOS; aqui son los
     * juegos de PC por GameNative y compañia, que en ES-DE van en «steam».
     */
    private val SAME_NAME_OTHER_THING = mapOf("pc" to "steam")

    /** La carpeta de ROMs de una unidad, si tiene alguna de las de siempre. */
    fun existingOn(volume: File): File? = NAMES.map { File(volume, it) }.firstOrNull { it.isDirectory }

    /** Cuantas subcarpetas son de alguna consola que se sabe leer. */
    fun consoles(dir: File, catalog: Catalog): Int =
        dir.listFiles()?.count { it.isDirectory && catalog.forFolder(it.name) != null } ?: 0

    /**
     * Hace una subcarpeta por consola del catalogo dentro de [root], las que falten, y la
     * propia [root] si no existe. Devuelve cuantas hizo.
     *
     * Solo las consolas que este programa sabe leer, no las ciento ochenta de ES-DE: una
     * carpeta que nadie mira invita a dejar juegos que luego no salen. La de Android no, que
     * sus juegos son apps y no ficheros. Y nada de lo que ya hay se toca: una carpeta que
     * existe se deja como esta, y un `systeminfo.txt` solo se escribe en las nuevas.
     */
    fun create(root: File, catalog: Catalog): Int {
        if (!root.isDirectory && !root.mkdirs()) error("Cannot create ${root.path}")
        val have: MutableSet<String> = root.listFiles()?.filter { it.isDirectory }
            ?.map { it.name.lowercase() }?.toHashSet() ?: HashSet()
        var made = 0
        for (sys in catalog.systems) {
            if (sys.id == ANDROID_SYSTEM) continue
            // Una consola que ya tiene carpeta, con cualquiera de sus nombres, no lleva otra:
            // una tarjeta con «psx» no necesita ademas «playstation».
            if (have.any { catalog.forFolder(it)?.id == sys.id }) continue
            val name = folderFor(sys)
            val dir = File(root, name)
            if (dir.isDirectory || !dir.mkdirs()) continue
            have.add(name)
            made++
            runCatching { File(dir, "systeminfo.txt").writeText(info(sys, catalog)) }
        }
        return made
    }

    /**
     * Lo que dice el `systeminfo.txt` de una carpeta nueva: que consola es y que ficheros
     * espera, con los rotulos de ES-DE para que quien venga de alli lo lea igual.
     */
    private fun info(sys: SystemDef, catalog: Catalog): String = buildString {
        appendLine("System name:").appendLine(folderFor(sys)).appendLine()
        appendLine("Full system name:").appendLine(sys.name).appendLine()
        appendLine("Supported file extensions:")
        appendLine(catalog.extensionsOf(sys).sorted().joinToString(" ") { ".$it" })
    }
}
