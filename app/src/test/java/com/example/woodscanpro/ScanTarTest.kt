package com.example.woodscanpro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ScanTarTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(name: String, data: ByteArray) = TarFile(name, data.size.toLong(), 1_700_000_000_000) { data.inputStream() }

    /** Legge con il tar di sistema, indipendente dal codice dell'app. */
    private fun extract(tar: File): Map<String, ByteArray> {
        val dir = tmp.newFolder()
        val p = ProcessBuilder("tar", "-xf", tar.path, "-C", dir.path).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().decodeToString()
        assertEquals(log, 0, p.waitFor())
        assertEquals("", log)
        return dir.listFiles()!!.associate { it.name to it.readBytes() }
    }

    @Test fun writeAndRename() {
        val video = ByteArray(300_001) { (it * 31).toByte() }
        val orientation = ByteArray(1024) { 'a'.code.toByte() }
        val calibration = "{}".toByteArray()
        val a = File(tmp.root, "A-1.tar")
        ScanTar.write(a, listOf(
            file("A-1.mp4", video), file("A-1.orientation.json", orientation), file("A-1-calibration.json", calibration)
        ))
        val code = "B".repeat(ScanCode.MAX_LENGTH)
        val b = File(tmp.root, "$code.tar")
        ScanTar.copyRenamed(a, b, "A-1", code)
        for ((tar, c) in listOf(a to "A-1", b to code)) {
            val x = extract(tar)
            assertEquals(setOf("$c.mp4", "$c.orientation.json", "$c-calibration.json"), x.keys)
            assertArrayEquals(video, x["$c.mp4"])
            assertArrayEquals(orientation, x["$c.orientation.json"])
            assertArrayEquals(calibration, x["$c-calibration.json"])
        }
    }

    @Test fun videoOnly() {
        val t = File(tmp.root, "x.tar")
        ScanTar.write(t, listOf(file("x.mp4", ByteArray(512))))
        assertEquals(setOf("x.mp4"), extract(t).keys)
    }

    @Test fun failuresLeaveNothing() {
        val t = File(tmp.root, "x.tar")
        assertThrows(IOException::class.java) { ScanTar.write(t, listOf(TarFile("x.mp4", 10, 0) { ByteArray(5).inputStream() })) }
        assertFalse(t.exists())
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun neverOverwrites() {
        val t = File(tmp.root, "x.tar").apply { writeText("old") }
        assertThrows(IOException::class.java) { ScanTar.write(t, listOf(file("x.mp4", ByteArray(1)))) }
        assertEquals("old", t.readText())
    }
}
