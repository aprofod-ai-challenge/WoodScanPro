package com.example.woodscanpro

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.io.IOException

/** Nel database c'è già una scansione diversa con questo codice. */
class CodeTakenException(code: String) : IOException("Codice $code già presente nel database")

/**
 * Database condiviso tra i dispositivi: contiene solo i file <codice>.tar (vedi ScanTar).
 * Per collegare il database vero basta implementare questa interfaccia e passarla a ScanRepository.
 */
interface ScanDatabase {
    /** Identifica la destinazione: ciò che è stato caricato su un altro [id] risulta ancora da caricare. */
    val id: String

    /** True se è una simulazione: l'interfaccia lo segnala. */
    val isMock: Boolean

    /**
     * Carica [tar] come <code>.tar, visibile solo quando è completo. Non sovrascrive mai:
     *  - se il codice c'è già (senza distinguere maiuscole e minuscole) con un contenuto diverso
     *    lancia [CodeTakenException]; il controllo deve essere atomico rispetto agli altri dispositivi
     *  - se c'è già lo stesso contenuto (tentativo precedente riuscito ma non registrato) termina senza errori
     * Per ogni altro problema lancia un'eccezione.
     */
    suspend fun uploadScan(code: String, tar: File)
}

/** Database simulato: una cartella locale, Android/data/<app>/files/mock_database/. */
class MockScanDatabase(private val context: Context) : ScanDatabase {
    override val id = "mock-tar"
    override val isMock = true

    override suspend fun uploadScan(code: String, tar: File) {
        val root = File(context.getExternalFilesDir(null) ?: throw IOException("Memoria non disponibile"), "mock_database")
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Database simulato non accessibile")
        root.listFiles().orEmpty().firstOrNull { it.name.equals("$code.tar", ignoreCase = true) }?.let {
            if (it.name == "$code.tar" && sameContent(it, tar)) return
            throw CodeTakenException(code)
        }
        val tmp = File(root, "$code.tar.part")
        try {
            tar.inputStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            if (!tmp.renameTo(File(root, "$code.tar"))) throw IOException("Scrittura di $code.tar non riuscita")
        } finally {
            tmp.delete()
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                while (true) {
                    val v = x.read()
                    if (v != y.read()) return false
                    if (v < 0) return true
                }
            }
        }
    }
}

/** True se c'è una connessione a Internet verificata dal sistema. */
fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
