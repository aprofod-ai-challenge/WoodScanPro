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
private const val R2_TOKEN = "..."
private const val SQL_URL = "https://woodscan-sql.nima00.workers.dev"
private const val SQL_TOKEN = "..."

/** Nel database c'è già una scansione diversa con questo codice. */
class CodeTakenException(code: String) : IOException("Codice $code già presente nel database")

/** Database condiviso tra i dispositivi, usato da ScanRepository. */
interface ScanDatabase {
    /** Identifica la destinazione: ciò che è stato caricato su un altro [id] risulta ancora da caricare. */
    val id: String

    /** True se è una simulazione (l'interfaccia lo segnala). */
    val isMock: Boolean

    /**
     * Carica [tar] come <code>.tar senza mai sovrascrivere: se il codice c'è già con un altro contenuto
     * lancia [CodeTakenException], se c'è già lo stesso (tentativo precedente) termina senza errori.
     */
    suspend fun uploadScan(code: String, tar: File)
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