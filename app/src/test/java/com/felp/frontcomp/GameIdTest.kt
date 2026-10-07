package com.felp.frontcomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ficheros hechos a mano con la forma de los de verdad: cada clave tiene que salir igual que la
 * escribe el generador del catalogo, o el juego no se encuentra.
 */
class GameIdTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun crc(b: ByteArray, from: Int = 0) = "c:%08x".format(CRC32().apply { update(b, from, b.size - from) }.value)
    private fun bytes(n: Int, seed: Int = 7) = ByteArray(n) { ((it * 31 + seed) and 0xFF).toByte() }
    private fun file(name: String, b: ByteArray) = File(tmp.root, name).apply { writeBytes(b) }

    @Test fun `un cartucho da su CRC`() {
        val rom = bytes(64 * 1024)
        assertEquals(listOf(crc(rom)), GameId.of(file("Chrono Trigger (USA).sfc", rom)))
    }

    @Test fun `una ROM de NES da el CRC con cabecera y sin ella`() {
        val body = bytes(40960)
        val rom = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) + body
        val keys = GameId.of(file("Mega Man 2 (USA).nes", rom))
        assertEquals(listOf(crc(rom), crc(body)), keys)
    }

    @Test fun `un n64 y un v64 salen con el CRC del z64`() {
        val z64 = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + bytes(4096 - 4)
        val n64 = z64.copyOf().also { b -> for (i in b.indices step 4) { b.reverse(i, i + 4) } }
        val v64 = z64.copyOf().also { b -> for (i in b.indices step 2) { val x = b[i]; b[i] = b[i + 1]; b[i + 1] = x } }
        assertEquals(listOf(crc(z64)), GameId.of(file("a.n64", n64)))
        assertEquals(listOf(crc(z64)), GameId.of(file("b.v64", v64)))
        assertEquals(listOf(crc(z64)), GameId.of(file("c.z64", z64)))
    }

    @Test fun `un smd se desentrelaza antes de su CRC`() {
        val bin = bytes(32768)
        // Cada bloque de 16 KB: los bytes impares primero y los pares despues.
        val smd = ByteArray(512 + bin.size)
        for (blk in bin.indices step 16384) for (i in 0 until 8192) {
            smd[512 + blk + i] = bin[blk + i * 2 + 1]
            smd[512 + blk + 8192 + i] = bin[blk + i * 2]
        }
        assertEquals(crc(bin), GameId.of(file("Earthworm Jim (USA).smd", smd)).first())
        assertTrue(GameId.cartBytes(smd, "smd").first() == crc(bin))
    }

    @Test fun `un zip da su romset y el CRC de dentro sin descomprimir`() {
        val rom = bytes(20000)
        val zip = File(tmp.root, "Super Metroid (Japan, USA).zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Super Metroid (Japan, USA).sfc")); z.write(rom); z.closeEntry()
        }
        assertEquals(listOf("r:super metroid (japan, usa)", crc(rom)), GameId.of(zip))
    }

    @Test fun `un romset de arcade no suelta los CRC de sus trozos`() {
        val zip = File(tmp.root, "mslug.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            for (n in listOf("201-p1.p1", "201-s1.s1", "201-m1.m1", "201-v1.v1", "201-c1.c1")) {
                z.putNextEntry(ZipEntry(n)); z.write(bytes(100, n.length)); z.closeEntry()
            }
        }
        assertEquals(listOf("r:mslug"), GameId.of(zip))
    }

    @Test fun `un CHD v5 da el SHA1 de sus datos`() {
        val h = ByteArray(124)
        "MComprHD".toByteArray().copyInto(h)
        h[15] = 5
        for (i in 0 until 20) h[0x40 + i] = (0xA0 + i).toByte()
        assertEquals(listOf("h:a0a1a2a3a4a5a6a7"), GameId.of(file("God of War (USA).chd", h + ByteArray(1000))))
    }

    /** Una ISO 9660 minima: el descriptor en el sector 16, la raiz en el 18 y el fichero en el 19. */
    private fun iso(files: Map<String, ByteArray>): ByteArray {
        val img = ByteArray(2048 * (20 + files.size))
        val pvd = 16 * 2048
        img[pvd] = 1
        "CD001".toByteArray().copyInto(img, pvd + 1)
        fun le32(at: Int, v: Int) { for (i in 0 until 4) img[at + i] = (v shr (8 * i)).toByte() }
        le32(pvd + 156 + 2, 18)
        le32(pvd + 156 + 10, 2048)
        var p = 18 * 2048
        fun record(name: ByteArray, lba: Int, size: Int, dir: Boolean) {
            val len = 33 + name.size + (if (name.size % 2 == 0) 1 else 0)
            img[p] = len.toByte()
            le32(p + 2, lba); le32(p + 10, size)
            img[p + 25] = if (dir) 2 else 0
            img[p + 32] = name.size.toByte()
            name.copyInto(img, p + 33)
            p += len
        }
        record(byteArrayOf(0), 18, 2048, true)
        record(byteArrayOf(1), 18, 2048, true)
        var lba = 19
        for ((n, data) in files) {
            record("$n;1".toByteArray(), lba, data.size, false)
            data.copyInto(img, lba * 2048)
            lba++
        }
        return img
    }

    @Test fun `una ISO de PS2 da el serial de su SYSTEM CNF`() {
        val cnf = "BOOT2 = cdrom0:\\SLUS_203.12;1\r\nVER = 1.00\r\n".toByteArray()
        assertEquals(listOf("s:SLUS20312"), GameId.of(file("Silent Hill 2.iso", iso(mapOf("SYSTEM.CNF" to cnf)))))
    }

    @Test fun `una imagen cruda de PlayStation, sola o por su cue`() {
        val cooked = iso(mapOf("SYSTEM.CNF" to "BOOT = cdrom:\\SLUS_008.20;1\r\n".toByteArray()))
        // Modo 2: marca de sincronia, cabecera de 4, subcabecera de 8, y los 2048 de datos.
        val raw = ByteArray(cooked.size / 2048 * 2352)
        for (s in 0 until cooked.size / 2048) {
            val at = s * 2352
            for (i in 1..10) raw[at + i] = 0xFF.toByte()
            raw[at + 15] = 2
            cooked.copyInto(raw, at + 24, s * 2048, s * 2048 + 2048)
        }
        val bin = file("Echo Night (ESP).bin", raw)
        assertEquals(listOf("s:SLUS00820"), GameId.of(bin))
        val cue = file("Echo Night (ESP).cue", "FILE \"Echo Night (ESP).bin\" BINARY\n  TRACK 01 MODE2/2352\n".toByteArray())
        assertEquals(listOf("s:SLUS00820"), GameId.of(cue))
    }

    @Test fun `una ISO de PSP da el serial de UMD_DATA`() {
        val umd = "ULUS-10041|0001|G|...".toByteArray()
        assertEquals(listOf("s:ULUS10041"), GameId.of(file("GoW.iso", iso(mapOf("UMD_DATA.BIN" to umd)))))
    }

    @Test fun `discos de GameCube y Wii dan su codigo de juego`() {
        val gc = ByteArray(0x440)
        "GALE01".toByteArray().copyInto(gc)
        byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D).copyInto(gc, 0x1C)
        assertEquals(listOf("i:GALE"), GameId.of(file("Melee.iso", gc)))
        val rvz = ByteArray(0x100)
        "RVZ".toByteArray().copyInto(rvz); rvz[3] = 1
        "RMCE01".toByteArray().copyInto(rvz, 0x58)
        assertEquals(listOf("i:RMCE"), GameId.of(file("Mario Kart Wii.rvz", rvz)))
    }

    @Test fun `DS y 3DS dan su codigo de juego`() {
        val nds = ByteArray(0x200)
        "METROIDPRIME".toByteArray().copyInto(nds)
        "AMHE".toByteArray().copyInto(nds, 0x0C)
        val keys = GameId.of(file("Metroid Prime Hunters.nds", nds))
        assertEquals("g:AMHE", keys.first())
        assertTrue(keys.any { it.startsWith("c:") })
        // 3DS: la tabla del NCSD dice que la primera particion empieza en 0x4000 (0x20 x 0x200).
        val ctr = ByteArray(0x4200)
        "NCSD".toByteArray().copyInto(ctr, 0x100)
        ctr[0x120] = 0x20
        "NCCH".toByteArray().copyInto(ctr, 0x4100)
        "CTR-P-AREE".toByteArray().copyInto(ctr, 0x4150)
        assertEquals(listOf("g:AREE"), GameId.of(file("Kid Icarus Uprising.3ds", ctr)))
    }

    @Test fun `el PARAM SFO se lee entero`() {
        // Dos claves: TITLE (texto) y DISC_ID (texto).
        val keys = "TITLE\u0000DISC_ID\u0000".toByteArray()
        val v1 = "Test Game\u0000".toByteArray(); val v2 = "BLUS30001\u0000".toByteArray()
        val header = 20 + 2 * 16
        val keyTable = header; val dataTable = keyTable + keys.size + 2
        val b = ByteArray(dataTable + 16 + 16)
        "\u0000PSF".toByteArray().copyInto(b)
        fun le16(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
        fun le32(at: Int, v: Int) { for (i in 0 until 4) b[at + i] = (v shr (8 * i)).toByte() }
        le32(8, keyTable); le32(12, dataTable); le32(16, 2)
        le16(20, 0); le16(22, 0x0204); le32(24, v1.size); le32(32, 0)
        le16(36, 6); le16(38, 0x0204); le32(40, v2.size); le32(48, 16)
        keys.copyInto(b, keyTable); v1.copyInto(b, dataTable); v2.copyInto(b, dataTable + 16)
        assertEquals(mapOf("TITLE" to "Test Game", "DISC_ID" to "BLUS30001"), GameId.sfo(b))
    }

    @Test fun `el serial y el Title ID del nombre`() {
        assertTrue("s:SLUS00820" in GameId.of(file("Echo Night [SLUS-00820].pbp", ByteArray(8))))
        assertEquals(listOf("t:0100AB1234567000"), GameId.of(file("Game [0100ab1234567000][v0].xci", ByteArray(8))))
    }

    @Test fun `un acceso de Steam da su numero`() {
        assertEquals(listOf("v:274520"), GameId.of(file("Darkwood.steam", "274520\n".toByteArray())))
        // Otra cosa con esa extension no da nada.
        assertEquals(emptyList<String>(), GameId.of(file("Raro.steam", "no es un numero".toByteArray())))
    }
}
