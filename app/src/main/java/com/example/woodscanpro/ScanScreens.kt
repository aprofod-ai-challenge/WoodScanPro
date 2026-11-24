package com.example.woodscanpro

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ok = Color(0xFF10B981)
private val Warn = Color(0xFFF59E0B)
private val Muted = Color(0xFF94A3B8)
private val Scheme = darkColorScheme(
    primary = Ok, onPrimary = Color.Black, surface = Color(0xFF0F172A), surfaceContainerHigh = Color(0xFF1E293B)
)

fun uploadMessage(result: UploadResult, what: String): String = when (result) {
    UploadResult.DONE -> "$what: caricamento completato"
    UploadResult.OFFLINE -> "$what: nessuna connessione, da caricare in seguito"
    UploadResult.CODE_TAKEN -> "$what: codice già presente nel database, rinomina da Scansioni"
    UploadResult.FAILED -> "$what: caricamento non riuscito, riprova da Scansioni"
}

/** Chiede il codice della scansione. [onConfirm] riceve un codice già valido; [saveError] è l'errore dell'ultimo salvataggio. */
@Composable
fun ScanCodeDialog(
    usedCodes: List<String>,
    calibrated: Boolean,
    saving: Boolean,
    saveError: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Codice della scansione",
) {
    var code by rememberSaveable { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val trimmed = code.trim()
    // durante il salvataggio il codice entra tra quelli usati: non va segnalato come errore
    val error = if (saving) null else ScanCode.error(trimmed, usedCodes)
    val shown = error?.takeIf { attempted || trimmed.isNotEmpty() } ?: saveError?.takeIf { !saving }

    fun confirm() {
        attempted = true
        if (error == null && !saving) onConfirm(trimmed)
    }

    MaterialTheme(colorScheme = Scheme) {
        AlertDialog(
            onDismissRequest = { if (!saving) onDismiss() },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        enabled = !saving,
                        singleLine = true,
                        isError = shown != null,
                        supportingText = { Text(shown ?: "Lettere, cifre, - e _") },
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!calibrated) Text(
                        "Dispositivo non calibrato: la calibrazione è fortemente consigliata, ma puoi salvare e caricare anche senza.",
                        color = Warn, fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { confirm() }, enabled = !saving) { Text(if (saving) "Salvataggio…" else "Salva") }
            },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Annulla") } }
        )
    }
}

/** Elenco delle scansioni sul dispositivo: stato, caricamento, cancellazione di quelle caricate. */
@Composable
fun ScanListScreen(repo: ScanRepository, onClose: () -> Unit) {
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<ScanRecord?>(null) }
    var toRename by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val busy = repo.uploading != null || renaming

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { repo.refreshMissing() }

    fun launchUpload(what: String, block: suspend () -> UploadResult) {
        repo.scope.launch {
            val message = uploadMessage(block(), what)
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    MaterialTheme(colorScheme = Scheme) {
        Surface(modifier = Modifier.fillMaxSize()) {   // Surface blocca i tocchi verso la schermata sotto
            Column(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scansioni", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Chiudi") }
                }
                Text(
                    if (repo.calibrated) "Dispositivo calibrato" else "Dispositivo non calibrato: calibrazione fortemente consigliata",
                    color = if (repo.calibrated) Ok else Warn, fontSize = 13.sp
                )
                if (repo.isMock) Text("Database simulato: i dati restano su questo dispositivo", color = Muted, fontSize = 12.sp)

                val pending = repo.pendingCount
                if (pending > 0) {
                    Button(
                        onClick = { launchUpload("Dati da caricare") { repo.uploadAll() } },
                        enabled = !busy, modifier = Modifier.fillMaxWidth()
                    ) { Text(repo.uploading?.let { "Caricamento di $it…" } ?: "Carica tutto ($pending scansioni)", maxLines = 1) }
                }

                if (repo.scans.isEmpty()) Text("Nessuna scansione sul dispositivo", color = Muted)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(repo.scans, key = { it.code }) { scan ->
                        val uploaded = repo.isUploaded(scan)
                        Row(
                            modifier = Modifier.fillMaxWidth().background(Color(0xFF1E293B), RoundedCornerShape(12.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(scan.code, fontWeight = FontWeight.Bold)
                                Text(
                                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(scan.createdAt)) +
                                            (if (scan.hasOrientation) "" else " · senza orientamento") +
                                            (if (scan.hasCalibration) "" else " · senza calibrazione"),
                                    color = Muted, fontSize = 12.sp
                                )
                                Text(
                                    when {
                                        repo.uploading == scan.code -> "Caricamento…"
                                        uploaded && scan.missing -> "Caricata · file locali rimossi"
                                        uploaded -> "Caricata"
                                        scan.missing -> "File rimossi dal dispositivo"
                                        scan.codeTaken -> "Codice già presente nel database"
                                        else -> "Da caricare"
                                    },
                                    color = if (uploaded) Ok else Warn, fontSize = 12.sp
                                )
                            }
                            when {
                                uploaded || scan.missing ->
                                    TextButton(onClick = { toDelete = scan }, enabled = !busy) { Text("Elimina", color = Color(0xFFF87171)) }
                                scan.codeTaken -> Button(
                                    onClick = { renameError = null; toRename = scan.code }, enabled = !busy
                                ) { Text("Rinomina") }
                                else -> Button(
                                    onClick = { launchUpload(scan.code) { repo.upload(scan.code) } }, enabled = !busy
                                ) { Text("Carica") }
                            }
                        }
                    }
                }
            }
        }

        toRename?.let { old ->
            ScanCodeDialog(
                usedCodes = repo.usedCodes,
                calibrated = true,   // qui l'avviso non serve: la calibrazione è fissata al salvataggio
                saving = renaming,
                saveError = renameError,
                onConfirm = { new ->
                    renaming = true
                    repo.scope.launch {
                        try {
                            repo.rename(old, new)
                            toRename = null
                        } catch (e: Exception) {
                            Log.e("WoodScanRepo", "Rinomina non riuscita", e)
                            renameError = if (e is IllegalArgumentException) e.message else "Rinomina non riuscita: ${e.message}"
                            return@launch
                        } finally {
                            renaming = false
                        }
                        Toast.makeText(context, uploadMessage(repo.upload(new), new), Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { toRename = null },
                title = "Nuovo codice per $old"
            )
        }

        toDelete?.let { scan ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Eliminare ${scan.code}?") },
                text = {
                    Text(
                        if (!repo.isUploaded(scan)) "I file non sono più sul dispositivo: la voce viene rimossa dall'elenco e il codice torna disponibile."
                        else "La copia sul dispositivo viene cancellata definitivamente." +
                                if (repo.isMock) " Attenzione: il database è simulato, non esiste una copia fuori da questo dispositivo." else ""
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        toDelete = null
                        repo.scope.launch {
                            if (!repo.deleteLocal(scan.code))
                                Toast.makeText(context, "Eliminazione non riuscita", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Elimina", color = Color(0xFFF87171)) }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } }
            )
        }
    }
}
