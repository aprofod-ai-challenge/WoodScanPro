package com.example.woodscanpro

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Scansione salvata in <code>.tar. [uploadedTo] è l'id del database su cui è stata caricata (null = mai).
 * [codeTaken]: il database ha rifiutato il codice, va rinominata.
 * [localDeleted]: copia locale cancellata dopo il caricamento; il codice resta occupato.
 */
data class ScanRecord(
    val code: String,
    val createdAt: Long,
    val hasOrientation: Boolean,
    val hasCalibration: Boolean,
    val uploadedTo: String? = null,
    val codeTaken: Boolean = false,
    val localDeleted: Boolean = false,
    val missing: Boolean = false,   // non salvato: tar rimosso fuori dall'app
)

enum class UploadResult { DONE, OFFLINE, CODE_TAKEN, FAILED }

/**
 * Salvataggio delle scansioni e coda di caricamento. I file stanno nella memoria dell'app,
 * Android/data/<app>/files/: calibration.json e un <codice>.tar per scansione.
 * L'indice è in noBackupFilesDir: descrive questo dispositivo e non deve essere ripristinato su un altro.
 */
class ScanRepository private constructor(private val context: Context, private val database: ScanDatabase) {

    /** Vive quanto il processo: salvataggi e caricamenti non si fermano se l'activity viene ricreata. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val isMock get() = database.isMock
    private val rootOrNull: File? get() = context.getExternalFilesDir(null)
    private val root: File get() = rootOrNull ?: throw IOException("Memoria non disponibile")
    private val indexFile = AtomicFile(File(context.noBackupFilesDir, "scan_index.json"))
    private val lock = Any()                // protegge lo stato e l'indice
    private val saveMutex = Mutex()         // un salvataggio o una rinomina alla volta
    private val uploadMutex = Mutex()       // un caricamento, una rinomina o una cancellazione alla volta

    // Stato osservabile da Compose
    private var records by mutableStateOf(emptyList<ScanRecord>())
    /** True se c'è calibration.json. */
    var calibrated by mutableStateOf(false); private set
    /** Codice in corso di caricamento, null se nessuno. */
    var uploading by mutableStateOf<String?>(null); private set

    init {
        load()
        rootOrNull?.let { dir ->
            dir.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }   // scritture interrotte
            calibrated = calibrationFile(dir).exists()
        }
    }

    /** Scansioni presenti sul dispositivo, dalla più recente. */
    val scans: List<ScanRecord> get() = records.filter { !it.localDeleted }.sortedByDescending { it.createdAt }
    val usedCodes: List<String> get() = records.map { it.code }
    val pendingCount: Int get() = scans.count { !isUploaded(it) && !it.missing }

    fun isUploaded(scan: ScanRecord) = scan.uploadedTo == database.id

    private fun calibrationFile(root: File) = File(root, "calibration.json")
    private fun tarFile(code: String) = File(root, "$code.tar")

    /**
     * Crea <code>.tar con video, orientamento e la calibrazione attuale (questi ultimi se ci sono).
     * In caso di errore lancia un'eccezione e i file temporanei restano al chiamante.
     */
    suspend fun save(code: String, video: File, orientation: File?): ScanRecord = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            val dest = checkFree(code)
            val calibration = readCalibration()
            val now = System.currentTimeMillis()
            ScanTar.write(dest, listOfNotNull(
                TarFile(code + ScanTar.VIDEO, video.length(), now) { video.inputStream() },
                orientation?.let { TarFile(code + ScanTar.ORIENTATION, it.length(), now) { it.inputStream() } },
                calibration?.let { TarFile(code + ScanTar.CALIBRATION, it.size.toLong(), now) { it.inputStream() } },
            ))
            val record = ScanRecord(code, now, orientation != null, calibration != null)
            try {
                update { it + record }
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
            record
        }
    }

    /** Cambia il codice di una scansione non ancora caricata: il tar viene ricreato con i nuovi nomi. */
    suspend fun rename(oldCode: String, newCode: String) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            uploadMutex.withLock {
                val scan = find(oldCode)
                check(!isUploaded(scan)) { "Scansione già caricata" }
                val dest = checkFree(newCode)
                val old = tarFile(oldCode)
                ScanTar.copyRenamed(old, dest, oldCode, newCode)
                try {
                    update { l -> l.map { if (it.code == oldCode) it.copy(code = newCode, codeTaken = false) else it } }
                } catch (e: Exception) {
                    dest.delete()
                    throw e
                }
                if (!old.delete()) Log.w(TAG, "Impossibile cancellare ${old.name}")
            }
        }
    }

    private fun checkFree(code: String): File {
        ScanCode.error(code, usedCodes)?.let { throw IllegalArgumentException(it) }
        return tarFile(code).also {
            if (it.exists()) throw IllegalArgumentException("Codice già usato: esiste già ${it.name}")
        }
    }

    private fun find(code: String) =
        records.firstOrNull { it.code == code && !it.localDeleted } ?: error("Scansione $code non trovata")

    /** null solo se il dispositivo non è calibrato: altri errori di lettura si propagano. */
    private fun readCalibration(): ByteArray? = synchronized(lock) {
        val f = calibrationFile(root)
        if (f.exists()) f.readBytes() else null
    }

    /** Sostituisce calibration.json: vale per le scansioni salvate da qui in poi. */
    suspend fun saveCalibration(json: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val f = calibrationFile(root)
            val tmp = File(f.path + ".part")
            try {
                tmp.outputStream().use { it.write(json.toByteArray()); it.fd.sync() }
                if (!tmp.renameTo(f)) throw IOException("Scrittura di calibration.json non riuscita")
            } finally {
                tmp.delete()
            }
            calibrated = true
        }
    }

    /** Carica il tar della scansione [code]. */
    suspend fun upload(code: String): UploadResult = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            if (!isOnline(context)) return@withLock UploadResult.OFFLINE
            uploading = code
            try {
                if (!isUploaded(find(code))) {
                    val tar = tarFile(code)
                    if (!tar.exists()) throw FileNotFoundException(tar.name)
                    database.uploadScan(code, tar)
                    replace(code) { it.copy(uploadedTo = database.id, codeTaken = false) }
                }
                UploadResult.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: CodeTakenException) {
                runCatching { replace(code) { it.copy(codeTaken = true) } }.onFailure { Log.e(TAG, "Indice non scritto", it) }
                UploadResult.CODE_TAKEN
            } catch (e: Exception) {
                Log.e(TAG, "Caricamento di $code non riuscito", e)
                refreshMissing()
                UploadResult.FAILED
            } finally {
                uploading = null
            }
        }
    }

    /** Carica tutto ciò che manca; si ferma subito se non c'è connessione, altrimenti prova ogni scansione. */
    suspend fun uploadAll(): UploadResult {
        var result = UploadResult.DONE
        scans.filter { !isUploaded(it) && !it.missing }.reversed().forEach { scan ->
            val r = upload(scan.code)
            if (r == UploadResult.OFFLINE) return r
            if (r > result) result = r   // in ordine di gravità
        }
        return result
    }

    /**
     * Cancella la copia locale. Consentito solo se la scansione è stata caricata (il codice resta occupato)
     * o se il tar non c'è più (la voce sparisce e il codice torna libero).
     */
    suspend fun deleteLocal(code: String): Boolean = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            val scan = records.firstOrNull { it.code == code && !it.localDeleted } ?: return@withLock false
            val tar = File(rootOrNull ?: return@withLock false, "$code.tar")
            val uploaded = isUploaded(scan)
            if (!uploaded && tar.exists()) return@withLock false
            if (tar.exists() && !tar.delete()) return@withLock false
            if (uploaded) replace(code) { it.copy(localDeleted = true) }
            else update { l -> l.filter { it.code != code } }
            true
        }
    }

    /** Controlla quali file sono stati rimossi fuori dall'app. */
    suspend fun refreshMissing() = withContext(Dispatchers.IO) {
        val dir = rootOrNull ?: return@withContext
        synchronized(lock) {
            records = records.map { it.copy(missing = !it.localDeleted && !File(dir, "${it.code}.tar").exists()) }
            calibrated = calibrationFile(dir).exists()
        }
    }

    private fun replace(code: String, change: (ScanRecord) -> ScanRecord) =
        update { l -> l.map { if (it.code == code) change(it) else it } }

    /** Lo stato cambia solo se l'indice è stato scritto. */
    private fun update(change: (List<ScanRecord>) -> List<ScanRecord>) = synchronized(lock) {
        val next = change(records)
        persist(next)
        records = next
    }

    private fun persist(list: List<ScanRecord>) {
        val json = JSONObject().put("scans", JSONArray(list.map { r ->
            JSONObject()
                .put("code", r.code)
                .put("createdAt", r.createdAt)
                .put("hasOrientation", r.hasOrientation)
                .put("hasCalibration", r.hasCalibration)
                .put("codeTaken", r.codeTaken)
                .put("localDeleted", r.localDeleted)
                .putOpt("uploadedTo", r.uploadedTo)
        }))
        val out = indexFile.startWrite()
        try {
            out.write(json.toString(2).toByteArray())
            indexFile.finishWrite(out)
        } catch (e: Exception) {
            indexFile.failWrite(out)
            throw e
        }
    }

    private fun load() {
        val text = try { String(indexFile.readFully()) } catch (e: FileNotFoundException) { return }
        try {
            val arr = JSONObject(text).getJSONArray("scans")
            records = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ScanRecord(
                    code = o.getString("code"),
                    createdAt = o.getLong("createdAt"),
                    hasOrientation = o.getBoolean("hasOrientation"),
                    hasCalibration = o.getBoolean("hasCalibration"),
                    uploadedTo = if (o.has("uploadedTo")) o.getString("uploadedTo") else null,
                    codeTaken = o.getBoolean("codeTaken"),
                    localDeleted = o.getBoolean("localDeleted"),
                )
            }
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "Indice delle scansioni illeggibile, messo da parte", e)
            File(context.noBackupFilesDir, "scan_index.corrupt.json").writeText(text)
        }
    }

    companion object {
        private const val TAG = "WoodScanRepo"
        @Volatile private var instance: ScanRepository? = null

        fun get(context: Context): ScanRepository = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                // Qui si sostituirà il database vero
                ScanRepository(app, MockScanDatabase(app)).also { instance = it }
            }
        }
    }
}
