package com.example.woodscanpro

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val R2_URL = "https://woodscan-r2.nima00.workers.dev"
private const val R2_TOKEN = "dcd36cbaa97f0944a3d22b32951d169aba2378e13434203500bc511ee052edf1"
private const val SQL_URL = "https://woodscan-sql.nima00.workers.dev"
private const val SQL_TOKEN = "16b101942dd7bb3cc7fe7567b473a15c7d5450c4be31b0666b38692e9a45a0e5"

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

/** Database vero: <codice>.tar nel bucket R2 e una riga (id = codice, data, archivio = <codice>.tar) nella tabella "scansioni" di D1. */
class CloudflareScanDatabase : ScanDatabase {
    override val id = "cloudflare"
    override val isMock = false

    override suspend fun uploadScan(code: String, tar: File) {
        val key = "$code.tar"
        // già nel bucket con la stessa dimensione = tentativo precedente riuscito
        val list = JSONObject(call("$R2_URL/lista?prefix=$key", R2_TOKEN, "GET")).getJSONArray("Contents")
        val size = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.getString("Key") == key }?.getLong("Size")
        when (size) {
            null -> call("$R2_URL/oggetto/$key", R2_TOKEN, "PUT", tar.length(), "application/x-tar") { out ->
                tar.inputStream().use { it.copyTo(out) }
            }
            tar.length() -> {}
            else -> throw CodeTakenException(code)
        }

        val data = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(tar.lastModified()))
        val body = JSONObject()
            .put("sql", "INSERT INTO scansioni (id, data, archivio) VALUES (?, ?, ?)")
            .put("params", JSONArray(listOf(code, data, key)))
            .toString().toByteArray()
        try {
            call(SQL_URL, SQL_TOKEN, "POST", body.size.toLong(), "application/json") { it.write(body) }
        } catch (e: IOException) {
            if ("UNIQUE" !in e.message.orEmpty()) throw e   // riga già inserita da un tentativo precedente
        }
    }

    /** Richiesta HTTP con il token; restituisce il corpo della risposta, eccezione se non è 2xx. */
    private fun call(url: String, token: String, method: String, length: Long = 0, type: String = "",
                     write: ((OutputStream) -> Unit)? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (write != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(length)
                c.setRequestProperty("Content-Type", type)
                c.outputStream.use(write)
            }
            val ok = c.responseCode in 200..299
            val text = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (!ok) throw IOException("$method $url: HTTP ${c.responseCode} $text")
            return text
        } finally {
            c.disconnect()
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