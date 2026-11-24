package com.example.woodscanpro

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** File da inserire nel tar: si apre solo al momento della scrittura. */
class TarFile(val name: String, val size: Long, val modifiedMs: Long, val open: () -> InputStream)

/**
 * <codice>.tar di una scansione, formato ustar senza cartelle né compressione (il video è già compresso):
 *   <codice>.mp4
 *   <codice>.orientation.json    (se disponibile)
 *   <codice>-calibration.json    (se il dispositivo era calibrato al salvataggio)
 */
object ScanTar {
    const val VIDEO = ".mp4"
    const val ORIENTATION = ".orientation.json"
    const val CALIBRATION = "-calibration.json"

    private const val BLOCK = 512
    private const val MAX_SIZE = 1L shl 33   // 8 GiB: 11 cifre ottali

    /** Scrive il tar su un file temporaneo e lo rende visibile solo quando è completo. [dest] non deve esistere. */
    fun write(dest: File, files: List<TarFile>) = writeNew(dest) { out -> files.forEach { f -> f.open().use { put(out, f.name, f.size, f.modifiedMs, it) } } }

    /** Copia [src] in [dest] cambiando il codice nei nomi dei file. */
    fun copyRenamed(src: File, dest: File, oldCode: String, newCode: String) = writeNew(dest) { out ->
        src.inputStream().use { input ->
            val h = ByteArray(BLOCK)
            while (true) {
                readFully(input, h)
                if (h.all { it.toInt() == 0 }) break
                val name = String(h, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
                val size = octal(h, 124, 12)
                if (!name.startsWith(oldCode)) throw IOException("File inatteso nel tar: $name")
                put(out, newCode + name.removePrefix(oldCode), size, octal(h, 136, 12) * 1000, input)
                readFully(input, ByteArray(padding(size)))
            }
        }
    }

    private fun writeNew(dest: File, body: (OutputStream) -> Unit) {
        val tmp = File(dest.path + ".part")
        try {
            tmp.outputStream().use { file ->
                val out = file.buffered(1 shl 16)
                body(out)
                out.write(ByteArray(2 * BLOCK))   // fine archivio
                out.flush()
                file.fd.sync()   // il video originale viene cancellato dopo il salvataggio
            }
            if (dest.exists() || !tmp.renameTo(dest)) throw IOException("Impossibile creare ${dest.name}")
        } finally {
            tmp.delete()
        }
    }

    /** Copia esattamente [size] byte di [input]. */
    private fun put(out: OutputStream, name: String, size: Long, modifiedMs: Long, input: InputStream) {
        out.write(header(name, size, modifiedMs / 1000))
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw EOFException("$name è più corto del previsto")
            out.write(buf, 0, n)
            left -= n
        }
        out.write(ByteArray(padding(size)))
    }

    private fun header(name: String, size: Long, modifiedSec: Long): ByteArray {
        if (name.length > 100 || name.any { it.code !in 33..126 }) throw IOException("Nome non valido: $name")
        if (size >= MAX_SIZE) throw IOException("$name supera 8 GB")
        val h = ByteArray(BLOCK)
        fun text(pos: Int, s: String) = s.toByteArray(Charsets.US_ASCII).copyInto(h, pos)
        fun octal(pos: Int, digits: Int, v: Long) = text(pos, v.toString(8).padStart(digits, '0'))
        text(0, name)
        octal(100, 7, 420)            // permessi 0644
        octal(108, 7, 0)              // uid
        octal(116, 7, 0)              // gid
        octal(124, 11, size)
        octal(136, 11, modifiedSec)
        text(148, "        ")         // la somma si calcola con questo campo a spazi
        text(156, "0")                // file normale
        text(257, "ustar\u000000")
        octal(148, 6, h.sumOf { it.toLong() and 0xff })
        h[154] = 0
        return h
    }

    private fun padding(size: Long) = ((BLOCK - size % BLOCK) % BLOCK).toInt()

    private fun octal(h: ByteArray, pos: Int, len: Int) =
        String(h, pos, len, Charsets.US_ASCII).trim(' ', '\u0000').toLongOrNull(8) ?: throw IOException("Tar danneggiato")

    private fun readFully(input: InputStream, buf: ByteArray) {
        var pos = 0
        while (pos < buf.size) {
            val n = input.read(buf, pos, buf.size - pos)
            if (n < 0) throw EOFException("Tar incompleto")
            pos += n
        }
    }
}
